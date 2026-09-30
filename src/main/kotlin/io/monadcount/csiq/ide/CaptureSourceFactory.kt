package io.monadcount.csiq.ide

import com.github.luben.zstd.ZstdInputStream
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.vfs.VirtualFile
import io.monadcount.csiq.format.ByteSource
import io.monadcount.csiq.format.Compression
import io.monadcount.csiq.format.DEFAULT_BUFFER
import io.monadcount.csiq.format.EnvelopeDetector
import io.monadcount.csiq.format.EnvelopeVerdict
import io.monadcount.csiq.format.FileByteSource
import io.monadcount.csiq.format.Payload
import io.monadcount.csiq.format.SessionSidecar
import io.monadcount.csiq.format.fmt
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.apache.commons.compress.compressors.lz4.FramedLZ4CompressorInputStream
import org.apache.commons.compress.compressors.lzma.LZMACompressorInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/** A capture opened and ready to index, with a note on how it was obtained. */
class OpenedCapture(
    val source: ByteSource,
    val payload: Payload,
    val compression: Compression,
    val verdict: EnvelopeVerdict,
    /** The archive's `metadata.json`, when one sits beside the capture. */
    val sidecar: SessionSidecar?,
    /** Other files in the same session directory, for display. */
    val siblings: List<String>,
    /** One sentence for the status line: where the bytes came from. */
    val provenance: String,
) : AutoCloseable {
    override fun close() {
        source.close()
    }
}

/**
 * Turns a virtual file into something the readers can walk.
 *
 * Three problems are solved here and nowhere else.
 *
 *  * **A compressed stream cannot be seeked.** It is expanded once into a local
 *    cache file and the rest of the plugin never knows there was a codec.
 *  * **A remote object is expensive to re-read.** Over S3 every jump costs a
 *    request and a re-read from the start. Fetching once into the same cache
 *    converts that into an ordinary local file.
 *  * **A capture is not the whole session.** The archive stores the container,
 *    the raw stream and a `metadata.json` in one directory, and the sidecar
 *    carries facts the capture cannot - above all the monitor width, which a
 *    raw driver stream has no field for.
 */
object CaptureSourceFactory {

    /**
     * Names the archive writes beside a capture.
     *
     * Listed so the Session view can say what else the directory holds. The
     * layout is one directory per capture session, and it is the same on disk
     * and under `s3://.../datasets/ax210-csi-captures/<host>/<session>/`.
     */
    private val KNOWN_SIBLINGS = listOf(
        "capture.csiq", "capture.csiq.zst", "capture.raw", "metadata.json",
        "ble_rssi.parquet", "ble_scan.jsonl",
        "time_transfer.parquet", "time_transfer.jsonl",
        "frame_census.jsonl",
    )

    fun open(file: VirtualFile, indicator: ProgressIndicator?): OpenedCapture {
        val prefix = readPrefix(file)
        val outer = EnvelopeDetector.detect(file.name, prefix)
        val settings = CsiqSettings.getInstance()
        val remote = VirtualFileByteSource.isRemote(file)
        val sidecar = readSidecar(file)
        val siblings = listSiblings(file)

        if (outer.compression.isCompressed) {
            // Expand first, then ask what is inside: the container's own magic
            // is hidden by the codec, so the payload cannot be sniffed before.
            val cached = materialise(file, indicator, outer.compression)
            val innerPrefix = readLocalPrefix(cached)
            val verdict = EnvelopeDetector.detect(file.name, prefix, innerPrefix)
            return OpenedCapture(
                source = FileByteSource(cached.toFile()),
                payload = verdict.payload,
                compression = outer.compression,
                verdict = verdict,
                sidecar = sidecar,
                siblings = siblings,
                provenance = "expanded from ${outer.compression.label}: " +
                    "${formatBytes(file.length)} compressed, ${formatBytes(Files.size(cached))} expanded" +
                    if (remote) ", fetched from ${VirtualFileByteSource.describe(file.fileSystem)}" else "",
            )
        }

        if (remote && settings.cacheRemoteCaptures && file.length in 1..settings.cacheLimitBytes) {
            val cached = materialise(file, indicator, Compression.NONE)
            return OpenedCapture(
                source = FileByteSource(cached.toFile()),
                payload = outer.payload,
                compression = Compression.NONE,
                verdict = outer,
                sidecar = sidecar,
                siblings = siblings,
                provenance = "fetched ${formatBytes(file.length)} from " +
                    "${VirtualFileByteSource.describe(file.fileSystem)} into the local cache",
            )
        }

        val provenance = when {
            remote -> "streaming from ${VirtualFileByteSource.describe(file.fileSystem)} " +
                "(not cached: ${if (!settings.cacheRemoteCaptures) "caching is off" else "over the cache limit"}); " +
                "record reads will be slow"
            else -> "local disk"
        }
        return OpenedCapture(
            source = VirtualFileByteSource(file),
            payload = outer.payload,
            compression = Compression.NONE,
            verdict = outer,
            sidecar = sidecar,
            siblings = siblings,
            provenance = provenance,
        )
    }

