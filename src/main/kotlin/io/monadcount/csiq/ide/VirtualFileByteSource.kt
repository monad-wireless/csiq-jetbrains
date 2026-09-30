package io.monadcount.csiq.ide

import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileSystem
import io.monadcount.csiq.format.ByteSource
import io.monadcount.csiq.format.DEFAULT_BUFFER
import io.monadcount.csiq.format.FileByteSource
import io.monadcount.csiq.format.skipFully
import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream

/**
 * A [ByteSource] over any IntelliJ virtual file.
 *
 * This is the whole of the Remote File Systems and Big Data Tools story, and it
 * is deliberately small. Those plugins mount S3, HDFS and SFTP as ordinary
 * entries in the platform's virtual file system. A viewer that reads through
 * [VirtualFile] rather than through a filesystem path therefore works on a
 * remote capture the day it works on a local one, with no API dependency on
 * either plugin and nothing to keep in step when they change.
 *
 * Three rules make that true, and all three are enforced here.
 *
 *  * **Never call `contentsToByteArray`.** The platform refuses it above about
 *    20 MB, and a capture is routinely a hundred times that.
 *  * **Never turn the file into a path unless it really is local.** A remote
 *    file's `path` is an address inside its own filesystem, not something
 *    `File` can open.
 *  * **Say whether seeking is cheap.** A local file gets true random access. A
 *    remote object does not, and every reader in this plugin already has a
 *    sequential path for that case.
 */
class VirtualFileByteSource(private val file: VirtualFile) : ByteSource {

    private val local: FileByteSource? = localFile(file)?.let(::FileByteSource)

    override val length: Long get() = file.length

    override val displayName: String get() = file.presentableUrl

    override val randomAccess: Boolean get() = local != null

    /** True when the bytes come over a network rather than off this machine. */
    val isRemote: Boolean get() = local == null

    /** The filesystem's protocol, for display: `s3`, `sftp`, `file`, and so on. */
    val protocol: String get() = file.fileSystem.protocol

    override fun open(offset: Long): InputStream {
        local?.let { return it.open(offset) }
        // A remote filesystem gives a forward-only stream. Seeking means
        // reading and discarding, which is why callers are told not to seek.
        val stream = BufferedInputStream(file.inputStream, DEFAULT_BUFFER)
        if (offset > 0) stream.skipFully(offset)
        return stream
    }

    override fun close() {
        local?.close()
    }

    companion object {
        /**
         * The local file behind a virtual file, or null when there is none.
         *
         * `toNioPath` throws for a filesystem that has no local representation,
         * which is exactly how a remote entry announces itself. Catching that
         * is the check, because a protocol allow-list would have to be revised
         * for every filesystem a plugin adds.
         */
        fun localFile(file: VirtualFile): File? = try {
            if (!file.isInLocalFileSystem) {
                null
            } else {
                file.toNioPath().toFile().takeIf { it.isFile }
            }
        } catch (e: UnsupportedOperationException) {
            thisLogger().debug("no local path for ${file.presentableUrl}", e)
            null
        } catch (e: java.nio.file.InvalidPathException) {
            thisLogger().debug("unusable local path for ${file.presentableUrl}", e)
            null
        }

        /** True when reading this file will go over a network. */
        fun isRemote(file: VirtualFile): Boolean = localFile(file) == null

        /** Names the filesystem in a way a person recognises. */
        fun describe(fs: VirtualFileSystem): String = when (fs.protocol) {
            "file" -> "local disk"
            "s3" -> "S3 (Remote File Systems)"
            "sftp" -> "SFTP (Remote File Systems)"
            "hdfs" -> "HDFS (Big Data Tools)"
            "gs" -> "Google Cloud Storage (Remote File Systems)"
            "wasbs", "abfss" -> "Azure storage (Big Data Tools)"
            else -> fs.protocol
        }
    }
}
