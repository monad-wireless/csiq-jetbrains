package io.monadcount.csiq.format

import java.io.EOFException

/**
 * Reader for the raw iax vendor-event stream: the driver's bytes verbatim, as
 * `csid` writes them to `capture.raw`.
 *
 * ```text
 * [be32 msg_len][be32 hdr_len][hdr (hdr_len B)][be32 csi_len][csi]
 * ```
 *
 * Note the big-endian length prefixes. Everything inside the header is
 * little-endian.
 *
 * This layer is driver-coupled: a firmware or driver revision can invalidate
 * the offsets below. That is the whole reason CSIQ exists. It is supported here
 * because `capture.raw` is the lossless source of truth beside every container,
 * and the question "does the container match the raw stream" is one a person
 * inspecting an archive needs to be able to ask.
 */
object RawStream {

    /** Little-endian field offsets inside the 272-byte driver header. */
    object Offsets {
        const val FTM = 8          // u32, 320 MHz baseband clock
        const val NRX = 46         // u8
        const val NTX = 47         // u8
        const val NTONE = 52       // u16, low half of the vendor struct's u32
        const val RSSI_A = 60      // u8 magnitude; 61..63 reserved
        const val RSSI_B = 64      // u8 magnitude; 65..67 reserved
        const val SRC_MAC = 68     // 6 B
        const val SEQ = 76         // u8
        const val US = 88          // u32
        const val RNF = 92         // u32
        const val MONO_US = 200    // u64, CLOCK_MONOTONIC microseconds
        const val UNIX_TS_NS = 208 // u64
        const val CHANNEL = 216    // u8; 217..219 reserved
    }

    /**
     * Per-chain u16 fields correlated with RSSI, found by the IP-130 header
     * survey over 653k headers. Their semantics were not established, so they
     * are deliberately unnamed and shown as what they are.
     */
    val UNNAMED_U16_OFFSETS = intArrayOf(240, 244, 248, 252)

    /**
     * Convert the header's RSSI byte to dBm.
     *
     * The driver carries a positive magnitude. Both vendor reference readers
     * print the negation, so the sign convention is applied once here and never
     * again downstream.
     */
    fun rssiDbm(raw: Int): Short = (-(raw and 0xFF)).toShort()

    /**
     * Parse one driver header plus its CSI payload.
     *
     * @param width the monitor width the session used. The raw header does not
     *   carry it, so a standalone `capture.raw` has none and gets
     *   [Width.UNKNOWN] rather than a guess.
     */
    fun parseRecord(
        hdr: ByteArray,
        csi: ByteArray,
        width: Width = Width.UNKNOWN,
        keepVendorHdr: Boolean = true,
    ): CsiRecord {
        if (hdr.size < Offsets.RNF + 4) {
            throw CsiqFormatException("driver header is ${hdr.size} bytes, shorter than its base fields")
        }
        val nrx = hdr[Offsets.NRX].toInt() and 0xFF
        val ntx = hdr[Offsets.NTX].toInt() and 0xFF
        val ntone = le16(hdr, Offsets.NTONE)
        val rnf = le32(hdr, Offsets.RNF)

        val rssiAll = shortArrayOf(
            rssiDbm(hdr[Offsets.RSSI_A].toInt()),
            rssiDbm(hdr[Offsets.RSSI_B].toInt()),
        )
        val rssi = rssiAll.copyOf(minOf(nrx, 2).coerceAtLeast(0))

        // The flq extension fields exist only on a long-enough header.
        val hasExt = hdr.size > Offsets.CHANNEL
        val unixTsNs = if (hasExt) le64(hdr, Offsets.UNIX_TS_NS) else 0L
        val channel = if (hasExt) hdr[Offsets.CHANNEL].toInt() and 0xFF else 0

        // Zero means "the node's own transmission", not "unavailable".
        val monoUs = if (hdr.size >= Offsets.MONO_US + 8) {
            le64(hdr, Offsets.MONO_US).takeIf { it != 0L }
        } else {
            null
        }

        if (csi.size % 2 != 0) throw CsiqFormatException("odd CSI byte length ${csi.size}")
        val iq = ShortArray(csi.size / 2)
        var p = 0
        for (i in iq.indices) {
            iq[i] = ((csi[p].toInt() and 0xFF) or (csi[p + 1].toInt() shl 8)).toShort()
            p += 2
        }

        val bwAnt = decodeRnfBwAntsel(rnf)
        return CsiRecord(
            ftm = le32(hdr, Offsets.FTM),
            us = le32(hdr, Offsets.US),
            unixTsNs = unixTsNs,
            rnf = rnf,
            phy = decodeRnfPhy(rnf),
            bandwidth = bwAnt?.first,
            antennaSel = bwAnt?.second,
            monoUs = monoUs,
            vendorHdr = if (keepVendorHdr) hdr else null,
            node = NodeState.EMPTY,
            seq = hdr[Offsets.SEQ].toInt() and 0xFF,
            nrx = nrx,
            ntx = ntx,
            ntone = ntone,
            rssi = rssi,
            srcMac = hdr.copyOfRange(Offsets.SRC_MAC, Offsets.SRC_MAC + 6),
            channel = channel,
            width = width,
            iq = iq,
        )
    }

