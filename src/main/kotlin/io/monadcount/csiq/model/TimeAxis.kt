package io.monadcount.csiq.model

import io.monadcount.csiq.format.FtmUnwrapper
import io.monadcount.csiq.format.SessionSidecar
import java.time.DateTimeException
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import kotlin.math.abs

/** Which clock the time axis shows. */
enum class TimeAxisMode(val label: String, val description: String) {
    /**
     * Seconds since the first record, on the 320 MHz baseband clock.
     *
     * The analysis clock. It is stamped in the RF plane before any host
     * software runs, so it carries no scheduling jitter and no NTP step.
     */
    ELAPSED("elapsed", "seconds since the first record, on the baseband clock"),

    /**
     * Absolute time: an anchor, advanced by the baseband clock.
     *
     * This is the format specification's own rule - analyse on ftm, anchor
     * wallclock on unix_ts_ns - applied to an axis. The anchor fixes where the
     * capture sits in the day; every offset within it comes from the clock an
     * NTP step cannot move.
     */
    WALL_CLOCK("wall clock", "absolute time, anchored once and advanced by the baseband clock"),

    /**
     * The host's own timestamp on each record, exactly as stored.
     *
     * Not the axis to analyse on, and it is here for one reason: it is the
     * field an NTP step corrupts, so plotting it is how a step becomes visible.
     * On a healthy capture it and the wall clock are the same line.
     */
    HOST_CLOCK("host clock", "unix_ts_ns as stored, which an NTP step moves"),
}

/** Where the absolute anchor came from. */
enum class AnchorSource(val label: String) {
    /**
     * The first record's own `unix_ts_ns`.
     *
     * Preferred, because it is measured on the same stream as everything it
     * anchors, rather than written by a different part of the capturer.
     */
    FIRST_RECORD("the first record's unix_ts_ns"),

    /**
     * `started_at` from the `metadata.json` beside the capture.
     *
     * The fallback for a raw driver stream with no host timestamps, or a
     * container whose records carry none. A segment's `started_at` is not
     * always the segment's own start, so this anchor is reported rather than
     * quietly used.
     */
    SIDECAR_STARTED_AT("started_at from metadata.json"),

    /** No absolute time is available. Only an elapsed axis is honest. */
    NONE("none"),
}

/**
 * Turns a record index into times a person can read.
 *
 * Three clocks exist in a capture and they fail in different ways, so this
 * keeps them apart rather than blending them. It also cross-checks the two that
 * can be compared, because the comparison is the only way a reader finds out
 * that a clock moved under a running capture.
 */
