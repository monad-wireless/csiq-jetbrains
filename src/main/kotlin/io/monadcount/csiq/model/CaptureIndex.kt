package io.monadcount.csiq.model

import io.monadcount.csiq.format.Bandwidth
import io.monadcount.csiq.format.CsiRecord
import io.monadcount.csiq.format.Csiq
import io.monadcount.csiq.format.FtmUnwrapper
import io.monadcount.csiq.format.Modulation
import io.monadcount.csiq.format.Width
import io.monadcount.csiq.format.fmt

/**
 * One row per record, held as a structure of arrays.
 *
 * This is what makes a large capture usable. A capture of 800,000 records costs
 * about 50 bytes a row here, so the whole timeline fits in memory while the CSI
 * matrices - which are all of the bytes - stay on disk or in the object store
 * and are re-read only for the records actually on screen.
 */
class CaptureIndex(
    /** Byte offset of each record's framing tag. */
    val offset: LongArray,
    /** Total framed length of each record. */
    val framedLength: IntArray,
    /** Raw 320 MHz baseband clock, as stored. */
    val ftm: IntArray,
    /** [ftm] unwrapped across its 13.42 s wrap, in ticks. */
    val ftmUnwrapped: LongArray,
    /** Host wallclock at delivery. Zero when the record carried none. */
    val unixTsNs: LongArray,
    /** Firmware microsecond clock. */
    val us: IntArray,
    /** Raw rate_n_flags word. */
    val rnf: IntArray,
    /** RSSI of chain 0 and chain 1 in dBm. [Short.MIN_VALUE] means absent. */
    val rssi0: ShortArray,
    val rssi1: ShortArray,
    val ntone: ShortArray,
    val nrx: ByteArray,
    val ntx: ByteArray,
    val seq: ByteArray,
    /** Source MAC packed into the low 48 bits. */
    val srcMac: LongArray,
    val channel: ShortArray,
    /** [Modulation.code], or -1. */
    val modulation: ByteArray,
    /** [Bandwidth.code], or -1 when genuinely absent. */
    val bandwidthCode: ByteArray,
    val mcs: ByteArray,
    val nss: ByteArray,
    /** See the FLAG_ constants. */
    val flags: ByteArray,
    /** The monitor width, a session constant, taken from the first record. */
    val monitorWidth: Width,
) {
    val size: Int get() = offset.size

    companion object {
        /** The CSI matrix was entirely zero. */
        const val FLAG_ALL_ZERO: Int = 1 shl 0

        /** `MONO_US` was present, so the record was genuinely received. */
        const val FLAG_MONO_PRESENT: Int = 1 shl 1

        /** The 272-byte driver header was kept on this record. */
        const val FLAG_VENDOR_HDR: Int = 1 shl 2

        /** A node-state tick was attached to this record. */
        const val FLAG_NODE_STATE: Int = 1 shl 3

        /** The CSI payload length did not match the declared geometry. */
        const val FLAG_GEOMETRY_BAD: Int = 1 shl 4

        /** At least one chain reported the no-measurement sentinel. */
        const val FLAG_STALE_CHAIN: Int = 1 shl 5

        /** RSSI was stored as a positive magnitude, before the sign corrigendum. */
        const val FLAG_RSSI_POSITIVE: Int = 1 shl 6

        /** The record carried a TLV code this reader does not know. */
        const val FLAG_UNKNOWN_TLV: Int = 1 shl 7

        const val RSSI_ABSENT: Short = Short.MIN_VALUE
    }

    fun flag(row: Int, bit: Int): Boolean = (flags[row].toInt() and bit) != 0

    /** Seconds since the first record, on the baseband clock. */
    fun elapsedSeconds(row: Int): Double =
        (ftmUnwrapped[row] - ftmUnwrapped[0]) * FtmUnwrapper.SECONDS_PER_TICK

    fun srcMacString(row: Int): String {
        val v = srcMac[row]
        return (5 downTo 0).joinToString(":") { "%02x".fmt(((v shr (8 * it)) and 0xFF).toInt()) }
    }

    fun modulationOf(row: Int): Modulation =
        modulation[row].toInt().let { if (it < 0) Modulation.UNKNOWN else Modulation.of(it) }

    fun bandwidthOf(row: Int): Bandwidth? =
        bandwidthCode[row].toInt().let { if (it < 0) null else Bandwidth.of(it) }

    /** Row whose offset is nearest to but not after [byteOffset], or -1. */
    fun rowAtOffset(byteOffset: Long): Int {
        var lo = 0
        var hi = size - 1
        var best = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (offset[mid] <= byteOffset) { best = mid; lo = mid + 1 } else hi = mid - 1
        }
        return best
    }

    /** Distinct tone counts and how many records carry each, most common first. */
    fun toneCountHistogram(): List<Pair<Int, Int>> {
        val counts = HashMap<Int, Int>()
        for (i in 0 until size) {
            val t = ntone[i].toInt() and 0xFFFF
            counts[t] = (counts[t] ?: 0) + 1
        }
        return counts.entries.sortedByDescending { it.value }.map { it.key to it.value }
    }
}

