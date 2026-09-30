package io.monadcount.csiq.format

/** A TLV field exactly as it sat in the payload, kept for the byte inspector. */
data class RawTlv(
    /** Offset of the type byte, relative to the start of the record payload. */
    val offsetInPayload: Int,
    val code: Int,
    val length: Int,
    val value: ByteArray,
) {
    val type: TlvType? get() = TlvType.of(code)

    override fun equals(other: Any?): Boolean =
        this === other ||
            (other is RawTlv && offsetInPayload == other.offsetInPayload && code == other.code && value.contentEquals(other.value))

    override fun hashCode(): Int = (offsetInPayload * 31 + code) * 31 + value.contentHashCode()
}

/** Node and host state. A sparse series, never a per-record column. */
data class NodeState(
    /** SoC die temperature, millidegrees Celsius. */
    val tempMilliC: Int? = null,
    /** Raspberry Pi throttle bitmask. Non-zero means the SoC was capped. */
    val throttleFlags: Int? = null,
    /** Bytes free on the capture spool filesystem. */
    val spoolFreeBytes: Long? = null,
    /** One-minute load average times 1000. */
    val loadMilli: Int? = null,
    /**
     * Wi-Fi NIC die temperature, WHOLE degrees Celsius.
     *
     * Not millidegrees, unlike [tempMilliC]. The two are different sensors in
     * different units and neither can be recovered from the other.
     */
    val nicTempC: Int? = null,
) {
    val isEmpty: Boolean
        get() = tempMilliC == null && throttleFlags == null &&
            spoolFreeBytes == null && loadMilli == null && nicTempC == null

    companion object {
        val EMPTY = NodeState()
    }
}

/** The decoded PHY label of a record. */
data class PhyLabel(val modulation: Modulation, val mcs: Int, val nss: Int) {
    override fun toString(): String = "${modulation.label} MCS$mcs NSS$nss"
}

/**
 * One CSI record.
 *
 * Timing rule from the spec: analyse on [ftm], anchor wallclock on [unixTsNs].
 */
