package io.monadcount.csiq.ide.ui

import com.intellij.openapi.application.ApplicationManager
import com.intellij.util.ui.JBUI
import io.monadcount.csiq.format.fmt
import io.monadcount.csiq.model.TimeAxisMode
import io.monadcount.csiq.model.WaterfallLayer
import io.monadcount.csiq.model.WaterfallWindow
import java.awt.AlphaComposite
import java.awt.Color
import java.awt.Graphics2D
import java.awt.Point
import java.awt.Rectangle
import java.awt.TexturePaint
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.MouseWheelEvent
import java.awt.image.BufferedImage
import kotlin.math.roundToInt

/**
 * Amplitude over subcarrier and time.
 *
 * The image is built once at the data's own resolution and then scaled by the
 * graphics pipeline, so a repaint costs a blit rather than a re-read of the
 * capture.
 *
 * Three things this view refuses to do, because each would show something the
 * capture does not contain:
 *
 *  * it never interpolates between tone geometries - a 52-tone legacy frame and
 *    a 242-tone HE frame share no subcarrier grid, so records outside the
 *    selected geometry are drawn as gaps;
 *  * it never paints a gap as a low amplitude - a column no record landed in is
 *    hatched, which reads as "no measurement" rather than "weak signal";
 *  * it never labels the colour key in dBm - amplitude here is AGC-normalised
 *    and carries the channel's shape only.
 */
class WaterfallPanel(private val state: CaptureViewState) : PlotPanel() {

    private var image: BufferedImage? = null
    private var layer: WaterfallLayer? = null
    private var window: WaterfallWindow? = null
    private var hover: Point? = null
    private var hoverLines: List<String> = emptyList()
    private var loading = false

    /** Set when a zoom read is in flight, so a later one supersedes it. */
    private var pendingZoomToken = 0

    override val marginBottom: Int get() = JBUI.scale(74)
    override val marginTop: Int get() = JBUI.scale(28)

    init {
        rebuild()
        state.addListener {
            when (it) {
                CaptureViewState.Change.GEOMETRY,
                CaptureViewState.Change.SCALE,
                CaptureViewState.Change.ZOOM,
                -> rebuild()
                CaptureViewState.Change.SELECTION,
                CaptureViewState.Change.TIME,
                -> repaint()
            }
        }
        val mouse = object : MouseAdapter() {
            override fun mouseMoved(e: MouseEvent) {
                hover = e.point
                updateHover(e.point)
                repaint()
            }

            override fun mouseExited(e: MouseEvent) {
                hover = null
                hoverLines = emptyList()
                repaint()
            }

            override fun mouseClicked(e: MouseEvent) {
                rowAt(e.x)?.let { state.selectedRow = it }
                if (e.clickCount == 2) state.resetZoom()
            }

            override fun mouseWheelMoved(e: MouseWheelEvent) {
                if (!e.isControlDown && !e.isMetaDown) {
                    parent?.dispatchEvent(e)
                    return
                }
                val centre = rowAt(e.x) ?: state.selectedRow
                val currentSpan = if (state.isZoomed) state.zoomRowCount else state.capture.recordCount
                val factor = if (e.wheelRotation < 0) 0.6 else 1.0 / 0.6
                val span = (currentSpan * factor).roundToInt().coerceIn(64, state.capture.recordCount)
                if (span >= state.capture.recordCount) state.resetZoom() else state.zoomTo(centre - span / 2, span)
            }
        }
        addMouseListener(mouse)
        addMouseMotionListener(mouse)
        addMouseWheelListener(mouse)
    }

    private fun rebuild() {
        val tones = state.toneCount
        if (tones <= 0) {
            image = null
            repaint()
            return
        }
        if (state.isZoomed && state.zoomRowCount <= WaterfallWindow.MAX_RECORDS) {
            loadWindow(state.zoomFirstRow, state.zoomRowCount, tones, state.chain)
        } else {
            window = null
            layer = state.capture.waterfall.firstOrNull { it.toneCount == tones && it.chain == state.chain }
            image = layer?.let { WaterfallImage.fromLayer(it, state.colorMap, state.logScale) }
            repaint()
        }
    }

    private fun loadWindow(first: Int, count: Int, tones: Int, chain: Int) {
        val token = ++pendingZoomToken
        loading = true
        repaint()
        ApplicationManager.getApplication().executeOnPooledThread {
            val w = try {
                WaterfallWindow.load(
                    state.capture.source, state.capture.index, first, count, tones, chain,
                    cancelled = { token != pendingZoomToken },
                )
            } catch (_: Exception) {
                null
            }
            ApplicationManager.getApplication().invokeLater {
                if (token != pendingZoomToken) return@invokeLater
                loading = false
                window = w
                layer = null
                image = w?.let { WaterfallImage.fromWindow(it, state.colorMap, state.logScale) }
                repaint()
            }
        }
    }

