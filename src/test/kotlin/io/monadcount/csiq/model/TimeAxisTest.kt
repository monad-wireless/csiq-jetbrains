package io.monadcount.csiq.model

import io.monadcount.csiq.format.ByteArrayByteSource
import io.monadcount.csiq.format.Payload
import io.monadcount.csiq.format.SessionSidecar
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The time axis, against the format's rule and against real captures.
 *
 * The rule is: analyse on the baseband clock, anchor wallclock on the host's
 * stamp. An axis that took its offsets from `unix_ts_ns` would look right and
 * would move under an NTP step, on a fleet with no RTC where nodes boot in the
 * past and get stepped mid-capture.
 */
class TimeAxisTest {

    private fun fixtureCapture(): Capture {
        val bytes = checkNotNull(javaClass.getResourceAsStream("/fixtures/ambient-24-legacy52.csiq"))
            .use { it.readBytes() }
        return CaptureLoader.load(ByteArrayByteSource(bytes), Payload.CSIQ, SilentProgress)
    }

    private fun sidecar(): SessionSidecar =
        assertNotNull(
            SessionSidecar.parse(
                checkNotNull(javaClass.getResourceAsStream("/fixtures/metadata-walk-survey-seg0002.json"))
                    .use { it.readBytes() }.toString(Charsets.UTF_8),
            ),
        )

    @Test
    fun `the anchor is the first record's own host stamp`() {
        val capture = fixtureCapture()
        val axis = TimeAxis(capture.index, null)

        assertEquals(AnchorSource.FIRST_RECORD, axis.anchorSource)
        assertTrue(axis.hasAbsoluteTime)
        // The fixture's first record carries this exact stamp, and record zero
        // is the anchor, so the two must agree to the nanosecond.
        assertEquals(1785161577060934856L, capture.index.unixTsNs[0])
        assertEquals(1785161577060934856L, axis.anchorEpochNanos)
        assertEquals(axis.anchorEpochNanos, axis.absoluteNanos(0))
    }

    @Test
    fun `absolute time advances on the baseband clock, not the host clock`() {
        val capture = fixtureCapture()
        val axis = TimeAxis(capture.index, null)
        val last = capture.recordCount - 1

        val absolute = assertNotNull(axis.absoluteNanos(last))
        val fromFtm = axis.anchorEpochNanos!! + (axis.elapsedSeconds(last) * 1e9).toLong()
        assertEquals(fromFtm, absolute, "the axis must advance on ftm")

        // And it must stay close to the host's own stamp, because the two
        // clocks only drift; a large gap here would mean the anchoring is wrong
        // rather than that a clock moved.
        val host = assertNotNull(axis.hostNanos(last))
        val differenceSeconds = abs(host - absolute) / 1e9
        assertTrue(differenceSeconds < 0.05, "clocks differ by $differenceSeconds s over the fixture")
    }

    @Test
    fun `a capture with no host stamps falls back to the sidecar`() {
        val capture = fixtureCapture()
        // Blank every host stamp, which is what a raw driver stream with a
        // short header looks like.
        val index = capture.index
        val blanked = CaptureIndex(
            offset = index.offset, framedLength = index.framedLength, ftm = index.ftm,
            ftmUnwrapped = index.ftmUnwrapped, unixTsNs = LongArray(index.size), us = index.us,
            rnf = index.rnf, rssi0 = index.rssi0, rssi1 = index.rssi1, ntone = index.ntone,
            nrx = index.nrx, ntx = index.ntx, seq = index.seq, srcMac = index.srcMac,
            channel = index.channel, modulation = index.modulation,
            bandwidthCode = index.bandwidthCode, mcs = index.mcs, nss = index.nss,
            flags = index.flags, monitorWidth = index.monitorWidth,
        )

        val axis = TimeAxis(blanked, sidecar())
        assertEquals(AnchorSource.SIDECAR_STARTED_AT, axis.anchorSource)
        assertEquals(
            Instant.parse("2026-09-08T07:15:45Z").epochSecond * 1_000_000_000L,
            axis.anchorEpochNanos,
        )
        // With no host stamps there is nothing to cross-check against.
        assertNull(axis.clockDivergenceSeconds)
    }

