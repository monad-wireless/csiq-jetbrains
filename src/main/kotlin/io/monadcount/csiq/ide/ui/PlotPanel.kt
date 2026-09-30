package io.monadcount.csiq.ide.ui

import com.intellij.util.ui.JBUI
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Rectangle
import javax.swing.JPanel

/**
 * Shared drawing for every plot in the editor.
 *
 * Nothing here decides what to draw. It decides where the plot area is, how an
 * axis looks and how an empty state reads, so the views stay comparable and a
 * reader can move between them without relearning the furniture.
 *
 * Axes are recessive by construction: a dotted grid at a tenth of the ink's
 * weight, a baseline and a left rule rather than a box, and ticks outside the
 * plot area. The data is the only thing drawn at full strength.
 */
abstract class PlotPanel : JPanel() {

    init {
        isOpaque = true
        preferredSize = Dimension(720, 360)
    }

    protected open val marginLeft: Int get() = JBUI.scale(62)
    protected open val marginRight: Int get() = JBUI.scale(16)
    protected open val marginTop: Int get() = JBUI.scale(16)
    protected open val marginBottom: Int get() = JBUI.scale(42)

    protected val plotX: Int get() = marginLeft
    protected val plotY: Int get() = marginTop
    protected val plotWidth: Int get() = (width - marginLeft - marginRight).coerceAtLeast(1)
    protected val plotHeight: Int get() = (height - marginTop - marginBottom).coerceAtLeast(1)
    protected val plotBounds: Rectangle get() = Rectangle(plotX, plotY, plotWidth, plotHeight)

    final override fun paintComponent(g: Graphics) {
        super.paintComponent(g)
        val g2 = g.create() as Graphics2D
        try {
            CsiqTheme.antialias(g2)
            g2.color = CsiqTheme.surface
            g2.fillRect(0, 0, width, height)
            paintPlot(g2)
        } finally {
            g2.dispose()
        }
    }

    protected abstract fun paintPlot(g: Graphics2D)

    /** A tick: where on the axis, and what to print there. */
    class Tick(val fraction: Double, val label: String)

    /**
     * Draw the furniture for a plot area.
     *
     * @param area the rectangle the data occupies.
     */
    protected fun drawFrame(
        g: Graphics2D,
        area: Rectangle,
        xTicks: List<Tick> = emptyList(),
        yTicks: List<Tick> = emptyList(),
        xLabel: String? = null,
        yLabel: String? = null,
        title: String? = null,
    ) {
        val caption = CsiqTheme.captionFont(g.font)
        val previous = g.font
        g.font = caption
        val fm = g.fontMetrics

        g.stroke = CsiqTheme.dotted()
        g.color = CsiqTheme.grid
        for (t in xTicks) {
            val x = area.x + (t.fraction * area.width).toInt()
            g.drawLine(x, area.y, x, area.y + area.height)
        }
        for (t in yTicks) {
            val y = area.y + area.height - (t.fraction * area.height).toInt()
            g.drawLine(area.x, y, area.x + area.width, y)
        }

        // A baseline and a left rule, not a box: two sides carry the scale and
        // the other two only add ink.
        g.stroke = CsiqTheme.hairline()
        g.color = CsiqTheme.axis
        g.drawLine(area.x, area.y + area.height, area.x + area.width, area.y + area.height)
        g.drawLine(area.x, area.y, area.x, area.y + area.height)

        g.color = CsiqTheme.inkMuted
        for (t in xTicks) {
            val x = area.x + (t.fraction * area.width).toInt()
            g.drawLine(x, area.y + area.height, x, area.y + area.height + JBUI.scale(3))
            // Centred on the tick, but kept inside the panel. A label centred
            // on the last tick runs half its width past the right edge, and a
            // clock label loses its seconds there.
            val labelWidth = fm.stringWidth(t.label)
            val labelX = (x - labelWidth / 2).coerceIn(
                0,
                (width - labelWidth).coerceAtLeast(0),
            )
            g.drawString(t.label, labelX, area.y + area.height + JBUI.scale(4) + fm.ascent)
        }
        for (t in yTicks) {
            val y = area.y + area.height - (t.fraction * area.height).toInt()
            g.drawString(
                t.label,
                area.x - fm.stringWidth(t.label) - JBUI.scale(7),
                y + fm.ascent / 2 - JBUI.scale(1),
            )
        }

        title?.let {
            g.color = CsiqTheme.inkSecondary
            g.drawString(it, area.x, area.y - JBUI.scale(5))
        }
        xLabel?.let {
            g.color = CsiqTheme.inkSecondary
            g.drawString(it, area.x + area.width / 2 - fm.stringWidth(it) / 2, height - JBUI.scale(8))
        }
        yLabel?.let {
            g.color = CsiqTheme.inkSecondary
            val old = g.transform
            g.rotate(-Math.PI / 2, JBUI.scale(14).toDouble(), (area.y + area.height / 2).toDouble())
            g.drawString(it, JBUI.scale(14) - fm.stringWidth(it) / 2, area.y + area.height / 2)
            g.transform = old
        }
        g.font = previous
    }

    /** Evenly spaced ticks in [0, 1], with values formatted by [format]. */
    protected fun ticks(min: Double, max: Double, count: Int, format: (Double) -> String): List<Tick> {
        if (count < 2 || !min.isFinite() || !max.isFinite() || max <= min) return emptyList()
        return (0 until count).map { i ->
            val t = i.toDouble() / (count - 1)
            Tick(t, format(min + t * (max - min)))
        }
    }

    /**
     * What a view shows when it has nothing to draw.
     *
     * An empty state says which condition of the data produced it, because
     * "nothing here" and "this record carries an all-zero CSI matrix" are
     * different facts and only one of them is a problem.
     */
    protected fun drawEmptyState(g: Graphics2D, headline: String, detail: String? = null) {
        val previous = g.font
        val fm = g.fontMetrics
        g.color = CsiqTheme.inkSecondary
        g.drawString(headline, (width - fm.stringWidth(headline)) / 2, height / 2)
        detail?.let {
            g.font = CsiqTheme.captionFont(previous)
            val dfm = g.fontMetrics
            g.color = CsiqTheme.inkMuted
            g.drawString(it, (width - dfm.stringWidth(it)) / 2, height / 2 + fm.height)
        }
        g.font = previous
    }

    /** Draw a polyline through normalised values, clipped to [area]. */
    protected inline fun drawSeries(
        g: Graphics2D,
        area: Rectangle,
        count: Int,
        normalise: (Int) -> Double,
    ) {
        if (count < 2) return
        val xs = IntArray(count)
        val ys = IntArray(count)
        for (i in 0 until count) {
            xs[i] = area.x + (i.toDouble() / (count - 1) * area.width).toInt()
            ys[i] = area.y + area.height - (normalise(i).coerceIn(0.0, 1.0) * area.height).toInt()
        }
        g.drawPolyline(xs, ys, count)
    }
}