    // -- the time axis -------------------------------------------------------

    /**
     * Ticks whose labels come from the record actually under them.
     *
     * The x axis is a column index, and time is not linear in it. Columns in
     * the overview are spaced by byte position, and records are not all the
     * same size; columns in a zoom window are spaced by record index, and
     * records are not evenly spaced in time. Interpolating a label between the
     * two ends would therefore print a time that is close but wrong, which is
     * worse on a clock axis than on an elapsed one. Each tick is read from its
     * own column instead.
     */
    private fun timeTicks(imageWidth: Int, firstRow: Int, lastRow: Int): List<Tick> {
        val count = (plotWidth / JBUI.scale(110)).coerceIn(3, 9)
        val span = state.time.elapsedSeconds(lastRow) - state.time.elapsedSeconds(firstRow)
        val formatter = state.formatter(span.coerceAtLeast(0.0))
        val out = ArrayList<Tick>(count)
        var previous: String? = null
        for (i in 0 until count) {
            val fraction = i.toDouble() / (count - 1)
            val column = (fraction * (imageWidth - 1)).toInt().coerceIn(0, imageWidth - 1)
            val row = rowForColumn(column) ?: continue
            val label = state.timeLabel(row, formatter)
            // A repeated label means the pattern is coarser than the span; one
            // of them carries no information, so it is dropped.
            if (label == previous) continue
            previous = label
            out.add(Tick(fraction, label))
        }
        return out
    }

    private fun axisLabel(firstRow: Int, lastRow: Int): String {
        val span = state.time.elapsedSeconds(lastRow) - state.time.elapsedSeconds(firstRow)
        val formatter = state.formatter(span.coerceAtLeast(0.0))
        return when (state.timeMode) {
            TimeAxisMode.ELAPSED ->
                "elapsed time on the baseband clock, seconds"
            TimeAxisMode.WALL_CLOCK -> {
                val a = state.time.absoluteNanos(firstRow)
                val b = state.time.absoluteNanos(lastRow)
                if (a != null && b != null) {
                    "wall clock   ${formatter.caption(a, b)}   anchored on ${state.time.anchorSource.label}"
                } else {
                    "elapsed time on the baseband clock, seconds"
                }
            }
            TimeAxisMode.HOST_CLOCK -> {
                val a = state.time.hostNanos(firstRow)
                val b = state.time.hostNanos(lastRow)
                if (a != null && b != null) {
                    "host clock, unix_ts_ns as stored   ${formatter.caption(a, b)}"
                } else {
                    "host clock, unix_ts_ns as stored   (absent on these records)"
                }
            }
        }
    }

    /** The index row a column represents, stepping over gaps. */
    private fun rowForColumn(column: Int): Int? {
        window?.let { return it.rowOf.getOrNull(column) }
        val l = layer ?: return null
        var step = 0
        while (step < l.columns) {
            val probe = column + if (step % 2 == 0) step / 2 else -(step / 2 + 1)
            if (probe in 0 until l.columns && l.firstRow[probe] >= 0) return l.firstRow[probe]
            step++
        }
        return null
    }

    // -- hit testing ---------------------------------------------------------

    private fun rowAt(x: Int): Int? {
        val img = image ?: return null
        val col = ((x - plotX).toDouble() / plotWidth * img.width).toInt()
        if (col < 0 || col >= img.width) return null
        window?.let { return it.rowOf.getOrNull(col) }
        val l = layer ?: return null
        var step = 0
        while (step < l.columns) {
            val probe = col + if (step % 2 == 0) step / 2 else -(step / 2 + 1)
            if (probe in 0 until l.columns && l.firstRow[probe] >= 0) return l.firstRow[probe]
            step++
        }
        return null
    }

    private fun toneAt(y: Int): Int? {
        val img = image ?: return null
        val t = ((plotY + plotHeight - y).toDouble() / plotHeight * img.height).toInt()
        return t.takeIf { it in 0 until img.height }
    }