    /**
     * The `metadata.json` in the same directory.
     *
     * Found through the virtual file system, so it works on S3 exactly as on
     * disk. It is 18 KB in the archive, which is cheap enough to fetch even
     * when the capture beside it will not be.
     */
    private fun readSidecar(file: VirtualFile): SessionSidecar? {
        val sibling = file.parent?.findChild(SessionSidecar.FILE_NAME) ?: return null
        if (!sibling.isValid || sibling.isDirectory) return null
        return try {
            // Small and bounded, so reading it whole is safe here. The guard
            // exists because nothing stops a directory holding a large file
            // under that name.
            if (sibling.length > MAX_SIDECAR_BYTES) return null
            SessionSidecar.parse(sibling.inputStream.use { it.readBytes() }.toString(Charsets.UTF_8))
        } catch (e: IOException) {
            thisLogger().debug("could not read the sidecar beside ${file.presentableUrl}", e)
            null
        }
    }

    private fun listSiblings(file: VirtualFile): List<String> {
        val parent = file.parent ?: return emptyList()
        return try {
            parent.children
                ?.filter { !it.isDirectory && it.name != file.name }
                ?.map { it.name }
                ?.sortedBy { name -> KNOWN_SIBLINGS.indexOf(name).takeIf { it >= 0 } ?: Int.MAX_VALUE }
                ?: emptyList()
        } catch (e: Exception) {
            thisLogger().debug("could not list the directory of ${file.presentableUrl}", e)
            emptyList()
        }
    }

    /** The first bytes, for sniffing. Cheap even on a remote filesystem. */
    private fun readPrefix(file: VirtualFile): ByteArray = try {
        file.inputStream.use { readPrefix(it) }
    } catch (e: IOException) {
        thisLogger().warn("could not sniff ${file.presentableUrl}", e)
        ByteArray(0)
    }

    private fun readLocalPrefix(path: Path): ByteArray = try {
        Files.newInputStream(path).use { readPrefix(it) }
    } catch (e: IOException) {
        ByteArray(0)
    }

    private fun readPrefix(input: InputStream): ByteArray {
        val buf = ByteArray(EnvelopeDetector.SNIFF_LENGTH)
        var read = 0
        while (read < buf.size) {
            val r = input.read(buf, read, buf.size - read)
            if (r < 0) break
            read += r
        }
        return buf.copyOf(read)
    }

    /**
     * Copy, and optionally expand, into the cache. Returns the cached path.
     *
     * The key covers the file's identity, length and timestamp, so a capture
     * that was replaced upstream is fetched again rather than served stale.
     */
    private fun materialise(file: VirtualFile, indicator: ProgressIndicator?, codec: Compression): Path {
        val dir = cacheDir()
        Files.createDirectories(dir)
        val target = dir.resolve(cacheKey(file, codec))
        if (Files.exists(target) && Files.size(target) > 0) {
            runCatching {
                Files.setLastModifiedTime(
                    target,
                    java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis()),
                )
            }
            return target
        }

