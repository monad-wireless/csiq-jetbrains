package io.monadcount.csiq.ide.ui

import io.monadcount.csiq.model.WaterfallLayer
import io.monadcount.csiq.model.WaterfallWindow
import java.awt.image.BufferedImage
import kotlin.math.ln

/**
 * Turns amplitude into pixels.
 *
 * Separated from the panel so the mapping can be checked without a screen: it
 * is the step where a wrong decision, such as painting a gap as a low value or
 * clipping on an outlier, produces a picture that looks entirely plausible and
 * is wrong.
 */
object WaterfallImage {

    /** The whole capture, downsampled, one tone geometry and one chain. */
    fun fromLayer(layer: WaterfallLayer, colorMap: ColorMap, logScale: Boolean): BufferedImage {
        val normaliser = Normaliser.forLayer(layer, logScale)
        val img = BufferedImage(layer.columns, layer.toneCount, BufferedImage.TYPE_INT_ARGB)
        val row = IntArray(layer.columns)
        for (tone in 0 until layer.toneCount) {
            for (col in 0 until layer.columns) {
                row[col] = if (layer.isGap(col)) {
                    ColorMap.GAP_ARGB
                } else {
                    colorMap.argb(normaliser.normalise(layer.mean(col, tone)))
                }
            }
            // Low subcarriers at the bottom, the way a spectrum is read.
            img.setRGB(0, layer.toneCount - 1 - tone, layer.columns, 1, row, 0, layer.columns)
        }
        return img
    }

    /** An exact image over a bounded record range. */
    fun fromWindow(window: WaterfallWindow, colorMap: ColorMap, logScale: Boolean): BufferedImage {
        val normaliser = Normaliser.forWindow(window, logScale)
        val width = window.rowCount.coerceAtLeast(1)
        val img = BufferedImage(width, window.toneCount, BufferedImage.TYPE_INT_ARGB)
        val row = IntArray(width)
        for (tone in 0 until window.toneCount) {
            for (col in 0 until window.rowCount) {
                val v = window.amplitude[col * window.toneCount + tone]
                row[col] = if (v.isNaN()) ColorMap.GAP_ARGB else colorMap.argb(normaliser.normalise(v))
            }
            img.setRGB(0, window.toneCount - 1 - tone, width, 1, row, 0, width)
        }
        return img
    }

    /**
     * Maps amplitude onto the colour map.
     *
     * The upper bound is a high percentile rather than the maximum, because a
     * single spike would otherwise flatten the whole image into one shade.
     * Amplitude is AGC-normalised, so these numbers carry the channel's shape
     * and not an absolute level - which is why the colour key is labelled |H|
     * and never dBm.
     */
    class Normaliser(private val lo: Float, private val hi: Float, private val log: Boolean) {
        private val span = (hi - lo).takeIf { it > 0f } ?: 1f

        fun normalise(v: Float): Float {
            if (v.isNaN()) return Float.NaN
            return if (log) {
                val a = ln((v.coerceAtLeast(EPS) / lo.coerceAtLeast(EPS)).toDouble())
                val b = ln((hi.coerceAtLeast(EPS) / lo.coerceAtLeast(EPS)).toDouble())
                if (b <= 0) 0f else (a / b).coerceIn(0.0, 1.0).toFloat()
            } else {
                ((v - lo) / span).coerceIn(0f, 1f)
            }
        }

        companion object {
            private const val EPS = 1e-3f

            fun forLayer(l: WaterfallLayer, log: Boolean): Normaliser {
                val samples = ArrayList<Float>(4096)
                val colStep = (l.columns / 64).coerceAtLeast(1)
                val toneStep = (l.toneCount / 32).coerceAtLeast(1)
                var col = 0
                while (col < l.columns) {
                    if (!l.isGap(col)) {
                        var tone = 0
                        while (tone < l.toneCount) {
                            val v = l.mean(col, tone)
                            if (!v.isNaN() && v > 0f) samples.add(v)
                            tone += toneStep
                        }
                    }
                    col += colStep
                }
                return fromSamples(samples, log)
            }

            fun forWindow(w: WaterfallWindow, log: Boolean): Normaliser {
                val samples = ArrayList<Float>(4096)
                val step = (w.amplitude.size / 4096).coerceAtLeast(1)
                var i = 0
                while (i < w.amplitude.size) {
                    val v = w.amplitude[i]
                    if (!v.isNaN() && v > 0f) samples.add(v)
                    i += step
                }
                return fromSamples(samples, log)
            }

            private fun fromSamples(samples: MutableList<Float>, log: Boolean): Normaliser {
                if (samples.isEmpty()) return Normaliser(0f, 1f, log)
                samples.sort()
                val lo = samples[(samples.size * 0.02).toInt().coerceIn(0, samples.size - 1)]
                val hi = samples[(samples.size * 0.99).toInt().coerceIn(0, samples.size - 1)]
                return Normaliser(lo, if (hi > lo) hi else lo * 2, log)
            }
        }
    }
}
