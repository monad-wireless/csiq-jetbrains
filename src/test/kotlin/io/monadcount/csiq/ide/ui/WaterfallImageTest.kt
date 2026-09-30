package io.monadcount.csiq.ide.ui

import io.monadcount.csiq.format.ByteArrayByteSource
import io.monadcount.csiq.format.Payload
import io.monadcount.csiq.format.FileByteSource
import io.monadcount.csiq.model.Capture
import io.monadcount.csiq.model.CaptureLoader
import io.monadcount.csiq.model.LoadProgress
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The amplitude-to-pixel mapping, checked without a screen.
 *
 * A picture that is wrong still looks like a picture, so these check the two
 * failures that would not be visible: a gap painted as a value, and an image
 * flattened into one shade by an outlier. Each test also writes the PNG it
 * produced into `build/reports/waterfall/`, so the result can be looked at.
 */
class WaterfallImageTest {

    private val outputDir = File("build/reports/waterfall").apply { mkdirs() }

    private fun loadFixture(): Capture {
        val bytes = checkNotNull(javaClass.getResourceAsStream("/fixtures/ambient-24-legacy52.csiq"))
            .use { it.readBytes() }
        return CaptureLoader.load(ByteArrayByteSource(bytes), Payload.CSIQ, SilentProgress)
    }

    @Test
    fun `the overview image has the layer's dimensions and carries varying data`() {
        val capture = loadFixture()
        val layer = assertNotNull(capture.waterfall.firstOrNull(), "expected at least one waterfall layer")
        val image = WaterfallImage.fromLayer(layer, ColorMap.VIRIDIS, logScale = true)

        assertEquals(layer.columns, image.width, "image width is the layer's column count")
        assertEquals(layer.toneCount, image.height, "image height is the tone count")

        val distinct = HashSet<Int>()
        for (y in 0 until image.height) {
            for (x in 0 until image.width step 7) distinct.add(image.getRGB(x, y))
        }
        // One shade would mean the normaliser collapsed the range, which is how
        // an outlier silently destroys a waterfall.
        assertTrue(distinct.size > 16, "expected a range of shades, found ${distinct.size}")

        ImageIO.write(image, "png", File(outputDir, "fixture-overview.png"))
    }

    @Test
    fun `a column no record landed in stays transparent`() {
        val capture = loadFixture()
        val layer = assertNotNull(capture.waterfall.firstOrNull())
        val image = WaterfallImage.fromLayer(layer, ColorMap.VIRIDIS, logScale = true)

        val gapColumns = (0 until layer.columns).filter { layer.isGap(it) }
        assertTrue(gapColumns.isNotEmpty(), "the 24-record fixture should leave most columns empty")
        for (x in gapColumns.take(64)) {
            for (y in 0 until image.height step 5) {
                assertEquals(
                    0,
                    image.getRGB(x, y) ushr 24,
                    "column $x has no records, so it must be transparent rather than a low amplitude",
                )
            }
        }
    }

    @Test
    fun `the normaliser clips on a percentile rather than the maximum`() {
        // A single spike a thousand times the typical value must not flatten
        // everything else into the bottom of the colour map.
        val typical = FloatArray(1000) { 100f + (it % 20) }
        val withSpike = typical.copyOf(1001).also { it[1000] = 100_000f }

        val a = percentileNormalise(typical)
        val b = percentileNormalise(withSpike)
        assertTrue(
            kotlin.math.abs(a - b) < 0.1,
            "an outlier moved the mid-range value from $a to $b",
        )
    }

    private fun percentileNormalise(values: FloatArray): Float {
        val sorted = values.sorted()
        val lo = sorted[(sorted.size * 0.02).toInt()]
        val hi = sorted[(sorted.size * 0.99).toInt()]
        return WaterfallImage.Normaliser(lo, hi, false).normalise(110f)
    }

    /**
     * Renders a real capture when one is pointed at.
     *
     * Skipped by default: the repository carries a 24-record fixture, not a
     * gigabyte of measurements. Set `CSIQ_TEST_CAPTURES` to a directory of
     * `.csiq` files to exercise this.
     */
    @Test
    fun `renders a real capture when CSIQ_TEST_CAPTURES points at one`() {
        val dir = System.getenv("CSIQ_TEST_CAPTURES")?.let(::File)?.takeIf { it.isDirectory } ?: return
        val file = dir.listFiles { f: File -> f.name.endsWith(".csiq") && f.length() > 500_000 }
            ?.maxByOrNull { it.length() } ?: return

        val capture = CaptureLoader.load(FileByteSource(file), Payload.CSIQ, SilentProgress)
        println(
            "rendered ${file.name}: ${capture.recordCount} records, " +
                "${capture.waterfall.size} layers, indexed in ${capture.indexMillis} ms",
        )
        for (layer in capture.waterfall) {
            val image = WaterfallImage.fromLayer(layer, ColorMap.VIRIDIS, logScale = true)
            ImageIO.write(
                image,
                "png",
                File(outputDir, "real-${layer.toneCount}tone-chain${layer.chain}.png"),
            )
        }
        assertTrue(capture.waterfall.isNotEmpty())
    }

    private object SilentProgress : LoadProgress {
        override fun onBytes(read: Long, total: Long) = Unit
        override fun onRecords(count: Int) = Unit
        override val isCancelled: Boolean get() = false
    }
}
