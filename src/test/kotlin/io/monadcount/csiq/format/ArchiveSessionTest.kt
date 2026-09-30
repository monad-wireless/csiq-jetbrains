package io.monadcount.csiq.format

import com.github.luben.zstd.ZstdInputStream
import io.monadcount.csiq.model.CaptureLoader
import io.monadcount.csiq.model.LoadProgress
import java.io.BufferedInputStream
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * A whole session directory, the way the archive stores one.
 *
 * Point `CSIQ_TEST_SESSION` at a directory holding a `capture.csiq.zst` and its
 * `metadata.json` - a copy of any
 * `s3://.../datasets/ax210-csi-captures/<host>/<session>/` will do. Without it
 * these are skipped, because the repository carries a 24-record fixture rather
 * than a gigabyte of measurements.
 *
 * What this proves that the unit tests cannot: a real zstd frame from the
 * archive expands, the container inside it indexes, and the record count the
 * capturer wrote into its sidecar is the record count this reader finds.
 */
class ArchiveSessionTest {

    private fun sessionDir(): File? =
        System.getenv("CSIQ_TEST_SESSION")?.let(::File)?.takeIf { it.isDirectory }

    @Test
    fun `a real compressed capture expands and indexes`() {
        val dir = sessionDir() ?: return
        val compressed = File(dir, "capture.csiq.zst").takeIf { it.isFile } ?: return

        // The envelope is decided from the bytes, exactly as the plugin does it.
        val prefix = compressed.inputStream().use { it.readNBytes(EnvelopeDetector.SNIFF_LENGTH) }
        assertEquals(Compression.ZSTD, Compression.bySniff(prefix), "the archive writes zstd")

        val expanded = Files.createTempFile("csiq-test-", ".csiq")
        try {
            ZstdInputStream(BufferedInputStream(compressed.inputStream())).use { input ->
                Files.newOutputStream(expanded).use { input.copyTo(it) }
            }
            val innerPrefix = Files.newInputStream(expanded).use { it.readNBytes(EnvelopeDetector.SNIFF_LENGTH) }
            val verdict = EnvelopeDetector.detect(compressed.name, prefix, innerPrefix)
            assertEquals(Compression.ZSTD, verdict.compression)
            assertEquals(Payload.CSIQ, verdict.payload, "a zstd frame around a CSIQ container")

            val sidecar = File(dir, SessionSidecar.FILE_NAME)
                .takeIf { it.isFile }
                ?.let { SessionSidecar.parse(it.readText()) }

            val capture = CaptureLoader.load(
                source = FileByteSource(expanded.toFile()),
                payload = verdict.payload,
                progress = SilentProgress,
                compression = verdict.compression,
                sidecar = sidecar,
            )

            println(
                "archive session ${dir.name}: ${capture.recordCount} records, " +
                    "${Files.size(expanded)} expanded from ${compressed.length()} " +
                    "(%.1f %%)".format(compressed.length() * 100.0 / Files.size(expanded)),
            )

            assertTrue(capture.recordCount > 0, "a real capture should hold records")
            assertEquals(ScanEnd.Clean, capture.scanEnd, "a synced capture should end cleanly")

            // The claim and the count, side by side. A mismatch here is what a
            // half-uploaded object looks like, so it is worth failing on.
            sidecar?.summaryRecords?.let { claimed ->
                assertEquals(
                    claimed,
                    capture.recordCount.toLong(),
                    "the sidecar claims $claimed records and this reader found ${capture.recordCount}",
                )
            }
        } finally {
            Files.deleteIfExists(expanded)
        }
    }

    @Test
    fun `the sidecar supplies the width a raw stream lacks`() {
        val dir = sessionDir() ?: return
        val raw = File(dir, "capture.raw").takeIf { it.isFile } ?: return
        val sidecar = assertNotNull(
            File(dir, SessionSidecar.FILE_NAME).takeIf { it.isFile }?.let { SessionSidecar.parse(it.readText()) },
            "a session directory carries a metadata.json",
        )
        val width = assertNotNull(sidecar.monitorWidth, "the sidecar states the monitor width")

        val capture = CaptureLoader.load(
            source = FileByteSource(raw),
            payload = Payload.RAW_DRIVER_STREAM,
            progress = SilentProgress,
            sidecar = sidecar,
        )
        assertTrue(capture.recordCount > 0)
        assertEquals(
            width,
            capture.health.monitorWidth,
            "a raw stream opened beside its sidecar should report the session's width",
        )

        // And without the sidecar it must say it does not know, rather than guess.
        val alone = CaptureLoader.load(
            source = FileByteSource(raw),
            payload = Payload.RAW_DRIVER_STREAM,
            progress = SilentProgress,
        )
        assertEquals(Width.UNKNOWN, alone.health.monitorWidth)
    }

    private object SilentProgress : LoadProgress {
        override fun onBytes(read: Long, total: Long) = Unit
        override fun onRecords(count: Int) = Unit
        override val isCancelled: Boolean get() = false
    }
}