    private fun updateHover(p: Point) {
        val row = rowAt(p.x)
        val tone = toneAt(p.y)
        if (row == null || tone == null || !plotBounds.contains(p)) {
            hoverLines = emptyList()
            return
        }
        val index = state.capture.index
        val amp = amplitudeAt(p.x, tone)
        val lines = ArrayList<String>(6)
        lines += "record $row"
        // The full instant here, not the axis's abbreviated tick: a reader
        // hovering a point wants the date and the milliseconds.
        val nanos = state.time.nanosFor(row, state.timeMode)
        if (nanos != null) lines += state.formatter(0.0).full(nanos)
        lines += "%.3f s elapsed".fmt(state.time.elapsedSeconds(row))
        lines += "subcarrier $tone of ${state.toneCount}"
        if (amp != null && !amp.isNaN()) lines += "|H| %.1f".fmt(amp)
        val rssi = index.rssi0.getOrNull(row)?.toInt()
        if (rssi != null) lines += "rssi $rssi dBm   ${index.modulationOf(row).label}"
        lines += index.srcMacString(row)
        hoverLines = lines
    }

    private fun amplitudeAt(x: Int, tone: Int): Float? {
        window?.let { w ->
            val col = ((x - plotX).toDouble() / plotWidth * w.rowCount).toInt()
            return w.amplitude.getOrNull(col * w.toneCount + tone)
        }
        layer?.let { l ->
            val col = ((x - plotX).toDouble() / plotWidth * l.columns).toInt().coerceIn(0, l.columns - 1)
            return l.mean(col, tone)
        }
        return null
    }

    // -- painting ------------------------------------------------------------

    override fun paintPlot(g: Graphics2D) {
        val img = image
        if (img == null) {
            drawEmptyState(
                g,
                if (state.toneCount <= 0) "No CSI matrix to draw" else "Nothing on this geometry",
                if (state.toneCount <= 0) {
                    "Every record in this capture carries an empty CSI payload."
                } else {
                    "No record carries ${state.toneCount} subcarriers on chain ${state.chain}."
                },
            )
            return
        }

        val index = state.capture.index
        val firstRow = (window?.firstRow ?: 0).coerceIn(0, index.size - 1)
        val lastRow = (window?.let { it.firstRow + it.rowCount - 1 } ?: (state.capture.recordCount - 1))
            .coerceIn(0, index.size - 1)

        drawFrame(
            g,
            plotBounds,
            xTicks = timeTicks(img.width, firstRow, lastRow),
            yTicks = ticks(0.0, img.height.toDouble(), 5) { "%.0f".fmt(it) },
            xLabel = axisLabel(firstRow, lastRow),
            yLabel = "subcarrier",
            title = if (window != null) {
                "records $firstRow to $lastRow of ${state.capture.recordCount}"
            } else {
                "all ${state.capture.recordCount} records"
            },
        )

        val clip = g.clip
        g.clipRect(plotX, plotY, plotWidth, plotHeight)
        g.drawImage(img, plotX, plotY, plotWidth, plotHeight, null)
        drawGapHatch(g)
        g.clip = clip

        drawSelectionMarker(g)
        drawColourKey(g)
        drawContextStrip(g)

        if (loading) {
            g.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.72f)
            g.color = CsiqTheme.surface
            g.fillRect(plotX, plotY, plotWidth, plotHeight)
            g.composite = AlphaComposite.SrcOver
            drawEmptyState(g, "Reading ${state.zoomRowCount} records", "One contiguous read from the source.")
        }