class CsiRecord(
    /** 320 MHz baseband timestamp. Wraps every 13.42 s; unwrap before use. */
    val ftm: Int,
    /** Firmware microsecond clock. Wraps every 71.6 min. */
    val us: Int,
    /** Host wallclock at vendor-event delivery. */
    val unixTsNs: Long,
    /** Raw `rate_n_flags` v2 word, verbatim. */
    val rnf: Int,
    val phy: PhyLabel?,
    /** Frame bandwidth and antenna mask. Absent is NOT 20 MHz. */
    val bandwidth: Bandwidth?,
    val antennaSel: Int?,
    /**
     * `CLOCK_MONOTONIC` microseconds.
     *
     * `null` means **this record is the node's own transmission**, looped back.
     * It does not mean the clock was unavailable. Do not read it as zero.
     */
    val monoUs: Long?,
    /** The 272-byte driver header, when the writer kept it. */
    val vendorHdr: ByteArray?,
    val node: NodeState,
    /** The driver's CSI-report counter. A gap is a dropped report. */
    val seq: Int,
    val nrx: Int,
    val ntx: Int,
    val ntone: Int,
    /** Per-chain RSSI in dBm, negative. [Csiq.RSSI_NO_MEASUREMENT] is a sentinel. */
    val rssi: ShortArray,
    val srcMac: ByteArray,
    val channel: Int,
    /** The monitor's configured width. Not the frame's bandwidth. */
    val width: Width,
    /** Interleaved i16 I/Q, chain-major, imaginary first. Stored verbatim. */
    val iq: ShortArray,
    /** Every TLV as it appeared, when the decoder was asked to keep them. */
    val rawTlvs: List<RawTlv>? = null,
    /** Codes the decoder skipped. Non-empty means the writer knew more than this reader. */
    val unknownCodes: IntArray = IntArray(0),
) {
    /** Number of CSI chains: `nrx * ntx`. */
    val chains: Int get() = nrx * ntx

    /** Number of complex coefficients the declared geometry implies. */
    val coeffCount: Int get() = ntone * chains

    /** True when [iq] matches the declared geometry. */
    val geometryConsistent: Boolean get() = coeffCount > 0 && iq.size == 2 * coeffCount

    val srcMacString: String
        get() = srcMac.joinToString(":") { "%02x".fmt(it) }

    /** Which RX chains actually measured. A `false` entry means stale CSI. */
    fun chainsMeasured(): BooleanArray =
        BooleanArray(rssi.size) { rssi[it] != Csiq.RSSI_NO_MEASUREMENT }

    /** True when every reported chain carries a real measurement. */
    fun fullyMeasured(): Boolean =
        rssi.isNotEmpty() && rssi.all { it != Csiq.RSSI_NO_MEASUREMENT }

    /**
     * True when the whole CSI matrix is zero.
     *
     * Around 15 to 16 percent of AX210 records are like this. They are not a
     * parse failure, and they must be excluded from any amplitude statistic.
     */
    fun isAllZero(): Boolean = iq.isEmpty() || iq.all { it.toInt() == 0 }

    /**
     * One chain's frequency response as interleaved `(re, im)` in tone order.
     *
     * Two conversions happen here, and getting either wrong silently corrupts
     * every phase-derived result:
     *
     *  * storage is **chain-major** - `nrx*ntx` contiguous blocks of `ntone`
     *    coefficients, so `index(c, t) = 2 * (c * ntone + t)`;
     *  * each coefficient is **imaginary first, then real**.
     *
     * Reading the pair the other way round yields `i * conj(H)`. Amplitude is
     * untouched, so it looks healthy, while every phase is mirrored and the
     * impulse response becomes anti-causal.
     *
     * Returns `null` when the geometry does not match the payload.
     */
    fun chainComplex(chain: Int): FloatArray? {
        if (chain < 0 || chain >= chains || !geometryConsistent) return null
        val out = FloatArray(2 * ntone)
        val base = 2 * chain * ntone
        for (t in 0 until ntone) {
            val i = base + 2 * t
            out[2 * t] = iq[i + 1].toFloat()     // real
            out[2 * t + 1] = iq[i].toFloat()     // imaginary
        }
        return out
    }

    /**
     * One chain's amplitude per tone.
     *
     * AGC-normalised: this carries the channel's shape only. Any absolute
     * scale must come from [rssi].
     */
    fun chainAmplitude(chain: Int): FloatArray? {
        if (chain < 0 || chain >= chains || !geometryConsistent) return null
        val out = FloatArray(ntone)
        val base = 2 * chain * ntone
        for (t in 0 until ntone) {
            val im = iq[base + 2 * t].toFloat()
            val re = iq[base + 2 * t + 1].toFloat()
            out[t] = kotlin.math.sqrt(re * re + im * im)
        }
        return out
    }

    /** One chain's raw phase per tone, radians in (-pi, pi]. Dominated by CFO. */
    fun chainPhase(chain: Int): FloatArray? {
        if (chain < 0 || chain >= chains || !geometryConsistent) return null
        val out = FloatArray(ntone)
        val base = 2 * chain * ntone
        for (t in 0 until ntone) {
            val im = iq[base + 2 * t].toFloat()
            val re = iq[base + 2 * t + 1].toFloat()
            out[t] = kotlin.math.atan2(im.toDouble(), re.toDouble()).toFloat()
        }
        return out
    }

    /**
     * Tone spacing in kHz, derived from the frame bandwidth and tone count.
     *
     * The occupied span cannot exceed the channel, so 242 tones in 20 MHz is
     * HE20 and 242 tones in 80 MHz is VHT80. `null` when the bandwidth is
     * unknown, because the spec forbids assuming one.
     */
    fun toneSpacingKhz(): Double? {
        val mhz = bandwidth?.mhz ?: return null
        if (ntone <= 0) return null
        return if (ntone * 312.5 <= mhz * 1000.0) 312.5 else 78.125
    }
}