class TimeAxis(
    private val index: CaptureIndex,
    sidecar: SessionSidecar?,
) {
    /** Nanoseconds since the epoch at the first record, or null. */
    val anchorEpochNanos: Long?

    val anchorSource: AnchorSource

    init {
        val firstHost = (0 until index.size).firstOrNull { index.unixTsNs[it] != 0L }
        when {
            firstHost != null -> {
                // Anchor on the first record that has a host stamp, stepped
                // back to record zero on the baseband clock so the anchor
                // really is the start.
                val offsetSeconds = elapsedSecondsRaw(firstHost)
                anchorEpochNanos = index.unixTsNs[firstHost] - (offsetSeconds * 1e9).toLong()
                anchorSource = AnchorSource.FIRST_RECORD
            }
            else -> {
                val parsed = sidecar?.startedAt?.let(::parseInstant)
                if (parsed != null) {
                    anchorEpochNanos = parsed.epochSecond * 1_000_000_000L + parsed.nano
                    anchorSource = AnchorSource.SIDECAR_STARTED_AT
                } else {
                    anchorEpochNanos = null
                    anchorSource = AnchorSource.NONE
                }
            }
        }
    }

    val hasAbsoluteTime: Boolean get() = anchorEpochNanos != null

    /** Seconds since the first record, on the baseband clock. */
    fun elapsedSeconds(row: Int): Double = elapsedSecondsRaw(row)

    private fun elapsedSecondsRaw(row: Int): Double {
        if (index.size == 0) return 0.0
        return (index.ftmUnwrapped[row] - index.ftmUnwrapped[0]) * FtmUnwrapper.SECONDS_PER_TICK
    }

    /** Absolute nanoseconds for a record: the anchor plus the baseband offset. */
    fun absoluteNanos(row: Int): Long? {
        val anchor = anchorEpochNanos ?: return null
        return anchor + (elapsedSecondsRaw(row) * 1e9).toLong()
    }

    /** The host's own stamp, or null when the record carried none. */
    fun hostNanos(row: Int): Long? = index.unixTsNs[row].takeIf { it != 0L }

    /** Nanoseconds for a record under the given mode, or null. */
    fun nanosFor(row: Int, mode: TimeAxisMode): Long? = when (mode) {
        TimeAxisMode.ELAPSED -> null
        TimeAxisMode.WALL_CLOCK -> absoluteNanos(row)
        TimeAxisMode.HOST_CLOCK -> hostNanos(row)
    }

    // -- the cross-check ----------------------------------------------------

    /**
     * The largest disagreement between the two clocks, in seconds.
     *
     * Measured on real captures from this fleet: 2 ms over three minutes, and
     * 56 ms over twelve hours, which is about one part per million and is the
     * oscillators simply being different. A jump of seconds is not drift. It is
     * an NTP step, and it moves `unix_ts_ns` under a running capture with no
     * other symptom in the file.
     */
    val clockDivergenceSeconds: Double? by lazy { computeDivergence() }

    /** Where the largest disagreement occurred. */
    var clockDivergenceRow: Int = -1
        private set

    private fun computeDivergence(): Double? {
        if (index.size < 2 || anchorSource != AnchorSource.FIRST_RECORD) return null
        var firstHost = -1
        for (i in 0 until index.size) {
            if (index.unixTsNs[i] != 0L) { firstHost = i; break }
        }
        if (firstHost < 0) return null
        val hostBase = index.unixTsNs[firstHost]
        val ftmBase = elapsedSecondsRaw(firstHost)
        var worst = 0.0
        for (i in firstHost until index.size) {
            val h = index.unixTsNs[i]
            if (h == 0L) continue
            val hostElapsed = (h - hostBase) / 1e9
            val ftmElapsed = elapsedSecondsRaw(i) - ftmBase
            val d = abs(hostElapsed - ftmElapsed)
            if (d > worst) { worst = d; clockDivergenceRow = i }
        }
        return worst
    }

    /**
     * The longest gap between consecutive records, in seconds.
     *
     * The baseband clock wraps every 13.42 s and unwrapping assumes a value
     * lower than its predecessor means exactly one wrap. A gap longer than the
     * wrap period breaks that assumption silently, so it is measured.
     */
    val largestGapSeconds: Double by lazy {
        var worst = 0.0
        for (i in 1 until index.size) {
            val d = elapsedSecondsRaw(i) - elapsedSecondsRaw(i - 1)
            if (d > worst) worst = d
        }
        worst
    }

    /** True when a gap is long enough that a wrap may have been missed. */
    val ftmUnwrapSuspect: Boolean get() = largestGapSeconds >= FTM_WRAP_SECONDS

    companion object {
        /** 2^32 baseband ticks at 320 MHz. */
        const val FTM_WRAP_SECONDS: Double = 4294967296.0 / 320_000_000.0

        /** A divergence beyond this is a step rather than oscillator drift. */
        const val STEP_THRESHOLD_SECONDS: Double = 0.5

        private fun parseInstant(text: String): Instant? = try {
            Instant.parse(text)
        } catch (_: DateTimeParseException) {
            try {
                ZonedDateTime.parse(text).toInstant()
            } catch (_: DateTimeParseException) {
                null
            }
        }
    }
}

/**
 * Formats an axis of absolute times.
 *
 * The pattern follows the span, because a three-second window and a twelve-hour
 * night need different precision, and a tick carrying more digits than the span
 * justifies is noise. The date is never repeated on a tick: it belongs in the
 * axis label, where it is stated once.
 */
class TimeAxisFormatter(private val zone: ZoneId, spanSeconds: Double) {

    private val tickFormat: DateTimeFormatter = when {
        spanSeconds <= 5 -> DateTimeFormatter.ofPattern("HH:mm:ss.SSS")
        spanSeconds <= 600 -> DateTimeFormatter.ofPattern("HH:mm:ss")
        else -> DateTimeFormatter.ofPattern("HH:mm")
    }.withZone(zone)

    private val dateFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(zone)
    private val fullFormat: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS").withZone(zone)

    fun tick(epochNanos: Long): String = tickFormat.format(instant(epochNanos))

    fun full(epochNanos: Long): String = fullFormat.format(instant(epochNanos))

    /**
     * The axis caption: the date or dates the capture covers, and the zone.
     *
     * A capture that crosses midnight names both days, because an axis reading
     * `23:50` to `01:10` with one date on it is ambiguous by exactly the amount
     * that matters.
     */
    fun caption(firstNanos: Long, lastNanos: Long): String {
        val a = dateFormat.format(instant(firstNanos))
        val b = dateFormat.format(instant(lastNanos))
        val day = if (a == b) a else "$a to $b"
        return "$day   $zoneName"
    }

    /**
     * The zone as a reader recognises it.
     *
     * `ZoneOffset.UTC.getId()` is the single letter `Z`, which is correct in an
     * RFC 3339 timestamp and opaque on an axis label.
     */
    val zoneName: String
        get() = when (zone.id) {
            "Z", "UTC", "GMT", "+00:00" -> "UTC"
            else -> zone.id
        }

    private fun instant(epochNanos: Long): Instant = try {
        Instant.ofEpochSecond(
            Math.floorDiv(epochNanos, 1_000_000_000L),
            Math.floorMod(epochNanos, 1_000_000_000L),
        )
    } catch (_: DateTimeException) {
        Instant.EPOCH
    }
}