/** Accumulates an index while a scan walks the file. */
class CaptureIndexBuilder {
    private val offset = LongBuf()
    private val framedLength = IntBuf()
    private val ftm = IntBuf()
    private val ftmUnwrapped = LongBuf()
    private val unixTsNs = LongBuf()
    private val us = IntBuf()
    private val rnf = IntBuf()
    private val rssi0 = ShortBuf()
    private val rssi1 = ShortBuf()
    private val ntone = ShortBuf()
    private val nrx = ByteBuf()
    private val ntx = ByteBuf()
    private val seq = ByteBuf()
    private val srcMac = LongBuf()
    private val channel = ShortBuf()
    private val modulation = ByteBuf()
    private val bandwidthCode = ByteBuf()
    private val mcs = ByteBuf()
    private val nss = ByteBuf()
    private val flags = ByteBuf()

    private val unwrapper = FtmUnwrapper()
    private var monitorWidth: Width = Width.UNKNOWN
    private var rows = 0

    val count: Int get() = rows

    fun add(recordOffset: Long, framed: Int, r: CsiRecord) {
        if (rows == 0) monitorWidth = r.width
        offset.add(recordOffset)
        framedLength.add(framed)
        ftm.add(r.ftm)
        ftmUnwrapped.add(unwrapper.next(r.ftm))
        unixTsNs.add(r.unixTsNs)
        us.add(r.us)
        rnf.add(r.rnf)
        rssi0.add(if (r.rssi.isNotEmpty()) r.rssi[0] else CaptureIndex.RSSI_ABSENT)
        rssi1.add(if (r.rssi.size > 1) r.rssi[1] else CaptureIndex.RSSI_ABSENT)
        ntone.add(r.ntone.toShort())
        nrx.add(r.nrx.toByte())
        ntx.add(r.ntx.toByte())
        seq.add(r.seq.toByte())
        srcMac.add(packMac(r.srcMac))
        channel.add(r.channel.toShort())
        modulation.add((r.phy?.modulation?.code ?: -1).toByte())
        bandwidthCode.add((r.bandwidth?.code ?: -1).toByte())
        mcs.add((r.phy?.mcs ?: -1).toByte())
        nss.add((r.phy?.nss ?: -1).toByte())

        var f = 0
        if (r.isAllZero()) f = f or CaptureIndex.FLAG_ALL_ZERO
        if (r.monoUs != null) f = f or CaptureIndex.FLAG_MONO_PRESENT
        if (r.vendorHdr != null) f = f or CaptureIndex.FLAG_VENDOR_HDR
        if (!r.node.isEmpty) f = f or CaptureIndex.FLAG_NODE_STATE
        if (!r.geometryConsistent) f = f or CaptureIndex.FLAG_GEOMETRY_BAD
        if (r.rssi.any { it == Csiq.RSSI_NO_MEASUREMENT }) f = f or CaptureIndex.FLAG_STALE_CHAIN
        // A positive RSSI is physically impossible and marks a file written
        // before the 2026-07-22 sign corrigendum.
        if (r.rssi.any { it > 0 }) f = f or CaptureIndex.FLAG_RSSI_POSITIVE
        if (r.unknownCodes.isNotEmpty()) f = f or CaptureIndex.FLAG_UNKNOWN_TLV
        flags.add(f.toByte())

        rows++
    }

    fun build(): CaptureIndex = CaptureIndex(
        offset = offset.compact(),
        framedLength = framedLength.compact(),
        ftm = ftm.compact(),
        ftmUnwrapped = ftmUnwrapped.compact(),
        unixTsNs = unixTsNs.compact(),
        us = us.compact(),
        rnf = rnf.compact(),
        rssi0 = rssi0.compact(),
        rssi1 = rssi1.compact(),
        ntone = ntone.compact(),
        nrx = nrx.compact(),
        ntx = ntx.compact(),
        seq = seq.compact(),
        srcMac = srcMac.compact(),
        channel = channel.compact(),
        modulation = modulation.compact(),
        bandwidthCode = bandwidthCode.compact(),
        mcs = mcs.compact(),
        nss = nss.compact(),
        flags = flags.compact(),
        monitorWidth = monitorWidth,
    )

    private fun packMac(mac: ByteArray): Long {
        var v = 0L
        for (b in mac) v = (v shl 8) or (b.toLong() and 0xFF)
        return v
    }
}
