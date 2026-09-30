package io.monadcount.csiq.format

/**
 * How a capture is wrapped.
 *
 * The CSIQ specification compresses the *file* and never the format: a
 * `capture.csiq.zst` is a zstd frame around an unchanged CSIQ byte stream, and
 * a reader that expands it first meets exactly the bytes the document
 * describes. Nothing about that argument is specific to zstd, so every codec
 * here is treated the same way - expand, then read the container inside.
 *
 * The specification's reader rule is to decide by extension and to say so in
 * those terms when a decoder is missing, rather than reporting a corrupt
 * container. This goes one better and decides by magic bytes, because a
 * download that lost or added an extension is a real thing that happens to an
 * archive. The name is still read, and a disagreement is reported.
 */
enum class Compression(
    val label: String,
    /** Magic bytes at offset 0. Empty for [NONE]. */
    val magic: ByteArray,
    /** Extensions that name this codec, lower case, with the dot. */
    val extensions: List<String>,
) {
    NONE("uncompressed", byteArrayOf(), emptyList()),

    /** The codec csid writes. `capture.csiq.zst`. */
    ZSTD("zstd", byteArrayOf(0x28, 0xB5.toByte(), 0x2F, 0xFD.toByte()), listOf(".zst", ".zstd")),

    GZIP("gzip", byteArrayOf(0x1F, 0x8B.toByte()), listOf(".gz", ".gzip")),

    XZ("xz", byteArrayOf(0xFD.toByte(), 0x37, 0x7A, 0x58, 0x5A, 0x00), listOf(".xz")),

    BZIP2("bzip2", byteArrayOf(0x42, 0x5A, 0x68), listOf(".bz2", ".bzip2")),

    /** The LZ4 *frame* format, not a bare block. */
    LZ4("lz4", byteArrayOf(0x04, 0x22, 0x4D, 0x18), listOf(".lz4")),

    /** Standalone LZMA, which precedes xz. Its magic is weak, so name only. */
    LZMA("lzma", byteArrayOf(), listOf(".lzma")),
    ;

    val isCompressed: Boolean get() = this != NONE

    companion object {
        /** Every extension that names a codec, for file-type registration. */
        val ALL_EXTENSIONS: List<String> = entries.flatMap { it.extensions }

        fun bySniff(prefix: ByteArray): Compression? =
            entries.firstOrNull { it.magic.isNotEmpty() && prefix.startsWith(it.magic) }

        fun byName(name: String): Compression? {
            val lower = name.lowercase()
            return entries.firstOrNull { c -> c.extensions.any { lower.endsWith(it) } }
        }

        /** Strip a trailing codec extension, so `a.csiq.zst` becomes `a.csiq`. */
        fun stripExtension(name: String): String {
            val lower = name.lowercase()
            val ext = ALL_EXTENSIONS.firstOrNull { lower.endsWith(it) } ?: return name
            return name.dropLast(ext.length)
        }
    }
}

/** What is inside, once any compression is undone. */
enum class Payload(val label: String) {
    /** A CSIQ container. */
    CSIQ("CSIQ container"),

    /** The lossless iax driver stream, `capture.raw`. */
    RAW_DRIVER_STREAM("raw driver stream"),

    UNKNOWN("unrecognised"),
    ;

    companion object {
        fun bySniff(prefix: ByteArray): Payload = when {
            prefix.startsWith(Csiq.MAGIC) -> CSIQ
            RawStream.looksLikeRawStream(prefix) -> RAW_DRIVER_STREAM
            else -> UNKNOWN
        }

        fun byName(name: String): Payload {
            val bare = Compression.stripExtension(name).lowercase()
            return when {
                bare.endsWith(".csiq") -> CSIQ
                bare.endsWith(".raw") -> RAW_DRIVER_STREAM
                else -> UNKNOWN
            }
        }
    }
}

/**
 * What the bytes are, and what the name claimed they were.
 *
 * Both are kept rather than reconciled, because the difference is information:
 * a `capture.csiq` that is actually gzip has been through a tool that
 * decompressed or recompressed it, and that is worth telling the operator.
 */
class EnvelopeVerdict(
    val compression: Compression,
    val payload: Payload,
    val compressionByName: Compression?,
    val payloadByName: Payload,
) {
    /** True when the name does not describe the bytes. */
    val disagrees: Boolean
        get() = (compressionByName != null && compressionByName != compression) ||
            (payloadByName != Payload.UNKNOWN && payload != Payload.UNKNOWN && payloadByName != payload)

    /** One clause naming the disagreement, or null. */
    fun disagreement(): String? {
        if (!disagrees) return null
        val named = buildString {
            append(if (payloadByName == Payload.UNKNOWN) "an unknown payload" else payloadByName.label)
            compressionByName?.takeIf { it.isCompressed }?.let { append(" in ").append(it.label) }
        }
        val found = buildString {
            append(if (payload == Payload.UNKNOWN) "an unknown payload" else payload.label)
            if (compression.isCompressed) append(" in ").append(compression.label)
        }
        return "the name says $named, the bytes say $found; the bytes win"
    }

    override fun toString(): String = buildString {
        append(payload.label)
        if (compression.isCompressed) append(", ").append(compression.label)
    }
}

object EnvelopeDetector {

    /**
     * Bytes needed for a confident sniff.
     *
     * The longest magic is xz at six bytes, and the raw-stream test needs
     * twelve. Sixteen covers both with room, and costs one small read even on a
     * remote filesystem.
     */
    const val SNIFF_LENGTH = 16

    /**
     * Decide from the leading bytes and the name.
     *
     * @param prefix the first [SNIFF_LENGTH] bytes of the file.
     * @param innerPrefix the first bytes *after* decompression, when the file
     *   was compressed. Without it a compressed file's payload is taken from
     *   the name, because the magic of the container is hidden by the codec.
     */
    fun detect(name: String, prefix: ByteArray, innerPrefix: ByteArray? = null): EnvelopeVerdict {
        val compressionByName = Compression.byName(name)
        val payloadByName = Payload.byName(name)
        val sniffed = Compression.bySniff(prefix)

        // LZMA has no reliable magic, so it is believed only when named.
        val compression = sniffed
            ?: compressionByName?.takeIf { it == Compression.LZMA }
            ?: Compression.NONE

        val payload = when {
            innerPrefix != null -> Payload.bySniff(innerPrefix)
            compression.isCompressed -> payloadByName
            else -> Payload.bySniff(prefix)
        }
        return EnvelopeVerdict(compression, payload, compressionByName, payloadByName)
    }

    private fun ByteArray.startsWith(magic: ByteArray): Boolean {
        if (size < magic.size) return false
        for (i in magic.indices) if (this[i] != magic[i]) return false
        return true
    }
}

private fun ByteArray.startsWith(magic: ByteArray): Boolean {
    if (size < magic.size) return false
    for (i in magic.indices) if (this[i] != magic[i]) return false
    return true
}
