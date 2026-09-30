package io.monadcount.csiq.ide.ui

import com.intellij.util.ui.JBUI
import io.monadcount.csiq.format.CsiRecord
import io.monadcount.csiq.format.Csiq
import io.monadcount.csiq.format.Dsp
import io.monadcount.csiq.format.fmt
import java.awt.Graphics2D
import java.awt.Rectangle

/**
 * Channel impulse response, and the inter-chain conjugate product.
 *
 * These are the two views that catch a reader holding the bytes wrong.
 *
 *  * A real channel concentrates energy at early delays. The early-to-late
 *    energy ratio is printed as a status chip, and a value below one is drawn
 *    in the critical colour with a label, never colour alone: amplitude looks
 *    perfectly healthy while the channel has become anti-causal, which is
 *    physically impossible.
 *  * Raw phase is dominated by carrier frequency offset, which is common to
 *    both chains and cancels in `H_a * conj(H_b)`. That product is the primary
 *    observable on this hardware, and its dispersion is reported as a circular
 *    standard deviation, because an ordinary one is wrong for angles.
 */
class CirPanel(
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
        if (r == null || !r.geometryConsistent || r.isAllZero()) {
            drawEmptyState(
                g,
                when {
                    pending -> "Reading record ${state.selectedRow}"
                    r == null -> "No record"
                    r.isAllZero() -> "Record ${state.selectedRow} carries an all-zero CSI matrix"
                    else -> "Record ${state.selectedRow} does not match its declared geometry"
                },
            )
            return
        }

        val gap = CsiqTheme.gapL
        val half = (plotHeight - gap) / 2
        drawImpulse(g, r, Rectangle(plotX, plotY, plotWidth, half))
        drawConjugateProduct(g, r, Rectangle(plotX, plotY + half + gap, plotWidth, half))
    }

    private fun drawImpulse(g: Graphics2D, r: CsiRecord, area: Rectangle) {
        val responses = (0 until r.chains).mapNotNull { c ->
            r.chainComplex(c)?.let { c to Dsp.impulseResponse(it) }
        }
        if (responses.isEmpty()) return
        val max = responses.maxOf { it.second.max() }.coerceAtLeast(1e-6f)
        val taps = responses.first().second.size
        val shown = taps / 2
        val spacingKhz = r.toneSpacingKhz()
        val nsPerTap = spacingKhz?.let { 1e6 / (taps * it) }

        drawFrame(
            g,
            area,
            xTicks = if (nsPerTap != null) {
                ticks(0.0, shown * nsPerTap, 6) { "%.0f".fmt(it) }
            } else {
                ticks(0.0, shown.toDouble(), 6) { "%.0f".fmt(it) }
            },
            yTicks = ticks(0.0, 1.0, 3) { "%.1f".fmt(it) },
            xLabel = if (nsPerTap != null) {
                "delay, nanoseconds   (%.1f ns per tap)".fmt(nsPerTap)
            } else {
                "delay, taps   (subcarrier spacing unknown, so this axis has no time scale)"
            },
            yLabel = "|h| / max",
            title = "impulse response   only the first $shown of $taps taps: the rest mirror them",
        )

        for ((chain, cir) in responses) {
            g.color = CsiqTheme.seriesColor(chain)
            g.stroke = CsiqTheme.seriesStroke()
            drawSeries(g, area, shown) { (cir[it] / max).toDouble() }
        }

        // The causality verdict, as a chip per chain.
        var x = area.x
        val y = area.y + area.height - JBUI.scale(22)
        for ((chain, cir) in responses) {
            val ratio = Dsp.causalityRatio(cir)
            val suspicious = ratio != null && ratio < 1.0
            val text = when {
                ratio == null -> "chain $chain  early/late n/a"
                suspicious -> "chain $chain  early/late %.2f  ANTI-CAUSAL".fmt(ratio)
                else -> "chain $chain  early/late %.1f".fmt(ratio)
            }
            x += CsiqTheme.paintChip(
                g, x, y, text,
                accent = if (suspicious) CsiqTheme.statusCritical else CsiqTheme.seriesColor(chain),
                filled = suspicious,
            ) + CsiqTheme.gapS
        }
    }

    private fun drawConjugateProduct(g: Graphics2D, r: CsiRecord, area: Rectangle) {
        if (r.chains < 2) {
            drawFrame(g, area, title = "inter-chain conjugate product")
            g.color = CsiqTheme.inkMuted
            g.drawString(
                "Needs two chains. This record reports ${r.chains}.",
                area.x + CsiqTheme.gapS,
                area.y + area.height / 2,
            )
            return
        }
        val stale = r.rssi.withIndex().filter { it.value == Csiq.RSSI_NO_MEASUREMENT }.map { it.index }
        if (stale.isNotEmpty()) {
            drawFrame(g, area, title = "inter-chain conjugate product")
            g.color = CsiqTheme.inkMuted
            g.drawString(
                "Chain ${stale.joinToString()} reported no measurement, so the product would compare " +
                    "this frame against an older one.",
                area.x + CsiqTheme.gapS,
                area.y + area.height / 2,
            )
            return
        }

        val a = r.chainComplex(0) ?: return
        val b = r.chainComplex(1) ?: return
        val angles = Dsp.argument(Dsp.conjugateProduct(a, b))
        val sigma = Dsp.circularStdDev(angles)

        drawFrame(
            g,
            area,
            xTicks = ticks(0.0, angles.size.toDouble(), 7) { "%.0f".fmt(it) },
            yTicks = listOf(Tick(0.0, "-pi"), Tick(0.5, "0"), Tick(1.0, "+pi")),
            xLabel = "subcarrier",
            yLabel = "radians",
            title = "inter-chain conjugate product   arg(H0 x conj(H1)) - carrier frequency offset cancels here",
        )
        g.color = CsiqTheme.seriesColor(2)
        g.stroke = CsiqTheme.seriesStroke()
        drawSeries(g, area, angles.size) { (angles[it] + Math.PI) / (2 * Math.PI) }

        sigma?.let {
            CsiqTheme.paintChip(
                g,
                area.x,
                area.y + area.height - JBUI.scale(22),
                "circular sigma %.1f degrees".fmt(Math.toDegrees(it)),
                accent = CsiqTheme.seriesColor(2),
            )
        }
    }
}
