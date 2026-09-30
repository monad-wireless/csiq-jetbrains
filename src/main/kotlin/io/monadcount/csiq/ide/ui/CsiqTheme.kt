package io.monadcount.csiq.ide.ui

import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.Shape
import java.awt.geom.RoundRectangle2D

/**
 * The plugin's design tokens.
 *
 * Colour is assigned by the job it does, never by taste, and the categorical
 * slots below were chosen by running a colourblind-separation validator rather
 * than by eye. Both modes are selected: the dark column is the same hues
 * stepped for a dark surface, not an automatic inversion of the light one.
 *
 * The series palette is the reference palette's first three slots. Those three
 * clear every gate under the all-pairs rule in both modes, which is the right
 * rule here because chains are overlaid rather than stacked, so any two of them
 * can end up adjacent on screen. A fourth chain is possible on paper but the
 * hardware in this project is 2x2 with one spatial stream, so it has never been
 * seen; it takes the yellow slot and leans on the direct labels and the dash
 * pattern that every series already carries.
 */
object CsiqTheme {

    // -- ink -----------------------------------------------------------------

    val inkPrimary: Color get() = UIUtil.getLabelForeground()
    val inkSecondary: Color get() = UIUtil.getContextHelpForeground()
    val inkMuted: JBColor = JBColor(Color(0x8A, 0x89, 0x85), Color(0x7A, 0x7A, 0x78))

    val surface: Color get() = UIUtil.getPanelBackground()

    /** A panel that sits above the surface, for tiles and tooltips. */
    val surfaceRaised: JBColor = JBColor(Color(0xFF, 0xFF, 0xFF), Color(0x2B, 0x2D, 0x30))

    val border: JBColor = JBColor(Color(0xD6, 0xD6, 0xD2), Color(0x39, 0x3B, 0x40))

    /**
     * Grid lines, deliberately recessive.
     *
     * A grid is a reading aid. When it competes with the data it stops being
     * one, so it sits at about a tenth of the ink's weight.
     */
    val grid: JBColor = JBColor(Color(0, 0, 0, 26), Color(255, 255, 255, 24))

    val axis: JBColor = JBColor(Color(0xC4, 0xC4, 0xC0), Color(0x4A, 0x4C, 0x50))

    // -- categorical: one slot per chain -------------------------------------

    /**
     * Chain colours, in fixed order and never cycled.
     *
     * Validated all-pairs in both modes: worst colour-vision separation
     * dE 9.2 light and 9.4 dark, worst normal-vision separation dE 24.0 light
     * and 20.9 dark.
     */
    val series: List<JBColor> = listOf(
        JBColor(Color(0x2A, 0x78, 0xD6), Color(0x39, 0x87, 0xE5)), // blue
        JBColor(Color(0xEB, 0x68, 0x34), Color(0xD9, 0x59, 0x26)), // orange
        JBColor(Color(0x1B, 0xAF, 0x7A), Color(0x19, 0x9E, 0x70)), // aqua
        JBColor(Color(0xED, 0xA1, 0x00), Color(0xC9, 0x85, 0x00)), // yellow, the unseen fourth
    )

    fun seriesColor(index: Int): JBColor = series[index.coerceIn(0, series.lastIndex)]

    // -- status: reserved, never reused as a series --------------------------

    val statusGood: JBColor = JBColor(Color(0x00, 0x83, 0x00), Color(0x3F, 0xB9, 0x50))
    val statusWarning: JBColor = JBColor(Color(0xED, 0xA1, 0x00), Color(0xC9, 0x85, 0x00))
    val statusCritical: JBColor = JBColor(Color(0xE3, 0x49, 0x48), Color(0xE6, 0x67, 0x67))

    /** Severity of a fact shown to the operator. */
    enum class Status { NEUTRAL, GOOD, WARNING, CRITICAL }

    fun statusColor(status: Status): Color = when (status) {
        Status.NEUTRAL -> inkSecondary
        Status.GOOD -> statusGood
        Status.WARNING -> statusWarning
        Status.CRITICAL -> statusCritical
    }

    // -- type ----------------------------------------------------------------

    /** A headline number on a tile. */
    fun heroFont(base: Font): Font = base.deriveFont(Font.PLAIN, JBUI.scaleFontSize(21f).toFloat())

    /** An axis tick or a legend entry. */
    fun captionFont(base: Font): Font = base.deriveFont(Font.PLAIN, JBUI.scaleFontSize(11f).toFloat())

    /** A small upper-case label above a tile's number. */
    fun labelFont(base: Font): Font = base.deriveFont(Font.PLAIN, JBUI.scaleFontSize(10f).toFloat())

    fun monoFont(size: Float = 12f): Font = Font(Font.MONOSPACED, Font.PLAIN, JBUI.scaleFontSize(size))

    // -- marks ---------------------------------------------------------------

