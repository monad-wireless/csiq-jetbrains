package io.monadcount.csiq.format

/**
 * One live-stream datagram.
 *
 * ```text
 * [magic "CL" 2B][ver u8 = 1][session_uid u64 LE][seq u32 LE][payload TLV]
 * ```
 *
 * The payload is byte-identical to a file record's payload, so one codec serves
 * both. `seq` gaps are meaningful: they are sender-side drops from a bounded
 * best-effort queue, and counting them is how a consumer knows it is behind.
 */
class LiveDatagram(
    val sessionUid: Long,
    val seq: Int,
    val record: CsiRecord,
) {
    companion object {
        fun decode(buf: ByteArray, keepRawTlvs: Boolean = false): LiveDatagram {
            if (buf.size < Csiq.LIVE_PREAMBLE_LEN) {
                throw CsiqFormatException("live datagram is ${buf.size} bytes, shorter than its preamble")
            }
            if (buf[0] != Csiq.LIVE_MAGIC[0] || buf[1] != Csiq.LIVE_MAGIC[1]) {
                throw CsiqFormatException(
                    "not a live datagram: magic is %02x %02x, expected \"CL\"".fmt(buf[0], buf[1]),
                )
            }
            val version = buf[2].toInt() and 0xFF
            if (version != Csiq.LIVE_VERSION) {
                throw CsiqFormatException(
                    "live datagram declares version $version; this reader implements ${Csiq.LIVE_VERSION}",
                )
            }
            return LiveDatagram(
                sessionUid = le64(buf, 3),
                seq = le32(buf, 11),
                record = TlvCodec.decode(
                    buf.copyOfRange(Csiq.LIVE_PREAMBLE_LEN, buf.size),
                    keepRawTlvs,
                ),
            )
        }
    }
}