        hover?.let { p ->
            if (hoverLines.isNotEmpty()) {
                drawCrosshair(g, p)
                CsiqTheme.paintTooltip(g, p.x, p.y, Rectangle(0, 0, width, height), hoverLines)
            }
        }
    }

    /**
     * Hatch the columns no record landed in.
     *
     * Transparency alone would let a gap read as the panel's background, which
     * is close enough to the darkest end of a colour map to be mistaken for a
     * very low amplitude. A diagonal hatch cannot be mistaken for data.
     */
    private fun drawGapHatch(g: Graphics2D) {
        val l = layer ?: return
        val img = image ?: return
        val runs = ArrayList<IntArray>()
        var start = -1
        for (c in 0 until l.columns) {
            val gap = l.isGap(c)
            if (gap && start < 0) start = c
            if (!gap && start >= 0) { runs.add(intArrayOf(start, c)); start = -1 }
        }
        if (start >= 0) runs.add(intArrayOf(start, l.columns))
        if (runs.isEmpty()) return

        g.paint = hatchPaint()
        for (run in runs) {
            val x0 = plotX + (run[0].toDouble() / img.width * plotWidth).toInt()
            val x1 = plotX + (run[1].toDouble() / img.width * plotWidth).toInt()
            if (x1 > x0) g.fillRect(x0, plotY, x1 - x0, plotHeight)
        }
        g.paint = null
    }

    private fun hatchPaint(): TexturePaint {
        val size = JBUI.scale(6)
        val tile = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
        val tg = tile.createGraphics()
        CsiqTheme.antialias(tg)
        val ink = CsiqTheme.inkMuted
        tg.color = Color(ink.red, ink.green, ink.blue, 46)
        tg.stroke = CsiqTheme.hairline()
        tg.drawLine(0, size, size, 0)
        tg.dispose()
        return TexturePaint(tile, Rectangle(0, 0, size, size))
    }

    private fun drawSelectionMarker(g: Graphics2D) {
        val img = image ?: return
        val selected = state.selectedRow
        val col = when {
            window != null -> {
                val w = window!!
                if (selected < w.firstRow || selected >= w.firstRow + w.rowCount) return
                selected - w.firstRow
            }
            layer != null -> {
                val l = layer!!
                (0 until l.columns).firstOrNull { l.firstRow[it] <= selected && selected <= l.lastRow[it] } ?: return
            }
            else -> return
        }
        val x = plotX + (col.toDouble() / img.width * plotWidth).toInt()
        g.color = CsiqTheme.inkPrimary
        g.stroke = CsiqTheme.hairline()
        g.drawLine(x, plotY, x, plotY + plotHeight)
        // A small flag at the top, so the marker is findable when the image
        // behind it is bright.
        val s = JBUI.scale(5)
        g.fillPolygon(intArrayOf(x - s, x + s, x), intArrayOf(plotY, plotY, plotY + s), 3)
    }

    private fun drawCrosshair(g: Graphics2D, p: Point) {
        g.color = CsiqTheme.inkMuted
        g.stroke = CsiqTheme.dotted()
        g.drawLine(plotX, p.y, plotX + plotWidth, p.y)
        g.drawLine(p.x, plotY, p.x, plotY + plotHeight)
    }

    /**
     * The colour key.
     *
     * Labelled "low" and "high" rather than with numbers: the values are
     * AGC-normalised and clipped at a percentile, so printing them would
     * suggest an absolute scale the data does not carry. The absolute
     * reference is RSSI, and it is on the spectrum view.
     */
    private fun drawColourKey(g: Graphics2D) {
        val h = JBUI.scale(9)
        val w = JBUI.scale(132)
        val x = plotX + plotWidth - w
        val y = height - JBUI.scale(26)
        for (i in 0 until w) {
            g.color = state.colorMap.color(i.toFloat() / (w - 1))
            g.fillRect(x + i, y, 1, h)
        }
        g.color = CsiqTheme.border
        g.stroke = CsiqTheme.hairline()
        g.drawRect(x, y, w, h)

        val previous = g.font
        g.font = CsiqTheme.captionFont(previous)
        val fm = g.fontMetrics
        g.color = CsiqTheme.inkMuted
        val caption = if (state.logScale) "|H|, log" else "|H|, linear"
        g.drawString(caption, x - fm.stringWidth(caption) - CsiqTheme.gapS, y + h - JBUI.scale(1))
        g.drawString("low", x, y + h + fm.ascent + JBUI.scale(2))
        g.drawString("high", x + w - fm.stringWidth("high"), y + h + fm.ascent + JBUI.scale(2))
        g.font = previous
    }

    /**
     * Where the zoom window sits in the whole capture.
     *
     * Without it a zoomed view has no anchor: every window of a long capture
     * looks like the whole of it.
     */
    private fun drawContextStrip(g: Graphics2D) {
        val w = window ?: return
        val total = state.capture.recordCount
        if (total <= 0) return
        val h = JBUI.scale(5)
        val y = height - JBUI.scale(42)
        val x = plotX
        val fullWidth = plotWidth

        g.color = CsiqTheme.border
        g.fill(CsiqTheme.rounded(x, y, fullWidth, h))

        val start = (w.firstRow.toDouble() / total * fullWidth).toInt()
        val span = (w.rowCount.toDouble() / total * fullWidth).toInt().coerceAtLeast(JBUI.scale(3))
        g.color = CsiqTheme.seriesColor(0)
        g.fill(CsiqTheme.rounded(x + start, y, span.coerceAtMost(fullWidth - start), h))

        val previous = g.font
        g.font = CsiqTheme.captionFont(previous)
        g.color = CsiqTheme.inkMuted
        val text = "showing %.1f %% of the capture   -   ctrl-scroll to zoom, double-click for all of it"
            .format(w.rowCount * 100.0 / total)
        g.drawString(text, x, y - JBUI.scale(3))
        g.font = previous
    }
}
