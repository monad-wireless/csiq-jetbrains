package io.monadcount.csiq.format

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The signal processing the viewer needs, and nothing more.
 *
 * CSIQ is deliberately not a signal-processing format: no filtering,
 * calibration or phase sanitisation is applied to what is stored. These
 * functions are display transforms, applied at view time and never written
 * back, so what the editor shows can always be traced to the stored bytes.
 */
object Dsp {

    /** Unwrap a phase series into a continuous curve. */
    fun unwrapPhase(phase: FloatArray): FloatArray {
        if (phase.isEmpty()) return phase
        val out = FloatArray(phase.size)
        out[0] = phase[0]
        var offset = 0.0
        for (i in 1 until phase.size) {
            val d = phase[i] - phase[i - 1]
            if (d > Math.PI) offset -= 2 * Math.PI
            if (d < -Math.PI) offset += 2 * Math.PI
            out[i] = (phase[i] + offset).toFloat()
        }
        return out
    }

    /**
     * Linear-fit removal from an unwrapped phase series.
     *
     * Raw CSI phase is dominated by carrier frequency offset, which is a slope
     * across tones. Removing it is what makes a phase plot show the channel
     * instead of the oscillator. This is a display aid, not a calibration:
     * the residual it leaves is not a physically meaningful absolute phase.
     */
    fun detrend(series: FloatArray): FloatArray {
        val n = series.size
        if (n < 2) return series
        var sx = 0.0; var sy = 0.0; var sxx = 0.0; var sxy = 0.0
        for (i in 0 until n) {
            val x = i.toDouble(); val y = series[i].toDouble()
            sx += x; sy += y; sxx += x * x; sxy += x * y
        }
        val denom = n * sxx - sx * sx
        if (abs(denom) < 1e-12) return series
        val slope = (n * sxy - sx * sy) / denom
        val intercept = (sy - slope * sx) / n
        return FloatArray(n) { (series[it] - (slope * it + intercept)).toFloat() }
    }

    /**
     * The inter-chain conjugate product, `H_a * conj(H_b)`, as (re, im) pairs.
     *
     * This is the primary observable on this hardware. Raw phase is CFO
     * garbage, but the CFO is common to both chains and cancels in the product,
     * which leaves a quantity concentrated enough for AoA and Doppler work.
     * Both chains must carry a real measurement for it to mean anything.
     */
    fun conjugateProduct(chainA: FloatArray, chainB: FloatArray): FloatArray {
        val n = minOf(chainA.size, chainB.size) / 2
        val out = FloatArray(2 * n)
        for (t in 0 until n) {
            val ar = chainA[2 * t]; val ai = chainA[2 * t + 1]
            val br = chainB[2 * t]; val bi = chainB[2 * t + 1]
            out[2 * t] = ar * br + ai * bi
            out[2 * t + 1] = ai * br - ar * bi
        }
        return out
    }

    /**
     * Channel impulse response magnitude, by inverse DFT of the frequency
     * response.
     *
     * The tone grid is zero-padded to the next power of two, so the tap axis is
     * uniform and the transform is radix-2. Tap `k` corresponds to a delay of
     * `k / (size * spacing)` seconds.
     */
    fun impulseResponse(chainComplex: FloatArray): FloatArray {
        val n = chainComplex.size / 2
        if (n == 0) return FloatArray(0)
        val size = Integer.highestOneBit(maxOf(n - 1, 1)) * 2
        val re = DoubleArray(size)
        val im = DoubleArray(size)
        for (t in 0 until n) {
            re[t] = chainComplex[2 * t].toDouble()
            im[t] = chainComplex[2 * t + 1].toDouble()
        }
        inverseFft(re, im)
        return FloatArray(size) { hypot(re[it], im[it]).toFloat() }
    }