    @Test
    fun `with neither a host stamp nor a sidecar there is no absolute time`() {
        val capture = fixtureCapture()
        val index = capture.index
        val blanked = CaptureIndex(
            offset = index.offset, framedLength = index.framedLength, ftm = index.ftm,
            ftmUnwrapped = index.ftmUnwrapped, unixTsNs = LongArray(index.size), us = index.us,
            rnf = index.rnf, rssi0 = index.rssi0, rssi1 = index.rssi1, ntone = index.ntone,
            nrx = index.nrx, ntx = index.ntx, seq = index.seq, srcMac = index.srcMac,
            channel = index.channel, modulation = index.modulation,
            bandwidthCode = index.bandwidthCode, mcs = index.mcs, nss = index.nss,
            flags = index.flags, monitorWidth = index.monitorWidth,
        )
        val axis = TimeAxis(blanked, null)
        assertEquals(AnchorSource.NONE, axis.anchorSource)
        assertTrue(!axis.hasAbsoluteTime)
        assertNull(axis.absoluteNanos(0))
        assertNull(axis.nanosFor(0, TimeAxisMode.WALL_CLOCK))
    }

    @Test
    fun `the record gap is measured against the baseband clock's wrap`() {
        val axis = TimeAxis(fixtureCapture().index, null)
        assertTrue(
            axis.largestGapSeconds < TimeAxis.FTM_WRAP_SECONDS,
            "the fixture's gaps are ${axis.largestGapSeconds} s",
        )
        assertTrue(!axis.ftmUnwrapSuspect)
        assertEquals(13.4217728, TimeAxis.FTM_WRAP_SECONDS, 1e-6)
    }

    @Test
    fun `the tick pattern follows the span and the caption names the zone`() {
        val anchor = Instant.parse("2026-09-08T07:15:45.123Z").let {
            it.epochSecond * 1_000_000_000L + it.nano
        }
        val utc = ZoneOffset.UTC

        assertEquals("07:15:45.123", TimeAxisFormatter(utc, 3.0).tick(anchor))
        assertEquals("07:15:45", TimeAxisFormatter(utc, 120.0).tick(anchor))
        assertEquals("07:15", TimeAxisFormatter(utc, 3600.0).tick(anchor))

        assertEquals(
            "2026-09-08 07:15:45.123",
            TimeAxisFormatter(utc, 3600.0).full(anchor),
        )
        assertEquals(
            "2026-09-08   UTC",
            TimeAxisFormatter(utc, 3600.0).caption(anchor, anchor),
        )
    }

    @Test
    fun `a capture that crosses midnight names both days`() {
        val utc = ZoneOffset.UTC
        val start = Instant.parse("2026-08-11T23:50:00Z").epochSecond * 1_000_000_000L
        val end = Instant.parse("2026-08-12T01:10:00Z").epochSecond * 1_000_000_000L
        assertEquals(
            "2026-08-11 to 2026-08-12   UTC",
            TimeAxisFormatter(utc, 4800.0).caption(start, end),
        )
    }

    @Test
    fun `a zone shifts the printed time without moving the instant`() {
        val anchor = Instant.parse("2026-09-08T07:15:45Z").epochSecond * 1_000_000_000L
        val utc = TimeAxisFormatter(ZoneOffset.UTC, 120.0).tick(anchor)
        val bratislava = TimeAxisFormatter(ZoneId.of("Europe/Bratislava"), 120.0).tick(anchor)
        assertEquals("07:15:45", utc)
        assertEquals("09:15:45", bratislava, "Bratislava is UTC+2 in September")
    }

    private object SilentProgress : LoadProgress {
        override fun onBytes(read: Long, total: Long) = Unit
        override fun onRecords(count: Int) = Unit
        override val isCancelled: Boolean get() = false
    }
}
