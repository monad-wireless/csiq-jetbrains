package io.monadcount.csiq.ide.ui

import com.intellij.util.ui.JBUI
import io.monadcount.csiq.format.CsiRecord
import io.monadcount.csiq.format.Csiq
import io.monadcount.csiq.format.Dsp
import io.monadcount.csiq.format.fmt
import java.awt.Graphics2D
import java.awt.Rectangle

/**
 * One record's frequency response: amplitude per subcarrier, and phase.
 *
 * Every chain is drawn, and a chain that reported the no-measurement sentinel
 * is drawn dashed and named in the legend. Its CSI is a byte-identical copy of
 * an earlier frame, so hiding it would be wrong and drawing it as an equal
 * would be worse. The dash is the secondary encoding: identity never rests on
 * colour alone.
 */
class SpectrumPanel(
    private val state: CaptureViewState,
    private val loader: RecordLoader,
) : PlotPanel() {

    private var record: CsiRecord? = null
    private var pending = false

    override val marginBottom: Int get() = JBUI.scale(58)
    override val marginTop: Int get() = JBUI.scale(22)

    init {
        refresh()
        state.addListener {
            when (it) {
                CaptureViewState.Change.SELECTION -> refresh()
                else -> repaint()
            }
        }
    }

    private fun refresh() {
        pending = true
        loader.request(state.selectedRow) { r ->
            record = r
            pending = false
            repaint()
        }
    }

    override fun paintPlot(g: Graphics2D) {
        val r = record
        if (r == null) {
            drawEmptyState(
                g,
                if (pending) "Reading record ${state.selectedRow}" else "No record",
                if (pending) "The CSI matrix is not held in the index, so it is read on demand." else null,
            )
            return
        }
        if (!r.geometryConsistent) {
            drawEmptyState(
                g,
                "Record ${state.selectedRow} does not match its declared geometry",
                "It declares ${r.ntone} subcarriers on ${r.chains} chains, " +
                    "which needs ${2 * r.coeffCount} values, and carries ${r.iq.size}.",
            )
            return
        }
        if (r.isAllZero()) {
            drawEmptyState(
                g,
                "Record ${state.selectedRow} carries an all-zero CSI matrix",
                "About one AX210 record in six is like this. It is not a parse failure.",
            )
            return
        }

        val gap = CsiqTheme.gapL
        val half = (plotHeight - gap) / 2
        val amplitudeArea = Rectangle(plotX, plotY, plotWidth, half)
        val phaseArea = Rectangle(plotX, plotY + half + gap, plotWidth, half)

        drawAmplitude(g, r, amplitudeArea)
        drawPhase(g, r, phaseArea)
        drawLegend(g, r)
    }

    private fun drawAmplitude(g: Graphics2D, r: CsiRecord, area: Rectangle) {
        val series = (0 until r.chains).mapNotNull { c -> r.chainAmplitude(c)?.let { c to it } }
        if (series.isEmpty()) return
        val max = series.maxOf { it.second.max() }.coerceAtLeast(1f)

        drawFrame(
            g,
            area,
            xTicks = ticks(0.0, r.ntone.toDouble(), 7) { "%.0f".fmt(it) },
            yTicks = ticks(0.0, max.toDouble(), 4) { "%.0f".fmt(it) },
            title = "amplitude   |H|, AGC-normalised, so this is shape and not level",
        )
        for ((chain, amp) in series) {
            g.color = CsiqTheme.seriesColor(chain)
            g.stroke = strokeFor(r, chain)
            drawSeries(g, area, amp.size) { (amp[it] / max).toDouble() }
        }
    }

    private fun drawPhase(g: Graphics2D, r: CsiRecord, area: Rectangle) {
        val series = (0 until r.chains).mapNotNull { c ->
            r.chainPhase(c)?.let { raw ->
                val unwrapped = Dsp.unwrapPhase(raw)
                c to if (state.detrendPhase) Dsp.detrend(unwrapped) else unwrapped
            }
        }
        if (series.isEmpty()) return
        var lo = Float.MAX_VALUE
        var hi = -Float.MAX_VALUE
        for ((_, p) in series) { lo = minOf(lo, p.min()); hi = maxOf(hi, p.max()) }
        if (hi <= lo) hi = lo + 1f

        drawFrame(
            g,
            area,
            xTicks = ticks(0.0, r.ntone.toDouble(), 7) { "%.0f".fmt(it) },
            yTicks = ticks(lo.toDouble(), hi.toDouble(), 4) { "%.1f".fmt(it) },
            xLabel = "subcarrier",
            yLabel = "radians",
            title = if (state.detrendPhase) {
                "phase   unwrapped and detrended - the removed slope is carrier frequency offset"
            } else {
                "phase   unwrapped - the slope across subcarriers is carrier frequency offset"
            },
        )
        for ((chain, phase) in series) {
            g.color = CsiqTheme.seriesColor(chain)
            g.stroke = strokeFor(r, chain)
            drawSeries(g, area, phase.size) { ((phase[it] - lo) / (hi - lo)).toDouble() }
        }
    }

    private fun strokeFor(r: CsiRecord, chain: Int) =
        if (r.rssi.getOrNull(chain) == Csiq.RSSI_NO_MEASUREMENT) CsiqTheme.staleStroke() else CsiqTheme.seriesStroke()

    /**
     * The legend, which is also where the record's identity lives.
     *
     * A legend is always present once there are two series, and each entry
     * carries its chain's RSSI - the absolute reference the amplitude plot
     * deliberately lacks.
     */
    private fun drawLegend(g: Graphics2D, r: CsiRecord) {
        var x = plotX
        val y = height - JBUI.scale(26)
        for (chain in 0 until r.chains) {
            val rssi = r.rssi.getOrNull(chain)
            val stale = rssi == Csiq.RSSI_NO_MEASUREMENT
            val text = when {
                stale -> "chain $chain   no measurement, CSI is stale"
                rssi != null -> "chain $chain   $rssi dBm"
                else -> "chain $chain"
            }
            g.color = CsiqTheme.seriesColor(chain)
            g.stroke = if (stale) CsiqTheme.staleStroke() else CsiqTheme.seriesStroke()
            g.drawLine(x, y - JBUI.scale(4), x + JBUI.scale(18), y - JBUI.scale(4))
            x += JBUI.scale(24)

            val previous = g.font
            g.font = CsiqTheme.captionFont(previous)
            g.color = if (stale) CsiqTheme.inkMuted else CsiqTheme.inkSecondary
            g.drawString(text, x, y)
            x += g.fontMetrics.stringWidth(text) + CsiqTheme.gapL
            g.font = previous
        }

        val previous = g.font
        g.font = CsiqTheme.captionFont(previous)
        val geometry = buildString {
            append(r.ntone).append(" subcarriers")
            r.phy?.let { append("   ").append(it) }
            r.bandwidth?.let { append("   ").append(it.label) }
            r.toneSpacingKhz()?.let { append("   %.3f kHz spacing".fmt(it)) }
        }
        g.color = CsiqTheme.inkMuted
        g.drawString(geometry, plotX + plotWidth - g.fontMetrics.stringWidth(geometry), y)
        g.font = previous
    }
}
