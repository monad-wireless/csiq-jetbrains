package io.monadcount.csiq.ide.ui

import com.intellij.util.ui.JBUI
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import javax.swing.JPanel

/**
 * A headline number.
 *
 * Some facts are not a chart. "1,128,956 records" and "16 percent all-zero" are
 * single values a reader needs at a glance, and a tile states one of them
 * without the furniture a plot would bring.
 *
 * The status colour is carried by a rule under the number, never by the number
 * itself: text wears text colours, so a value stays legible when the status is
 * only a hint and readable to someone who cannot see the hue at all. Anything
 * beyond neutral also spells its reason out in the caption.
 */
class StatTile(
    private val label: String,
    private val value: String,
    private val caption: String?,
    private val status: CsiqTheme.Status = CsiqTheme.Status.NEUTRAL,
) : JPanel() {

    init {
        isOpaque = false
        preferredSize = Dimension(JBUI.scale(190), JBUI.scale(84))
        minimumSize = Dimension(JBUI.scale(140), JBUI.scale(84))
    }

    override fun paintComponent(g: Graphics) {
        super.paintComponent(g)
        val g2 = g.create() as Graphics2D
        try {
            CsiqTheme.antialias(g2)
            val pad = CsiqTheme.gapM

            g2.color = CsiqTheme.surfaceRaised
            g2.fill(CsiqTheme.rounded(0, 0, width - 1, height - 1))
            g2.color = CsiqTheme.border
            g2.stroke = CsiqTheme.hairline()
            g2.draw(CsiqTheme.rounded(0, 0, width - 1, height - 1))

            // The status accent: a bar down the left edge, not a coloured or
            // underlined number. An underline under a value reads as a typo
            // mark, and a coloured value breaks the rule that text wears text
            // colours. Neutral draws nothing at all.
            val textX = if (status == CsiqTheme.Status.NEUTRAL) {
                pad
            } else {
                val barWidth = JBUI.scale(3)
                g2.color = CsiqTheme.statusColor(status)
                g2.fillRect(0, JBUI.scale(8), barWidth, height - JBUI.scale(17))
                pad + barWidth
            }

            val base = g2.font
            g2.font = CsiqTheme.labelFont(base)
            g2.color = CsiqTheme.inkMuted
            g2.drawString(label.uppercase(), textX, pad + g2.fontMetrics.ascent)

            g2.font = CsiqTheme.heroFont(base)
            g2.color = CsiqTheme.inkPrimary
            g2.drawString(value, textX, pad + JBUI.scale(14) + g2.fontMetrics.ascent)

            caption?.let {
                g2.font = CsiqTheme.captionFont(base)
                g2.color = CsiqTheme.inkSecondary
                val fm = g2.fontMetrics
                val maxWidth = width - textX - pad
                var text = it
                if (fm.stringWidth(text) > maxWidth) {
                    while (text.isNotEmpty() && fm.stringWidth("$text...") > maxWidth) {
                        text = text.dropLast(1)
                    }
                    text = "$text..."
                }
                g2.drawString(text, textX, height - pad)
            }
            g2.font = base
        } finally {
            g2.dispose()
        }
    }
}
