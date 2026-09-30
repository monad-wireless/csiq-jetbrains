package io.monadcount.csiq.format

/** A record that does not satisfy the format. */
class CsiqFormatException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * `rate_n_flags` version 2 field positions.
 *
 * Transcribed from the driver the fleet runs: `rs.h` in the pinned `iax` tree,
 * section "rate_n_flags bit field version 2". v1 and v2 disagree about almost
 * every field above bit 7, so a v1 constant used here would misparse silently.
 */
private object Rnf {
    const val MOD_TYPE_POS = 8
    const val MOD_TYPE_MSK = 0x7
    const val CHAN_WIDTH_POS = 11
    const val CHAN_WIDTH_MSK = 0x7
    const val ANT_POS = 14
    const val ANT_MSK = 0x3
}

/**
 * Decode `rate_n_flags` into a PHY label. `null` when `rnf == 0`, which is how
 * the header reports "no rate information".
 */
fun decodeRnfPhy(rnf: Int): PhyLabel? {
    if (rnf == 0) return null
    val mcs = rnf and 0x0F
    val nss = ((rnf ushr 4) and 0x03) + 1
    val modType = (rnf ushr Rnf.MOD_TYPE_POS) and Rnf.MOD_TYPE_MSK
    return PhyLabel(Modulation.of(modType), mcs, nss)
}

/**
 * Decode the frame bandwidth and antenna mask from `rate_n_flags`.
 *
 * These bits have been in every record csid ever wrote, because `RNF` is stored
 * verbatim. The spec's reader rule says to perform this decode when `0x11` is
 * absent and report the result as if the writer had emitted it: `BW_ANTSEL`
 * *is* this function applied at write time, so both paths return the same bits.
 */
fun decodeRnfBwAntsel(rnf: Int): Pair<Bandwidth, Int>? {
    if (rnf == 0) return null
    val bw = Bandwidth.of((rnf ushr Rnf.CHAN_WIDTH_POS) and Rnf.CHAN_WIDTH_MSK)
    val ant = (rnf ushr Rnf.ANT_POS) and Rnf.ANT_MSK
    return bw to ant
}

/** Decoder for a record's TLV payload. */
object TlvCodec {

