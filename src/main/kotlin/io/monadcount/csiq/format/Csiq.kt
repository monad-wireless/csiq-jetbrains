package io.monadcount.csiq.format

/**
 * Constants of the CSIQ interchange format, version 1.
 *
 * Transcribed from `docs/CSIQ-format-v1.md` in the `csid` repository, which is
 * authoritative. Where this file and that document disagree, this file is the
 * bug.
 *
 * All multi-byte integers in CSIQ are little-endian. The one exception is the
 * raw driver stream of Appendix A, whose length prefixes are big-endian.
 */
object Csiq {
    /** File magic, four bytes at offset 0. */
    val MAGIC: ByteArray = byteArrayOf(0x43, 0x53, 0x49, 0x51) // "CSIQ"

    /**
     * The only container version defined.
     *
     * The spec makes this an equality test, not a floor: a reader that meets a
     * version it does not implement must refuse the file rather than guess.
     */
    const val FORMAT_VERSION: Int = 1

    /**
     * Record framing tag.
     *
     * Any other byte where a tag is expected means the stream desynchronised.
     * A reader must stop there rather than emit garbage.
     */
    const val RECORD_TAG: Byte = 0xA1.toByte()

    /** File-header flag bit 0: a session block follows the header. */
    const val FLAG_SESSION: Int = 0x0001

    /** Fixed part of the file header: magic, version, flags, session length. */
    const val FILE_HEADER_LEN: Int = 12

    /** Live-datagram magic. */
    val LIVE_MAGIC: ByteArray = byteArrayOf(0x43, 0x4C) // "CL"

    /** Live-datagram version. */
    const val LIVE_VERSION: Int = 1

    /** Live-datagram bytes before the TLV payload: magic, version, uid, seq. */
    const val LIVE_PREAMBLE_LEN: Int = 15

    /** Length of the driver header the raw stream and `VENDOR_HDR` carry. */
    const val VENDOR_HEADER_LEN: Int = 272

    /**
     * RSSI value meaning "this chain reported no measurement".
     *
     * Not a weak signal. The firmware writes the magnitude `0x7F`, Intel's
     * documented not-available marker. -127 dBm sits about 26 dB below the
     * thermal noise floor of a 20 MHz channel, so it cannot be a measurement.
     *
     * Consumer rule: the chain's slice of the CSI matrix is a byte-identical
     * stale copy of an earlier frame. Discard it.
     */
    const val RSSI_NO_MEASUREMENT: Short = -127

    /** The zstd frame magic, for recognising a `.csiq.zst` envelope. */
    val ZSTD_MAGIC: ByteArray = byteArrayOf(0x28, 0xB5.toByte(), 0x2F, 0xFD.toByte())
}

/**
 * A TLV type code, with what its value means.
 *
 * The `spec` text is shown in the record inspector, so a reader of an unfamiliar
 * capture gets the format's own words rather than a bare number.
 */
