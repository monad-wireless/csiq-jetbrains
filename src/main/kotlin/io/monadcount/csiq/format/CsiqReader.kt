package io.monadcount.csiq.format

import java.io.EOFException
import java.io.InputStream

/** The file header, and where the first record starts. */
class CsiqHeader(
    val version: Int,
    val flags: Int,
    val sessionLength: Int,
    /** The session block verbatim, when one is present. */
    val sessionJsonText: String?,
    /** The session block parsed, or null when it is absent or unparseable. */
    val session: JsonValue?,
    /** Why the session block did not parse, when it did not. */
    val sessionParseError: String?,
    val firstRecordOffset: Long,
) {
    val hasSessionBlock: Boolean get() = flags and Csiq.FLAG_SESSION != 0
}

/** How a record scan ended. */
sealed interface ScanEnd {
    /** The stream ended on a record boundary. This is a complete file. */
    data object Clean : ScanEnd

    /**
     * The stream ended mid-record.
     *
     * A capture killed with SIGKILL leaves this. The records before the cut are
     * good, so a viewer reports the cut rather than refusing the file.
     */
    data class Truncated(val atOffset: Long, val detail: String) : ScanEnd

    /**
     * A byte other than the record tag appeared where a tag was expected.
     *
     * The format calls this desynchronisation, and a reader must stop: past
     * this point the bytes cannot be trusted to mean anything.
     */
    data class Desync(val atOffset: Long, val foundTag: Int) : ScanEnd

    /** A record's TLV payload did not satisfy the format. */
    data class BadRecord(val atOffset: Long, val detail: String) : ScanEnd
}

/** One framed record: where it starts and the payload bytes inside it. */
class FramedRecord(val offset: Long, val payload: ByteArray) {
    /** Total on-disk size: tag byte, length, payload. */
    val framedLength: Int get() = 5 + payload.size
}

object CsiqReader {

    /** Read and validate the file header. */
    fun readHeader(source: ByteSource): CsiqHeader = source.open(0).use { readHeader(it) }

    fun readHeader(input: InputStream): CsiqHeader {
        val fixed = try {
            input.readFully(Csiq.FILE_HEADER_LEN)
        } catch (e: EOFException) {
            throw CsiqFormatException("file is shorter than a CSIQ header", e)
        }
        if (!(fixed[0] == Csiq.MAGIC[0] && fixed[1] == Csiq.MAGIC[1] &&
                fixed[2] == Csiq.MAGIC[2] && fixed[3] == Csiq.MAGIC[3])
        ) {
            throw CsiqFormatException(
                "not a CSIQ container: magic is %02x %02x %02x %02x, expected \"CSIQ\""
                    .format(fixed[0], fixed[1], fixed[2], fixed[3]),
            )
        }
        val version = le16(fixed, 4)
        if (version != Csiq.FORMAT_VERSION) {
            // The spec makes version an equality test, not a floor. Refusing is
            // correct; saying which version was found is what makes it useful.
            throw CsiqFormatException(
                "container declares version $version; this reader implements version ${Csiq.FORMAT_VERSION}",
            )
        }
        val flags = le16(fixed, 6)
        val sessionLen = le32(fixed, 8)
        if (sessionLen < 0) throw CsiqFormatException("session block length $sessionLen is not representable")

        var text: String? = null
        var parsed: JsonValue? = null
        var parseError: String? = null
        if (sessionLen > 0) {
            val body = input.readFully(sessionLen)
            if (flags and Csiq.FLAG_SESSION != 0) {
                text = String(body, Charsets.UTF_8)
                try {
                    parsed = Json.parse(text)
                } catch (e: JsonParseException) {
                    parseError = e.message
                }
            }
        }
        return CsiqHeader(
            version = version,
            flags = flags,
            sessionLength = sessionLen,
            sessionJsonText = text,
            session = parsed,
            sessionParseError = parseError,
            firstRecordOffset = (Csiq.FILE_HEADER_LEN + sessionLen).toLong(),
        )
    }

    /**
     * Walk records from [startOffset], handing each to [onRecord].
     *
     * Sequential by construction, so it costs one pass over a remote object
     * rather than one request per record. Return false from [onRecord] to stop
     * early. [onProgress] is called with the current absolute offset.
     */
    fun scan(
        source: ByteSource,
        startOffset: Long,
        onProgress: ((Long) -> Unit)? = null,
        onRecord: (FramedRecord) -> Boolean,
    ): ScanEnd {
        source.open(startOffset).use { input ->
            var offset = startOffset
            val head = ByteArray(5)
            while (true) {
                // Read the tag and length as one 5-byte unit: a partial read
                // here is a truncated tail, not a desync.
                var got = 0
                while (got < 5) {
                    val r = input.read(head, got, 5 - got)
                    if (r < 0) break
                    got += r
                }
                if (got == 0) return ScanEnd.Clean
                if (got < 5) {
                    return ScanEnd.Truncated(offset, "record framing cut after $got of 5 bytes")
                }
                if (head[0] != Csiq.RECORD_TAG) {
                    return ScanEnd.Desync(offset, head[0].toInt() and 0xFF)
                }
                val len = le32(head, 1)
                if (len < 0) {
                    return ScanEnd.BadRecord(offset, "record declares $len bytes")
                }
                val payload = try {
                    input.readFully(len)
                } catch (e: EOFException) {
                    return ScanEnd.Truncated(offset, "record declares $len bytes, ${e.message}")
                }
                if (!onRecord(FramedRecord(offset, payload))) return ScanEnd.Clean
                offset += 5L + len
                onProgress?.invoke(offset)
            }
        }
    }

    /**
     * Read one record whose framing starts at [offset].
     *
     * Cheap on a local file. On a sequential source this reopens the stream, so
     * a caller that wants many records should use [scan] instead.
     */
    fun readRecordAt(source: ByteSource, offset: Long, keepRawTlvs: Boolean = false): CsiRecord =
        source.open(offset).use { input ->
            val head = input.readFully(5)
            if (head[0] != Csiq.RECORD_TAG) {
                throw CsiqFormatException(
                    "no record tag at offset $offset: found 0x%02x".fmt(head[0]),
                )
            }
            TlvCodec.decode(input.readFully(le32(head, 1)), keepRawTlvs)
        }
}