        val temp = Files.createTempFile(dir, "partial-", ".tmp")
        try {
            indicator?.text = if (codec.isCompressed) "Expanding ${codec.label} capture" else "Fetching capture"
            indicator?.isIndeterminate = file.length <= 0
            val total = file.length
            var copied = 0L
            decompressing(file, codec).use { input ->
                Files.newOutputStream(temp).use { out ->
                    val buf = ByteArray(DEFAULT_BUFFER)
                    while (true) {
                        indicator?.checkCanceled()
                        val r = input.read(buf)
                        if (r < 0) break
                        out.write(buf, 0, r)
                        copied += r
                        // Progress is measured on the expanded byte count,
                        // which has no known total, so only a plain copy can
                        // show a fraction.
                        if (total > 0 && !codec.isCompressed) {
                            indicator?.fraction = (copied.toDouble() / total).coerceIn(0.0, 1.0)
                        }
                        indicator?.text2 = formatBytes(copied)
                    }
                }
            }
            Files.move(temp, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        } catch (e: ProcessCanceledException) {
            Files.deleteIfExists(temp)
            throw e
        } catch (e: Throwable) {
            Files.deleteIfExists(temp)
            throw e
        }
        evict(dir)
        return target
    }

    /**
     * Wrap the file's stream in the right decoder.
     *
     * zstd goes through zstd-jni, which is what csid writes against. The rest
     * go through commons-compress. A codec with no decoder is refused by name
     * rather than reported as a corrupt container, which is what the format
     * specification asks a reader to do.
     */
    private fun decompressing(file: VirtualFile, codec: Compression): InputStream {
        val raw = BufferedInputStream(file.inputStream, DEFAULT_BUFFER)
        return try {
            when (codec) {
                Compression.NONE -> raw
                Compression.ZSTD -> ZstdInputStream(raw)
                // Concatenated members are expanded: `cat a.gz b.gz` is a valid
                // stream and a capture that arrived that way is still one
                // capture.
                Compression.GZIP -> GzipCompressorInputStream.builder()
                    .setInputStream(raw)
                    .setDecompressConcatenated(true)
                    .get()
                Compression.XZ -> XZCompressorInputStream.builder()
                    .setInputStream(raw)
                    .setDecompressConcatenated(true)
                    .get()
                Compression.BZIP2 -> BZip2CompressorInputStream(raw, true)
                Compression.LZ4 -> FramedLZ4CompressorInputStream(raw, true)
                Compression.LZMA -> LZMACompressorInputStream(raw)
            }
        } catch (e: NoClassDefFoundError) {
            raw.close()
            throw IllegalStateException(
                "${file.name} is ${codec.label}, and this build has no ${codec.label} decoder.",
                e,
            )
        } catch (e: IOException) {
            raw.close()
            throw IllegalStateException(
                "${file.name} looks like ${codec.label} but the stream could not be opened: ${e.message}",
                e,
            )
        }
    }

    private fun cacheDir(): Path = Path.of(PathManager.getSystemPath(), "csiq-captures")

    private fun cacheKey(file: VirtualFile, codec: Compression): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(file.url.toByteArray())
        digest.update(file.length.toString().toByteArray())
        digest.update(file.timeStamp.toString().toByteArray())
        val hash = digest.digest().take(10).joinToString("") { "%02x".fmt(it) }
        // The session directory is part of the name, because every capture in
        // the archive is called capture.csiq and a cache full of files with one
        // name is unreadable.
        val session = file.parent?.name?.takeIf { it.isNotBlank() }?.take(40) ?: ""
        val stem = Compression.stripExtension(file.name).substringBeforeLast(".")
        val safe = "$session-$stem".replace(Regex("[^A-Za-z0-9._-]"), "_").take(64)
        return "$safe-$hash.bin"
    }

    /** Drop the least recently used cache entries until the budget is met. */
    private fun evict(dir: Path) {
        val budget = CsiqSettings.getInstance().cacheBudgetBytes
        val files = runCatching {
            Files.list(dir).use { s -> s.map(Path::toFile).filter(File::isFile).toList() }
        }.getOrNull() ?: return
        var total = files.sumOf { it.length() }
        if (total <= budget) return
        for (f in files.sortedBy { it.lastModified() }) {
            if (total <= budget) break
            val size = f.length()
            if (f.delete()) total -= size
        }
    }

    private const val MAX_SIDECAR_BYTES = 4L * 1024 * 1024

    fun formatBytes(n: Long): String = when {
        n < 1024 -> "$n B"
        n < 1024L * 1024 -> "%.1f KB".fmt(n / 1024.0)
        n < 1024L * 1024 * 1024 -> "%.1f MB".fmt(n / (1024.0 * 1024))
        else -> "%.2f GB".fmt(n / (1024.0 * 1024 * 1024))
    }
}