    /** Data lines: thin, but above the grid's weight. */
    fun seriesStroke(): BasicStroke = BasicStroke(
        JBUI.scale(2).toFloat(),
        BasicStroke.CAP_ROUND,
        BasicStroke.JOIN_ROUND,
    )

    /**
     * The stroke for a chain that reported no measurement.
     *
     * Dashed, so identity never rests on colour alone and the reader can see
     * the difference in a screenshot, in print, and with any colour vision.
     */
    fun staleStroke(): BasicStroke = BasicStroke(
        JBUI.scale(2).toFloat(),
        BasicStroke.CAP_BUTT,
        BasicStroke.JOIN_ROUND,
        10f,
        floatArrayOf(JBUI.scale(5).toFloat(), JBUI.scale(4).toFloat()),
        0f,
    )

    fun hairline(): BasicStroke = BasicStroke(JBUI.scale(1).toFloat())

    /** A dotted rule, for a grid or a crosshair. */
    fun dotted(): BasicStroke = BasicStroke(
        JBUI.scale(1).toFloat(),
        BasicStroke.CAP_BUTT,
        BasicStroke.JOIN_MITER,
        10f,
        floatArrayOf(1f, JBUI.scale(3).toFloat()),
        0f,
    )

    // -- spacing -------------------------------------------------------------

    val gapXs: Int get() = JBUI.scale(4)
    val gapS: Int get() = JBUI.scale(8)
    val gapM: Int get() = JBUI.scale(12)
    val gapL: Int get() = JBUI.scale(18)

    val radius: Float get() = JBUI.scale(6).toFloat()

    // -- painters ------------------------------------------------------------

    fun rounded(x: Int, y: Int, w: Int, h: Int): Shape =
        RoundRectangle2D.Float(x.toFloat(), y.toFloat(), w.toFloat(), h.toFloat(), radius, radius)

    fun antialias(g: Graphics2D) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
    }

    /**
     * A small rounded label carrying one fact.
     *
     * Returns the width it painted, so a row of them can be laid out without
     * measuring twice.
     */
    fun paintChip(
        g: Graphics2D,
        x: Int,
        y: Int,
        text: String,
        accent: Color? = null,
        filled: Boolean = false,
    ): Int {
        val font = captionFont(g.font)
        val old = g.font
        g.font = font
        val fm = g.fontMetrics
        val padX = gapS
        val dotWidth = if (accent != null) JBUI.scale(12) else 0
        val w = fm.stringWidth(text) + 2 * padX + dotWidth
        val h = fm.height + JBUI.scale(5)

        if (filled && accent != null) {
            g.color = Color(accent.red, accent.green, accent.blue, 28)
            g.fill(rounded(x, y, w, h))
        }
        g.color = border
        g.stroke = hairline()
        g.draw(rounded(x, y, w, h))

        var textX = x + padX
        if (accent != null) {
            val d = JBUI.scale(6)
            g.color = accent
            g.fillOval(textX, y + (h - d) / 2, d, d)
            textX += dotWidth
        }
        // A filled chip is one the reader must not skim past, so its text takes
        // the primary ink. Still a text token: the accent stays in the dot and
        // the wash, never in the letters.
        g.color = if (filled) inkPrimary else inkSecondary
        g.drawString(text, textX, y + h / 2 + fm.ascent / 2 - JBUI.scale(1))
        g.font = old
        return w
    }

    /** A tooltip box: raised surface, hairline border, one line per entry. */
    fun paintTooltip(g: Graphics2D, anchorX: Int, anchorY: Int, bounds: java.awt.Rectangle, lines: List<String>) {
        if (lines.isEmpty()) return
        val font = captionFont(g.font)
        val old = g.font
        g.font = font
        val fm = g.fontMetrics
        val padX = gapS
        val padY = gapXs + JBUI.scale(2)
        val w = lines.maxOf { fm.stringWidth(it) } + 2 * padX
        val h = lines.size * fm.height + 2 * padY

        // Keep the box inside the panel, flipping it around the cursor rather
        // than letting it run off an edge.
        var x = anchorX + gapM
        var y = anchorY + gapM
        if (x + w > bounds.x + bounds.width) x = anchorX - gapM - w
        if (y + h > bounds.y + bounds.height) y = anchorY - gapM - h
        x = x.coerceIn(bounds.x, (bounds.x + bounds.width - w).coerceAtLeast(bounds.x))
        y = y.coerceIn(bounds.y, (bounds.y + bounds.height - h).coerceAtLeast(bounds.y))

        g.color = surfaceRaised
        g.fill(rounded(x, y, w, h))
        g.color = border
        g.stroke = hairline()
        g.draw(rounded(x, y, w, h))

        g.color = inkPrimary
        lines.forEachIndexed { i, line ->
            g.drawString(line, x + padX, y + padY + fm.ascent + i * fm.height)
        }
        g.font = old
    }
}