    /**
     * Ratio of energy in the early taps to energy in the late taps.
     *
     * The causality check the format spec documents. A real channel puts its
     * energy at early delays. On this hardware the correct byte order yields
     * about 21 and the imaginary/real swap inverts it to about 0.5, so a value
     * below 1 is the tell that a reader has the coefficient order wrong.
     *
     * Returns `null` when the response is too short or carries no energy.
     */
    fun causalityRatio(cir: FloatArray, taps: Int = 4): Double? {
        if (cir.size < 4 * taps) return null
        var early = 0.0
        var late = 0.0
        for (i in 0 until taps) early += cir[i].toDouble() * cir[i]
        for (i in cir.size - taps until cir.size) late += cir[i].toDouble() * cir[i]
        if (late <= 0.0 || early <= 0.0) return null
        return early / late
    }

    /** In-place radix-2 inverse FFT, unnormalised except by `1/n`. */
    private fun inverseFft(re: DoubleArray, im: DoubleArray) {
        val n = re.size
        if (n <= 1) return
        // bit reversal
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j or bit
            if (i < j) {
                var t = re[i]; re[i] = re[j]; re[j] = t
                t = im[i]; im[i] = im[j]; im[j] = t
            }
        }
        var len = 2
        while (len <= n) {
            val ang = 2 * Math.PI / len // positive sign: inverse transform
            val wr = cos(ang)
            val wi = sin(ang)
            var i = 0
            while (i < n) {
                var cwr = 1.0
                var cwi = 0.0
                for (k in 0 until len / 2) {
                    val ur = re[i + k]; val ui = im[i + k]
                    val vr = re[i + k + len / 2] * cwr - im[i + k + len / 2] * cwi
                    val vi = re[i + k + len / 2] * cwi + im[i + k + len / 2] * cwr
                    re[i + k] = ur + vr; im[i + k] = ui + vi
                    re[i + k + len / 2] = ur - vr; im[i + k + len / 2] = ui - vi
                    val nwr = cwr * wr - cwi * wi
                    cwi = cwr * wi + cwi * wr
                    cwr = nwr
                }
                i += len
            }
            len = len shl 1
        }
        for (i in 0 until n) {
            re[i] /= n
            im[i] /= n
        }
    }

    /** Magnitude of an interleaved (re, im) series. */
    fun magnitude(complex: FloatArray): FloatArray =
        FloatArray(complex.size / 2) { hypot(complex[2 * it].toDouble(), complex[2 * it + 1].toDouble()).toFloat() }

    /** Argument of an interleaved (re, im) series, radians. */
    fun argument(complex: FloatArray): FloatArray =
        FloatArray(complex.size / 2) { atan2(complex[2 * it + 1].toDouble(), complex[2 * it].toDouble()).toFloat() }

    /**
     * Circular standard deviation of a set of angles, radians.
     *
     * The right dispersion measure for phase: an ordinary standard deviation
     * over angles near the wrap point reports a large spread for a tight
     * cluster.
     */
    fun circularStdDev(angles: FloatArray): Double? {
        if (angles.isEmpty()) return null
        var c = 0.0
        var s = 0.0
        for (a in angles) { c += cos(a.toDouble()); s += sin(a.toDouble()) }
        val r = sqrt(c * c + s * s) / angles.size
        if (r <= 0.0 || r >= 1.0) return 0.0
        return sqrt(-2.0 * kotlin.math.ln(r))
    }
}

/**
 * Unwraps the 320 MHz baseband clock.
 *
 * `ftm` wraps every 13.42 seconds. Records arrive in order, so a value lower
 * than its predecessor implies exactly one wrap. Feed records in file order.
 */
class FtmUnwrapper {
    private var last: Long = -1
    private var epoch: Long = 0

    /** Unwrapped tick count for the next record's raw `ftm`. */
    fun next(ftm: Int): Long {
        val v = ftm.toLong() and 0xFFFF_FFFFL
        if (last >= 0 && v < last) epoch += 1L shl 32
        last = v
        return epoch + v
    }

    companion object {
        /** Seconds per baseband tick: the clock runs at 320 MHz. */
        const val SECONDS_PER_TICK: Double = 1.0 / 320_000_000.0
    }
}
