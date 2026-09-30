package io.monadcount.csiq.format

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Field-by-field parity against the Python reference reader.
 *
 * The CSIQ specification names two reference implementations and states that
 * where they disagree, the document is authoritative and both are bugs. This
 * reader is a third. The only way to keep it honest is to check it against one
 * of the two on real driver bytes, so the fixture is 24 records cut verbatim
 * out of a capture taken on monad02, and the expectation beside it is what
 * the csid reader (`python/csiq` in https://github.com/Sibyx/csid) reports for
 * exactly those bytes.
 */
class CsiqParityTest {

    private fun fixture(name: String): ByteArray =
        checkNotNull(javaClass.getResourceAsStream("/fixtures/$name")) { "missing fixture $name" }
            .use { it.readBytes() }

    private fun fixtureText(name: String): String =
        checkNotNull(javaClass.getResourceAsStream("/fixtures/$name")) { "missing fixture $name" }
            .use { it.readBytes().toString(Charsets.UTF_8) }

    private fun readAll(bytes: ByteArray): Pair<CsiqHeader, List<CsiRecord>> {
        val source = ByteArrayByteSource(bytes)
        val header = CsiqReader.readHeader(source)
        val out = ArrayList<CsiRecord>()
        val end = CsiqReader.scan(source, header.firstRecordOffset) { framed ->
            out.add(TlvCodec.decode(framed.payload, keepRawTlvs = true))
            true
        }
        assertEquals(ScanEnd.Clean, end, "fixture should end on a record boundary")
        return header to out
    }

    @Test
    fun `every field matches the python reference reader`() {
        val (header, records) = readAll(fixture("ambient-24-legacy52.csiq"))
        val expected = Json.parse(fixtureText("ambient-24-legacy52.reference.json"))

        assertEquals(Csiq.FORMAT_VERSION, header.version)
        assertTrue(header.hasSessionBlock)
        assertNotNull(header.session, "session block should parse")

        val expectedRecords = (expected.path("records") as JsonValue.Arr).items
        assertEquals(expectedRecords.size, records.size, "record count")

        expectedRecords.forEachIndexed { i, e ->
            val r = records[i]
            fun num(k: String) = e.path(k).asLong()
            assertEquals(num("ftm"), r.ftm.toLong() and 0xFFFF_FFFFL, "record $i ftm")
            assertEquals(num("us"), r.us.toLong() and 0xFFFF_FFFFL, "record $i us")
            assertEquals(num("unix_ts_ns"), r.unixTsNs, "record $i unix_ts_ns")
            assertEquals(num("rnf"), r.rnf.toLong() and 0xFFFF_FFFFL, "record $i rnf")
            assertEquals(num("seq"), r.seq.toLong(), "record $i seq")
            assertEquals(num("nrx"), r.nrx.toLong(), "record $i nrx")
            assertEquals(num("ntx"), r.ntx.toLong(), "record $i ntx")
            assertEquals(num("ntone"), r.ntone.toLong(), "record $i ntone")
            assertEquals(num("channel"), r.channel.toLong(), "record $i channel")
            assertEquals(e.path("mac").asString(), r.srcMacString, "record $i src_mac")
            assertEquals(e.path("width").asString(), r.width.label, "record $i width")

            val expRssi = (e.path("rssi") as JsonValue.Arr).items.map { it.asLong()!!.toInt() }
            assertEquals(expRssi, r.rssi.map { it.toInt() }, "record $i rssi")

            // Absent MONO_US is a semantic marker, not a missing value: it says
            // the record is the capturing node's own transmission.
            val expMono = e.path("mono_us")
            if (expMono is JsonValue.Null) {
                assertNull(r.monoUs, "record $i mono_us should be absent")
            } else {
                assertEquals(expMono.asLong(), r.monoUs, "record $i mono_us")
            }

            assertEquals(
                e.path("modulation").asString(),
                r.phy?.modulation?.name?.lowercase(),
                "record $i modulation",
            )
            assertEquals(e.path("mcs").asLong()?.toInt(), r.phy?.mcs, "record $i mcs")
            assertEquals(e.path("nss").asLong()?.toInt(), r.phy?.nss, "record $i nss")
            assertEquals(e.path("bw_code").asLong()?.toInt(), r.bandwidth?.code, "record $i bandwidth code")
            assertEquals(e.path("ant").asLong()?.toInt(), r.antennaSel, "record $i antenna_sel")
            assertEquals(e.path("iq_len").asLong()?.toInt(), r.iq.size, "record $i iq length")

            val expIqHead = (e.path("iq_head") as JsonValue.Arr).items.map { it.asLong()!!.toInt() }
            assertEquals(expIqHead, r.iq.take(expIqHead.size).map { it.toInt() }, "record $i iq head")
        }
    }

    /**
     * The layout property that fails silently.
     *
     * Storage is chain-major and each coefficient is imaginary first. Reading
     * the pair the other way round yields `i * conj(H)`, which leaves every
     * amplitude untouched and mirrors every phase, so an amplitude-only check
     * cannot catch it. This compares the complex values themselves against the
     * reference reader's.
     */
    @Test
    fun `chain zero is chain-major and imaginary-first`() {
        val (_, records) = readAll(fixture("ambient-24-legacy52.csiq"))
        val expected = Json.parse(fixtureText("ambient-24-legacy52.reference.json"))
        val expectedRecords = (expected.path("records") as JsonValue.Arr).items

        expectedRecords.forEachIndexed { i, e ->
            val head = e.path("chain0_head") as? JsonValue.Arr ?: return@forEachIndexed
            val chain = assertNotNull(records[i].chainComplex(0), "record $i chain 0")
            head.items.forEachIndexed { t, pair ->
                val p = (pair as JsonValue.Arr).items
                assertEquals(p[0].asDouble()!!, chain[2 * t].toDouble(), 0.0, "record $i tone $t real")
                assertEquals(p[1].asDouble()!!, chain[2 * t + 1].toDouble(), 0.0, "record $i tone $t imaginary")
            }
        }
    }

    /**
     * The causality tell.
     *
     * A real channel concentrates its impulse response at early delays. The
     * format spec measures about 21 times more early energy than late on these
     * captures, and the imaginary/real swap inverts the ratio to about 0.5. A
     * ratio below one on real data means a reader has the order wrong.
     */
    @Test
    fun `impulse response is causal on real records`() {
        val (_, records) = readAll(fixture("ambient-24-legacy52.csiq"))
        val ratios = records.mapNotNull { r ->
            r.chainComplex(0)?.let { Dsp.causalityRatio(Dsp.impulseResponse(it)) }
        }
        assertTrue(ratios.size >= 20, "expected most records to yield a ratio, got ${ratios.size}")
        val median = ratios.sorted()[ratios.size / 2]
        assertTrue(median > 1.0, "median early/late energy ratio is $median, which is anti-causal")
    }

    @Test
    fun `summary counts match the reference reader`() {
        val (_, records) = readAll(fixture("ambient-24-legacy52.csiq"))
        val summary = Json.parse(fixtureText("ambient-24-legacy52.reference.json")).path("summary")

        assertEquals(summary.path("records").asLong()?.toInt(), records.size)
        assertEquals(summary.path("all_zero").asLong()?.toInt(), records.count { it.isAllZero() })
        assertEquals(
            summary.path("stale_chain").asLong()?.toInt(),
            records.count { r -> r.rssi.any { it == Csiq.RSSI_NO_MEASUREMENT } },
        )
        assertEquals(
            summary.path("own_transmission").asLong()?.toInt(),
            records.count { it.monoUs == null },
        )
        assertEquals(summary.path("first_ftm").asLong(), records.first().ftm.toLong() and 0xFFFF_FFFFL)
        assertEquals(summary.path("last_ftm").asLong(), records.last().ftm.toLong() and 0xFFFF_FFFFL)
    }
}
