package io.monadcount.csiq.ide.ui

import com.intellij.util.ui.JBUI
import io.monadcount.csiq.format.Payload
import io.monadcount.csiq.model.Capture
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import javax.swing.JPanel

/**
 * The capture's identity, across the top of the editor.
 *
 * A capture is not a file in isolation: it belongs to a node, an experiment and
 * a run, and those live in the sidecar rather than in the bytes. Putting them
 * here means a reader never has to open a tab to know which measurement is on
 * screen.
 */
class CaptureHeader(private val capture: Capture) : JPanel() {

    init {
        isOpaque = true
        preferredSize = Dimension(0, JBUI.scale(38))
    }

    private class Chip(val text: String, val accent: java.awt.Color? = null, val filled: Boolean = false)

    private fun chips(): List<Chip> {
        val out = ArrayList<Chip>(8)
        val s = capture.sidecar

        out += Chip(
            buildString {
                append(capture.payload.label)
                if (capture.compression.isCompressed) append(" / ").append(capture.compression.label)
            },
            CsiqTheme.seriesColor(0),
        )
        s?.hostname?.let { out += Chip(it) }
        s?.experiment?.let { out += Chip(it) }
        s?.runId?.let { out += Chip(it) }

        val radio = buildString {
            s?.channel?.let { append("ch ").append(it) }
            if (capture.health.monitorWidth != io.monadcount.csiq.format.Width.UNKNOWN) {
                if (isNotEmpty()) append("  ")
                append(capture.health.monitorWidth.label)
            }
        }
        if (radio.isNotBlank()) out += Chip(radio)

        if (s?.isSegment == true) {
            out += Chip("segment ${s.segmentNumber ?: "?"}", CsiqTheme.statusWarning, filled = true)
        }
        if (capture.payload == Payload.RAW_DRIVER_STREAM &&
            capture.health.monitorWidth == io.monadcount.csiq.format.Width.UNKNOWN
        ) {
            out += Chip("width unknown", CsiqTheme.statusWarning, filled = true)
        }
        if (capture.lifecycleIsStale) {
            out += Chip("embedded status is stale", CsiqTheme.statusWarning, filled = true)
        }
        capture.recordCountMismatch?.let {
            out += Chip("record count differs by $it", CsiqTheme.statusCritical, filled = true)
        }
        return out
    }

    override fun paintComponent(g: Graphics) {
        super.paintComponent(g)
        val g2 = g.create() as Graphics2D
        try {
            CsiqTheme.antialias(g2)
            g2.color = CsiqTheme.surface
            g2.fillRect(0, 0, width, height)
            g2.color = CsiqTheme.border
            g2.stroke = CsiqTheme.hairline()
            g2.drawLine(0, height - 1, width, height - 1)

            var x = CsiqTheme.gapM
            val y = (height - JBUI.scale(20)) / 2
            for (chip in chips()) {
                if (x > width - JBUI.scale(60)) break
                x += CsiqTheme.paintChip(g2, x, y, chip.text, chip.accent, chip.filled) + CsiqTheme.gapXs
            }
        } finally {
            g2.dispose()
        }
    }
}
