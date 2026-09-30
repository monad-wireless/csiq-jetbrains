package io.monadcount.csiq.format

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The archive's sidecar, read from a real one.
 *
 * The fixture is `metadata.json` taken from
 * `s3://.../datasets/ax210-csi-captures/monad02/monad02_walk-survey-48_20260908-070541-seg0002/`.
 * It is verbatim except for `summary.transmitters.top`: the bystander MACs
 * there are replaced by the RFC 7042 documentation range `00:00:5e:00:53:xx`,
 * in the original order and with the original record counts. The injector's
 * own address and the `deadbeef` receiver fill are kept.
 * It is here because the one fact this file exists to supply - the monitor
 * width - is the fact a `capture.raw` cannot carry, and a reader that gets its
 * shape wrong would silently report a width of "unknown" for every raw stream
 * in the archive.
 */
class SessionSidecarTest {

    private fun sidecar(): SessionSidecar {
        val text = checkNotNull(javaClass.getResourceAsStream("/fixtures/metadata-walk-survey-seg0002.json"))
            .use { it.readBytes() }
            .toString(Charsets.UTF_8)
        return assertNotNull(SessionSidecar.parse(text), "the real sidecar should parse")
    }

    @Test
    fun `the monitor width a raw stream cannot carry is read from the sidecar`() {
        assertEquals(Width.HT20, sidecar().monitorWidth)
    }

    @Test
    fun `identity and lifecycle are read as written`() {
        val s = sidecar()
        assertEquals("csid-session/1", s.schema)
        assertEquals("monad02_walk-survey-48_20260908-070541-seg0002", s.sessionId)
        assertEquals("lib-walk-survey-2026-09-08-01", s.runId)
        assertEquals("walk-survey-48", s.experiment)
        assertEquals("complete", s.status)
        assertEquals("2026-09-08T07:15:45Z", s.startedAt)
        assertEquals("2026-09-08T07:25:45Z", s.endedAt)
        assertEquals("monad02", s.hostname)
    }

    @Test
    fun `the radio block is read`() {
        val s = sidecar()
        assertEquals(48L, s.channel)
        assertEquals("5", s.band)
        assertEquals(5240L, s.controlFreqMhz)
        assertEquals(0L, s.intervalUs)
    }

    @Test
    fun `a segment names itself and its base session`() {
        val s = sidecar()
        assertTrue(s.isSegment)
        assertEquals(2, s.segmentNumber)
        assertEquals("monad02_walk-survey-48_20260908-070541", s.baseSessionId)
    }

    @Test
    fun `the claimed record count is available for cross-checking`() {
        val claimed = assertNotNull(sidecar().summaryRecords)
        assertTrue(claimed > 0, "the summary should carry a record count")
    }

    @Test
    fun `a capture that is not a segment says so`() {
        val s = assertNotNull(
            SessionSidecar.parse("""{"session_id":"monad02_walk-survey-48_20260908-070541"}"""),
        )
        assertTrue(!s.isSegment)
        assertEquals(null, s.segmentNumber)
        assertEquals(null, s.baseSessionId)
    }

    @Test
    fun `an unparseable sidecar is absent rather than fatal`() {
        assertEquals(null, SessionSidecar.parse("{not json"))
    }
}