enum class TlvType(
    val code: Int,
    val label: String,
    val spec: String,
    val required: Boolean = false,
) {
    FTM(0x01, "FTM", "u32 - 320 MHz baseband timestamp, 3.125 ns tick, wraps every 13.42 s", required = true),
    US(0x02, "US", "u32 - firmware microsecond clock, wraps every 71.6 min"),
    UNIX_TS_NS(0x03, "UNIX_TS_NS", "u64 - host wallclock at delivery, nanoseconds since epoch"),
    RNF(0x04, "RNF", "u32 - raw rate_n_flags v2 word, verbatim"),
    PHY(0x05, "PHY", "u8 modulation, u8 mcs, u8 nss"),
    NRX(0x06, "NRX", "u8 - receive chains", required = true),
    NTX(0x07, "NTX", "u8 - transmit spatial streams", required = true),
    NTONE(0x08, "NTONE", "u16 - subcarriers", required = true),
    SRC_MAC(0x09, "SRC_MAC", "6 B - source MAC of the sounded frame"),
    CHANNEL(0x0A, "CHANNEL", "u32 - 802.11 channel number"),
    WIDTH(0x0B, "WIDTH", "u16 - CONFIGURED MONITOR width, a session constant, not the frame's"),
    RSSI(0x0C, "RSSI", "i16[] - one per RX chain, dBm (negative); -127 = no measurement"),
    SEQ(0x0D, "SEQ", "u8 - the driver's own CSI-report counter, +1 per report, wraps at 256"),
    CSI_MATRIX(0x10, "CSI_MATRIX", "i16[] - chain-major, imaginary first then real"),
    BW_ANTSEL(0x11, "BW_ANTSEL", "u8 bandwidth code, u8 antenna mask - the FRAME's bandwidth"),
    MONO_US(0x12, "MONO_US", "u64 - CLOCK_MONOTONIC us; ABSENT means the node's own transmission"),
    VENDOR_HDR(0x14, "VENDOR_HDR", "the 272-byte driver header, verbatim"),
    NODE_TEMP_MC(0x40, "NODE_TEMP_MC", "i32 - host SoC die temperature, MILLIdegrees C"),
    NODE_THROTTLE(0x41, "NODE_THROTTLE", "u32 - firmware throttle bitmask; non-zero means the SoC was capped"),
    NODE_SPOOL_FREE(0x42, "NODE_SPOOL_FREE", "u64 - bytes free on the capture spool"),
    NODE_LOAD_M(0x43, "NODE_LOAD_M", "u32 - 1-minute load average x 1000"),
    NODE_NIC_TEMP_C(0x44, "NODE_NIC_TEMP_C", "i32 - Wi-Fi NIC die temperature, WHOLE degrees C"),
    ;

    companion object {
        private val BY_CODE: Map<Int, TlvType> = entries.associateBy { it.code }

        fun of(code: Int): TlvType? = BY_CODE[code]

        /**
         * What the spec reserves a code for, when no type is defined.
         *
         * A reader must skip an unknown code, but a *viewer* should say what
         * the code was set aside for, so an unexpected field in a capture is
         * legible rather than merely tolerated.
         */
        fun reservedFor(code: Int): String = when (code) {
            0x00 -> "reserved / padding"
            0x13 -> "deliberately NOT allocated - SEQ (0x0D) is already the record counter"
            in 0x15..0x1F -> "reserved: further recoveries from the 272-byte driver header"
            in 0x20..0x2F -> "reserved: 802.11be / EHT (RU allocation, per-RU tone maps)"
            in 0x30..0x3F -> "reserved: 802.11bf sensing metadata"
            in 0x45..0x4F -> "reserved: further node and host state"
            else -> "not defined by CSIQ v1"
        }
    }
}

/** Modulation family, from the `rate_n_flags` v2 modulation-type field. */
enum class Modulation(val code: Int, val label: String) {
    CCK(0, "CCK"),
    LEGACY_OFDM(1, "Legacy OFDM"),
    HT(2, "HT"),
    VHT(3, "VHT"),
    HE(4, "HE"),
    EHT(5, "EHT"),
    UNKNOWN(-1, "unknown"),
    ;

    companion object {
        fun of(code: Int): Modulation = entries.firstOrNull { it.code == code } ?: UNKNOWN
    }
}

/**
 * Width of the **monitor interface**, from TLV `0x0B`.
 *
 * A session constant. It bounds what the receiver could decode and describes no
 * individual record. For a record's own bandwidth use [Bandwidth].
 */
enum class Width(val code: Int, val label: String) {
    NOHT(0, "NOHT"),
    HT20(1, "HT20"),
    HT40_MINUS(2, "HT40-"),
    HT40_PLUS(3, "HT40+"),
    W80(4, "80MHz"),
    W160(5, "160MHz"),
    W320(6, "320MHz"),
    UNKNOWN(-1, "unknown"),
    ;

    companion object {
        fun of(code: Int): Width = entries.firstOrNull { it.code == code } ?: UNKNOWN
    }
}

/**
 * Bandwidth of the **received frame**, from TLV `0x11` or decoded from `RNF`.
 *
 * Codes are the driver's own `RATE_MCS_CHAN_WIDTH_*` values. An unrecognised
 * code is carried verbatim and must not be coerced to 20 MHz.
 */
enum class Bandwidth(val code: Int, val mhz: Int?) {
    W20(0, 20),
    W40(1, 40),
    W80(2, 80),
    W160(3, 160),
    W320(4, 320),
    UNKNOWN(-1, null),
    ;

    val label: String get() = mhz?.let { "${it}MHz" } ?: "unknown"

    companion object {
        fun of(code: Int): Bandwidth = entries.firstOrNull { it.code == code } ?: UNKNOWN
    }
}
