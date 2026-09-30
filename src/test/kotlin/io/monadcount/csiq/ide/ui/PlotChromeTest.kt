package io.monadcount.csiq.ide.ui

import java.awt.Graphics2D
import java.awt.Rectangle
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The plot furniture, painted without a screen.
 *
 * The validator that checked the palette checks colour, not layout. This paints
 * the shared chrome - axes, ticks, legend chips, tooltip, empty state - into an
 * image so a label collision or a mark drawn outside its area is visible, and
 * so a paint path that throws fails a test rather than an editor tab.
 *
 * The PNGs land in `build/reports/chrome/`.
 */
class PlotChromeTest {

    private val outputDir = File("build/reports/chrome").apply { mkdirs() }

    private fun render(width: Int, height: Int, panel: PlotPanel): BufferedImage {
        panel.setSize(width, height)
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val g = image.createGraphics()
        try {
            panel.paint(g)
        } finally {
            g.dispose()
        }
        return image
    }

    /** True when the image is not a single flat colour. */
    private fun hasContent(image: BufferedImage): Boolean {
        val first = image.getRGB(0, 0)
        for (y in 0 until image.height step 3) {
            for (x in 0 until image.width step 3) {
                if (image.getRGB(x, y) != first) return true
            }
        }
        return false
    }

    @Test
    fun `axes, series, legend chips and a tooltip all paint`() {
        val panel = object : PlotPanel() {
            override fun paintPlot(g: Graphics2D) {
                val area = Rectangle(plotX, plotY, plotWidth, plotHeight - CsiqTheme.gapL * 2)
                drawFrame(
                    g,
                    area,
                    xTicks = ticks(0.0, 242.0, 7) { "%.0f".format(it) },
                    yTicks = ticks(0.0, 900.0, 4) { "%.0f".format(it) },
                    xLabel = "subcarrier",
                    yLabel = "radians",
                    title = "amplitude   |H|, AGC-normalised, so this is shape and not level",
                )
                for (chain in 0 until 3) {
                    g.color = CsiqTheme.seriesColor(chain)
                    g.stroke = if (chain == 2) CsiqTheme.staleStroke() else CsiqTheme.seriesStroke()
                    drawSeries(g, area, 242) { i ->
                        0.5 + 0.35 * sin(i / 14.0 + chain) * sin(i / 61.0 + chain)
                    }
                }
                // Below the tick band, which drawFrame owns. Placing a chip
                // row directly under `area` collides with the tick labels,
                // which is how this test earns its keep.
                var x = area.x
                val y = area.y + area.height + CsiqTheme.gapL + CsiqTheme.gapS
                x += CsiqTheme.paintChip(g, x, y, "chain 0   -42 dBm", CsiqTheme.seriesColor(0)) + CsiqTheme.gapS
                x += CsiqTheme.paintChip(g, x, y, "chain 1   -47 dBm", CsiqTheme.seriesColor(1)) + CsiqTheme.gapS
                x += CsiqTheme.paintChip(
                    g, x, y, "chain 2   no measurement, CSI is stale",
                    CsiqTheme.statusWarning, filled = true,
                ) + CsiqTheme.gapS
                CsiqTheme.paintChip(g, x, y, "early/late 0.48  ANTI-CAUSAL", CsiqTheme.statusCritical, filled = true)

                CsiqTheme.paintTooltip(
                    g, width / 2, height / 3, Rectangle(0, 0, width, height),
                    listOf(
                        "record 12 480   3.221 s",
                        "subcarrier 118 of 242",
                        "|H| 612.4",
                        "rssi -42 dBm   HE",
                        "86:b5:e6:df:89:74",
                    ),
                )
            }
        }
        val image = render(940, 520, panel)
        ImageIO.write(image, "png", File(outputDir, "chrome-plot.png"))
        assertTrue(hasContent(image), "the panel painted nothing")
    }

    @Test
    fun `an empty state names the condition that produced it`() {
        val panel = object : PlotPanel() {
            override fun paintPlot(g: Graphics2D) {
                drawEmptyState(
                    g,
                    "Record 12 480 carries an all-zero CSI matrix",
                    "About one AX210 record in six is like this. It is not a parse failure.",
                )
            }
        }
        val image = render(940, 260, panel)
        ImageIO.write(image, "png", File(outputDir, "chrome-empty.png"))
        assertTrue(hasContent(image), "the empty state painted nothing")
    }