    /** One framed message in a raw stream. */
    class RawMessage(val offset: Long, val hdr: ByteArray, val csi: ByteArray)

    /**
     * Walk a raw stream from [startOffset]. Same contract as
     * [CsiqReader.scan]: sequential, one pass, stop by returning false.
     */
    fun scan(
        source: ByteSource,
        startOffset: Long = 0L,
        onProgress: ((Long) -> Unit)? = null,
        onMessage: (RawMessage) -> Boolean,
    ): ScanEnd {
        source.open(startOffset).use { input ->
            var offset = startOffset
            val lenBuf = ByteArray(4)
            while (true) {
                var got = 0
                while (got < 4) {
                    val r = input.read(lenBuf, got, 4 - got)
                    if (r < 0) break
                    got += r
                }
                if (got == 0) return ScanEnd.Clean
                if (got < 4) return ScanEnd.Truncated(offset, "message length cut after $got of 4 bytes")
                val msgLen = be32(lenBuf, 0)
                if (msgLen < 8) return ScanEnd.BadRecord(offset, "message declares $msgLen bytes")
                val body = try {
                    input.readFully(msgLen)
                } catch (e: EOFException) {
                    return ScanEnd.Truncated(offset, "message declares $msgLen bytes, ${e.message}")
                }
                val hdrLen = be32(body, 0)
                if (hdrLen < 0 || 4 + hdrLen + 4 > body.size) {
                    return ScanEnd.BadRecord(offset, "header declares $hdrLen bytes inside a $msgLen byte message")
                }
                val csiLen = be32(body, 4 + hdrLen)
                val csiStart = 4 + hdrLen + 4
                if (csiLen < 0 || csiStart + csiLen > body.size) {
                    return ScanEnd.BadRecord(offset, "CSI declares $csiLen bytes, ${body.size - csiStart} remain")
                }
                val msg = RawMessage(
                    offset,
                    body.copyOfRange(4, 4 + hdrLen),
                    body.copyOfRange(csiStart, csiStart + csiLen),
                )
                if (!onMessage(msg)) return ScanEnd.Clean
                offset += 4L + msgLen
                onProgress?.invoke(offset)
            }
        }
    }

    /**
     * True when the first bytes look like a raw stream rather than a container.
     *
     * The test is structural, not a magic number: a raw stream has no magic. A
     * first message whose declared header length is the 272 bytes the driver
     * writes, inside a plausible message length, is the signature.
     */
    fun looksLikeRawStream(prefix: ByteArray): Boolean {
        if (prefix.size < 12) return false
        val msgLen = be32(prefix, 0)
        val hdrLen = be32(prefix, 4)
        return msgLen in 12..(1 shl 24) && hdrLen == Csiq.VENDOR_HEADER_LEN
    }
}
