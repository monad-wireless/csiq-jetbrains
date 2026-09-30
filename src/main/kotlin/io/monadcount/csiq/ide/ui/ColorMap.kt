package io.monadcount.csiq.ide.ui

import java.awt.Color

/**
 * A sequential colour map for amplitude.
 *
 * All three are perceptually uniform or monotonic in lightness, so equal steps
 * in amplitude look like equal steps on screen and no false boundary appears
 * where the hue happens to turn. A rainbow map does the opposite, which is why
 * none is offered.
 */
class ColorMap(val displayName: String, private val stops: Array<FloatArray>) {

    private val lut: IntArray = IntArray(LUT_SIZE) { i ->
        val t = i.toFloat() / (LUT_SIZE - 1)
        val scaled = t * (stops.size - 1)
        val lo = scaled.toInt().coerceIn(0, stops.size - 1)
        val hi = (lo + 1).coerceAtMost(stops.size - 1)
        val f = scaled - lo
        val r = ((stops[lo][0] + (stops[hi][0] - stops[lo][0]) * f) * 255).toInt().coerceIn(0, 255)
        val g = ((stops[lo][1] + (stops[hi][1] - stops[lo][1]) * f) * 255).toInt().coerceIn(0, 255)
        val b = ((stops[lo][2] + (stops[hi][2] - stops[lo][2]) * f) * 255).toInt().coerceIn(0, 255)
        (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    /** Packed ARGB for a normalised value. Out-of-range values clamp. */
    fun argb(t: Float): Int {
        if (t.isNaN()) return GAP_ARGB
        val i = (t * (LUT_SIZE - 1)).toInt().coerceIn(0, LUT_SIZE - 1)
        return lut[i]
    }

    fun color(t: Float): Color = Color(argb(t), true)

    companion object {
        private const val LUT_SIZE = 512

        /**
         * The colour of a cell no record landed in.
         *
         * Transparent, not black. A gap is an absence of measurement and must
         * not read as a low amplitude.
         */
        const val GAP_ARGB: Int = 0x00000000

        val VIRIDIS = ColorMap(
            "Viridis",
            arrayOf(
                floatArrayOf(0.267f, 0.005f, 0.329f),
                floatArrayOf(0.283f, 0.141f, 0.458f),
                floatArrayOf(0.254f, 0.265f, 0.530f),
                floatArrayOf(0.207f, 0.372f, 0.553f),
                floatArrayOf(0.164f, 0.471f, 0.558f),
                floatArrayOf(0.128f, 0.567f, 0.551f),
                floatArrayOf(0.135f, 0.659f, 0.518f),
                floatArrayOf(0.267f, 0.749f, 0.441f),
                floatArrayOf(0.478f, 0.821f, 0.318f),
                floatArrayOf(0.741f, 0.873f, 0.150f),
                floatArrayOf(0.993f, 0.906f, 0.144f),
            ),
        )

        val MAGMA = ColorMap(
            "Magma",
            arrayOf(
                floatArrayOf(0.001f, 0.000f, 0.014f),
                floatArrayOf(0.113f, 0.066f, 0.224f),
                floatArrayOf(0.272f, 0.109f, 0.401f),
                floatArrayOf(0.438f, 0.146f, 0.463f),
                floatArrayOf(0.601f, 0.190f, 0.470f),
                floatArrayOf(0.768f, 0.233f, 0.436f),
                floatArrayOf(0.902f, 0.322f, 0.376f),
                floatArrayOf(0.972f, 0.482f, 0.395f),
                floatArrayOf(0.995f, 0.652f, 0.497f),
                floatArrayOf(0.996f, 0.816f, 0.636f),
                floatArrayOf(0.987f, 0.991f, 0.749f),
            ),
        )

        val GREY = ColorMap(
            "Grey",
            arrayOf(
                floatArrayOf(0.05f, 0.05f, 0.06f),
                floatArrayOf(0.95f, 0.95f, 0.96f),
            ),
        )

        val ALL = listOf(VIRIDIS, MAGMA, GREY)

        fun byName(name: String): ColorMap = ALL.firstOrNull { it.displayName == name } ?: VIRIDIS
    }
}
