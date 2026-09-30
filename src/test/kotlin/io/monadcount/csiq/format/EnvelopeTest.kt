package io.monadcount.csiq.format

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Envelope detection, against the names the archive actually uses.
 *
 * The census that these cases come from is the capture archive itself: under
 * `s3://.../datasets/ax210-csi-captures/<host>/<session>/` one node holds 712
 * `capture.csiq.zst`, 273 `capture.csiq` and 1,001 `capture.raw`, each beside a
 * `metadata.json`. All four names have to work, and the compressed spellings
 * other tools produce have to work too.
 */
class EnvelopeTest {

    private fun fixture(): ByteArray =
        checkNotNull(javaClass.getResourceAsStream("/fixtures/ambient-24-legacy52.csiq"))
            .use { it.readBytes() }

    @Test
    fun `the archive's own file names are recognised`() {
        assertEquals(Payload.CSIQ, Payload.byName("capture.csiq"))
        assertEquals(Payload.CSIQ, Payload.byName("capture.csiq.zst"))
        assertEquals(Payload.RAW_DRIVER_STREAM, Payload.byName("capture.raw"))

        assertEquals(Compression.NONE, Compression.byName("capture.csiq") ?: Compression.NONE)
        assertEquals(Compression.ZSTD, Compression.byName("capture.csiq.zst"))
    }

    @Test
    fun `every codec extension maps to its codec`() {
        val cases = mapOf(
            "capture.csiq.zst" to Compression.ZSTD,
            "capture.csiq.zstd" to Compression.ZSTD,
            "capture.csiq.gz" to Compression.GZIP,
            "capture.csiq.xz" to Compression.XZ,
            "capture.csiq.bz2" to Compression.BZIP2,
            "capture.csiq.lz4" to Compression.LZ4,
            "capture.csiq.lzma" to Compression.LZMA,
            "capture.raw.zst" to Compression.ZSTD,
        )
        for ((name, expected) in cases) {
            assertEquals(expected, Compression.byName(name), name)
            // The payload has to survive the codec extension being stripped.
            val expectedPayload =
                if (name.startsWith("capture.raw")) Payload.RAW_DRIVER_STREAM else Payload.CSIQ
            assertEquals(expectedPayload, Payload.byName(name), name)
        }
    }

    @Test
    fun `magic bytes decide the codec, whatever the name says`() {
        assertEquals(Compression.ZSTD, Compression.bySniff(byteArrayOf(0x28, 0xB5.toByte(), 0x2F, 0xFD.toByte())))
        assertEquals(Compression.GZIP, Compression.bySniff(byteArrayOf(0x1F, 0x8B.toByte(), 0x08, 0x00)))
        assertEquals(Compression.BZIP2, Compression.bySniff(byteArrayOf(0x42, 0x5A, 0x68, 0x39)))
        assertEquals(Compression.LZ4, Compression.bySniff(byteArrayOf(0x04, 0x22, 0x4D, 0x18)))
        assertEquals(
            Compression.XZ,
            Compression.bySniff(byteArrayOf(0xFD.toByte(), 0x37, 0x7A, 0x58, 0x5A, 0x00)),
        )
        assertNull(Compression.bySniff(Csiq.MAGIC), "a plain container is not compressed")
    }

    @Test
    fun `an uncompressed container is recognised from its bytes`() {
        val verdict = EnvelopeDetector.detect("capture.csiq", fixture().copyOf(EnvelopeDetector.SNIFF_LENGTH))
        assertEquals(Compression.NONE, verdict.compression)
        assertEquals(Payload.CSIQ, verdict.payload)
        assertFalse(verdict.disagrees)
        assertNull(verdict.disagreement())
    }

    @Test
    fun `a compressed file takes its payload from the expanded bytes`() {
        val zstdMagic = byteArrayOf(0x28, 0xB5.toByte(), 0x2F, 0xFD.toByte(), 0, 0, 0, 0)
        val verdict = EnvelopeDetector.detect(
            "capture.csiq.zst",
            zstdMagic,
            innerPrefix = fixture().copyOf(EnvelopeDetector.SNIFF_LENGTH),
        )
        assertEquals(Compression.ZSTD, verdict.compression)
        assertEquals(Payload.CSIQ, verdict.payload)
        assertFalse(verdict.disagrees)
    }

    @Test
    fun `a name that lies about the codec is reported, and the bytes win`() {
        // A capture that was expanded somewhere and kept its .zst name.
        val verdict = EnvelopeDetector.detect(
            "capture.csiq.zst",
            fixture().copyOf(EnvelopeDetector.SNIFF_LENGTH),
        )
        assertEquals(Compression.NONE, verdict.compression, "the bytes are a plain container")
        assertEquals(Payload.CSIQ, verdict.payload)
        assertTrue(verdict.disagrees)
        val message = assertNotNull(verdict.disagreement())
        assertTrue(message.contains("zstd"), message)
        assertTrue(message.contains("bytes win"), message)
    }

    @Test
    fun `a raw driver stream is recognised structurally, since it has no magic`() {
        // [be32 msg_len][be32 hdr_len = 272] is the signature.
        val prefix = ByteArray(16)
        writeBe32(prefix, 0, 1000)
        writeBe32(prefix, 4, Csiq.VENDOR_HEADER_LEN)
        assertEquals(Payload.RAW_DRIVER_STREAM, Payload.bySniff(prefix))

        // A header length that is not 272 is not this stream.
        writeBe32(prefix, 4, 64)
        assertEquals(Payload.UNKNOWN, Payload.bySniff(prefix))
    }

    @Test
    fun `stripping a codec extension leaves the payload name`() {
        assertEquals("capture.csiq", Compression.stripExtension("capture.csiq.zst"))
        assertEquals("capture.csiq", Compression.stripExtension("capture.csiq.gz"))
        assertEquals("capture.csiq", Compression.stripExtension("capture.csiq"))
        assertEquals("capture.raw", Compression.stripExtension("capture.raw.bz2"))
    }

    private fun writeBe32(b: ByteArray, o: Int, v: Int) {
        b[o] = (v ushr 24).toByte()
        b[o + 1] = (v ushr 16).toByte()
        b[o + 2] = (v ushr 8).toByte()
        b[o + 3] = v.toByte()
    }
}