    /**
     * Parse a payload into a record.
     *
     * @param keepRawTlvs retain every field's bytes for the inspector. Off by
     *   default: a waterfall pass decodes hundreds of thousands of records and
     *   the retained bytes would dominate memory.
     */
    fun decode(payload: ByteArray, keepRawTlvs: Boolean = false): CsiRecord {
        var pos = 0
        val end = payload.size

        var ftm: Int? = null
        var us = 0
        var unixTsNs = 0L
        var rnf = 0
        var phy: PhyLabel? = null
        var seq = 0
        var nrx: Int? = null
        var ntx: Int? = null
        var ntone: Int? = null
        var rssi = ShortArray(0)
        var srcMac = ByteArray(6)
        var channel = 0
        var width = Width.UNKNOWN
        var bandwidth: Bandwidth? = null
        var antennaSel: Int? = null
        var monoUs: Long? = null
        var vendorHdr: ByteArray? = null
        var tempMilliC: Int? = null
        var throttle: Int? = null
        var spoolFree: Long? = null
        var loadMilli: Int? = null
        var nicTempC: Int? = null
        var iq = ShortArray(0)

        val raws = if (keepRawTlvs) ArrayList<RawTlv>(24) else null
        val unknown = ArrayList<Int>(0)

        while (pos < end) {
            if (end - pos < 5) throw CsiqFormatException("truncated TLV header at offset $pos")
            val fieldStart = pos
            val code = payload[pos].toInt() and 0xFF
            val len = le32(payload, pos + 1)
            pos += 5
            if (len < 0 || len > end - pos) {
                throw CsiqFormatException("TLV 0x%02x declares $len bytes, %d remain".fmt(code, end - pos))
            }
            if (raws != null) {
                raws.add(RawTlv(fieldStart, code, len, payload.copyOfRange(pos, pos + len)))
            }
            when (code) {
                TlvType.FTM.code -> ftm = requireLen(code, len, 4) { le32(payload, pos) }
                TlvType.US.code -> us = requireLen(code, len, 4) { le32(payload, pos) }
                TlvType.UNIX_TS_NS.code -> unixTsNs = requireLen(code, len, 8) { le64(payload, pos) }
                TlvType.RNF.code -> rnf = requireLen(code, len, 4) { le32(payload, pos) }
                TlvType.PHY.code -> {
                    if (len != 3) throw CsiqFormatException("PHY length $len, expected 3")
                    phy = PhyLabel(
                        Modulation.of(payload[pos].toInt() and 0xFF),
                        payload[pos + 1].toInt() and 0xFF,
                        payload[pos + 2].toInt() and 0xFF,
                    )
                }
                TlvType.NRX.code -> nrx = requireLen(code, len, 1) { payload[pos].toInt() and 0xFF }
                TlvType.NTX.code -> ntx = requireLen(code, len, 1) { payload[pos].toInt() and 0xFF }
                TlvType.NTONE.code -> ntone = requireLen(code, len, 2) { le16(payload, pos) }
                TlvType.SEQ.code -> seq = requireLen(code, len, 1) { payload[pos].toInt() and 0xFF }
                TlvType.SRC_MAC.code -> {
                    if (len != 6) throw CsiqFormatException("SRC_MAC length $len, expected 6")
                    srcMac = payload.copyOfRange(pos, pos + 6)
                }
                TlvType.CHANNEL.code -> channel = requireLen(code, len, 4) { le32(payload, pos) }
                TlvType.WIDTH.code -> width = requireLen(code, len, 2) { Width.of(le16(payload, pos)) }
                TlvType.RSSI.code -> rssi = i16Array(payload, pos, len)
                TlvType.CSI_MATRIX.code -> iq = i16Array(payload, pos, len)
                TlvType.BW_ANTSEL.code -> {
                    if (len < 2) throw CsiqFormatException("BW_ANTSEL length $len, expected 2")
                    bandwidth = Bandwidth.of(payload[pos].toInt() and 0xFF)
                    antennaSel = payload[pos + 1].toInt() and 0xFF
                }
                TlvType.MONO_US.code -> monoUs = requireLen(code, len, 8) { le64(payload, pos) }
                TlvType.VENDOR_HDR.code -> vendorHdr = payload.copyOfRange(pos, pos + len)
                TlvType.NODE_TEMP_MC.code -> tempMilliC = requireLen(code, len, 4) { le32(payload, pos) }
                TlvType.NODE_THROTTLE.code -> throttle = requireLen(code, len, 4) { le32(payload, pos) }
                TlvType.NODE_SPOOL_FREE.code -> spoolFree = requireLen(code, len, 8) { le64(payload, pos) }
                TlvType.NODE_LOAD_M.code -> loadMilli = requireLen(code, len, 4) { le32(payload, pos) }
                TlvType.NODE_NIC_TEMP_C.code -> nicTempC = requireLen(code, len, 4) { le32(payload, pos) }
                else -> unknown.add(code) // forward compatibility: skip, but record that we did
            }
            pos += len
        }

        // The spec's reader rule: recover 0x11 from RNF when the writer did not
        // emit it. When neither is present the field is genuinely absent and
        // must be reported as such - absent is not 20 MHz.
        if (bandwidth == null) {
            decodeRnfBwAntsel(rnf)?.let { (bw, ant) -> bandwidth = bw; antennaSel = ant }
        }
        if (phy == null) phy = decodeRnfPhy(rnf)

        return CsiRecord(
            ftm = ftm ?: throw CsiqFormatException("record is missing the required FTM field"),
            us = us,
            unixTsNs = unixTsNs,
            rnf = rnf,
            phy = phy,
            bandwidth = bandwidth,
            antennaSel = antennaSel,
            monoUs = monoUs,
            vendorHdr = vendorHdr,
            node = NodeState(tempMilliC, throttle, spoolFree, loadMilli, nicTempC),
            seq = seq,
            nrx = nrx ?: throw CsiqFormatException("record is missing the required NRX field"),
            ntx = ntx ?: throw CsiqFormatException("record is missing the required NTX field"),
            ntone = ntone ?: throw CsiqFormatException("record is missing the required NTONE field"),
            rssi = rssi,
            srcMac = srcMac,
            channel = channel,
            width = width,
            iq = iq,
            rawTlvs = raws,
            unknownCodes = unknown.toIntArray(),
        )
    }

    private inline fun <T> requireLen(code: Int, actual: Int, expected: Int, read: () -> T): T {
        if (actual != expected) {
            throw CsiqFormatException("TLV 0x%02x length %d, expected %d".fmt(code, actual, expected))
        }
        return read()
    }
}

internal fun le16(b: ByteArray, o: Int): Int =
    (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)

internal fun le32(b: ByteArray, o: Int): Int =
    (b[o].toInt() and 0xFF) or
        ((b[o + 1].toInt() and 0xFF) shl 8) or
        ((b[o + 2].toInt() and 0xFF) shl 16) or
        ((b[o + 3].toInt() and 0xFF) shl 24)

internal fun le64(b: ByteArray, o: Int): Long {
    var v = 0L
    for (i in 7 downTo 0) v = (v shl 8) or (b[o + i].toLong() and 0xFF)
    return v
}

internal fun be32(b: ByteArray, o: Int): Int =
    ((b[o].toInt() and 0xFF) shl 24) or
        ((b[o + 1].toInt() and 0xFF) shl 16) or
        ((b[o + 2].toInt() and 0xFF) shl 8) or
        (b[o + 3].toInt() and 0xFF)

private fun i16Array(b: ByteArray, o: Int, len: Int): ShortArray {
    if (len % 2 != 0) throw CsiqFormatException("odd byte count $len for an i16 array")
    val out = ShortArray(len / 2)
    var p = o
    for (i in out.indices) {
        out[i] = ((b[p].toInt() and 0xFF) or (b[p + 1].toInt() shl 8)).toShort()
        p += 2
    }
    return out
}