    /**
     * The real waterfall panel, with each time axis in turn.
     *
     * This is the one that would catch a tick label reading `Z`, a date lost
     * from the caption, or a clock axis whose labels do not line up with the
     * records under them.
     */
    @Test
    fun `the waterfall paints every time axis`() {
        val bytes = checkNotNull(javaClass.getResourceAsStream("/fixtures/ambient-24-legacy52.csiq"))
            .use { it.readBytes() }
        val capture = io.monadcount.csiq.model.CaptureLoader.load(
            io.monadcount.csiq.format.ByteArrayByteSource(bytes),
            io.monadcount.csiq.format.Payload.CSIQ,
            object : io.monadcount.csiq.model.LoadProgress {
                override fun onBytes(read: Long, total: Long) = Unit
                override fun onRecords(count: Int) = Unit
                override val isCancelled: Boolean get() = false
            },
        )
        val state = CaptureViewState(capture)
        assertTrue(state.time.hasAbsoluteTime, "the fixture carries host timestamps")

        for (mode in io.monadcount.csiq.model.TimeAxisMode.entries) {
            state.timeMode = mode
            state.zone = java.time.ZoneOffset.UTC
            val panel = WaterfallPanel(state)
            val image = render(940, 420, panel)
            ImageIO.write(image, "png", File(outputDir, "waterfall-${mode.name.lowercase()}.png"))
            assertTrue(hasContent(image), "the waterfall painted nothing in $mode")
        }
    }

    /**
     * A real capture's wall-clock axis.
     *
     * A twelve-hour night exercises the coarse tick pattern and the caption's
     * two-date case, neither of which the 1.3-second fixture can reach. Skipped
     * unless `CSIQ_TEST_CAPTURES` points at a directory of captures.
     */
    @Test
    fun `a long real capture gets an hour-scale wall clock`() {
        val dir = System.getenv("CSIQ_TEST_CAPTURES")?.let(::File)?.takeIf { it.isDirectory } ?: return
        val file = dir.listFiles { f: File -> f.name.endsWith(".csiq") && f.length() > 50_000_000 }
            ?.maxByOrNull { it.length() } ?: return

        val capture = io.monadcount.csiq.model.CaptureLoader.load(
            io.monadcount.csiq.format.FileByteSource(file),
            io.monadcount.csiq.format.Payload.CSIQ,
            object : io.monadcount.csiq.model.LoadProgress {
                override fun onBytes(read: Long, total: Long) = Unit
                override fun onRecords(count: Int) = Unit
                override val isCancelled: Boolean get() = false
            },
        )
        val state = CaptureViewState(capture)
        state.zone = java.time.ZoneOffset.UTC
        assertTrue(state.time.hasAbsoluteTime)

        println(
            "real capture ${file.name}: ${capture.recordCount} records, " +
                "%.1f s, anchor %s, clock disagreement %s s, largest gap %.3f s".format(
                    java.util.Locale.ROOT,
                    capture.health.ftmSpanSeconds,
                    state.time.anchorSource,
                    state.time.clockDivergenceSeconds,
                    state.time.largestGapSeconds,
                ),
        )

        val image = render(1100, 420, WaterfallPanel(state))
        ImageIO.write(image, "png", File(outputDir, "waterfall-real-wallclock.png"))
        assertTrue(hasContent(image))
    }

    @Test
    fun `stat tiles paint with their status rule`() {
        val row = javax.swing.JPanel(java.awt.FlowLayout(java.awt.FlowLayout.LEFT, CsiqTheme.gapS, 0))
        row.isOpaque = true
        row.background = CsiqTheme.surface
        row.add(StatTile("records", "1,128,956", "indexed in 886 ms"))
        row.add(StatTile("duration", "30.1 min", "on the baseband clock"))
        row.add(StatTile("all-zero CSI", "15.8 %", "exclude from any amplitude statistic", CsiqTheme.Status.WARNING))
        row.add(StatTile("stream ended", "truncated", "cut at byte 604366720", CsiqTheme.Status.CRITICAL))
        row.add(StatTile("dropped reports", "0", "no gaps in the report counter", CsiqTheme.Status.GOOD))
        row.setSize(1000, 100)
        row.doLayout()
        for (c in row.components) (c as javax.swing.JComponent).doLayout()

        val image = BufferedImage(1000, 100, BufferedImage.TYPE_INT_RGB)
        val g = image.createGraphics()
        try {
            row.paint(g)
        } finally {
            g.dispose()
        }
        ImageIO.write(image, "png", File(outputDir, "chrome-tiles.png"))
        assertTrue(hasContent(image), "the tiles painted nothing")
    }
}
