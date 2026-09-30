package io.monadcount.csiq.model

import io.monadcount.csiq.format.ByteSource
import io.monadcount.csiq.format.CsiRecord
import io.monadcount.csiq.format.CsiqReader
import io.monadcount.csiq.format.TlvCodec

/**
 * A downsampled amplitude image over the whole capture, for one tone count and
 * one chain.
 *
 * The tone axis changes between records - a legacy frame carries 52 tones, an
 * HT frame 56, an HE20 frame 242 - so there is no single grid a whole ambient
 * capture fits on. Rather than interpolate between geometries, which would
 * invent subcarriers, the overview keeps one image per tone count and the view
 * shows the one the operator selects.
 */
class WaterfallLayer(
    val toneCount: Int,
    val chain: Int,
    val columns: Int,
) {
    /** Column-major sums: `sum[col * toneCount + tone]`. */
    private val sum = FloatArray(columns * toneCount)

    /** Records accumulated into each column. */
    val columnCount = IntArray(columns)

    /** First and last index row that landed in each column, or -1. */
    val firstRow = IntArray(columns) { -1 }
    val lastRow = IntArray(columns) { -1 }

    var maxAmplitude: Float = 0f
        private set

    var records: Int = 0
        private set

    fun accumulate(column: Int, row: Int, amplitude: FloatArray) {
        if (column !in 0 until columns) return
        val base = column * toneCount
        val n = minOf(toneCount, amplitude.size)
        for (t in 0 until n) {
            val v = amplitude[t]
            sum[base + t] += v
            if (v > maxAmplitude) maxAmplitude = v
        }
        columnCount[column]++
        if (firstRow[column] < 0) firstRow[column] = row
        lastRow[column] = row
        records++
    }

    /** Mean amplitude in a cell, or NaN when nothing landed there. */
    fun mean(column: Int, tone: Int): Float {
        val c = columnCount[column]
        if (c == 0) return Float.NaN
        return sum[column * toneCount + tone] / c
    }

    /** True when no record at all landed in this column. */
    fun isGap(column: Int): Boolean = columnCount[column] == 0
}

/**
 * Builds every [WaterfallLayer] the capture needs, in the same single pass that
 * builds the index.
 *
 * Columns are assigned by byte position, because the total byte length is known
 * up front and the record count is not. Each column also remembers which index
 * rows fell into it, so the axis can afterwards be labelled in records or in
 * seconds without a second pass and without approximation.
 */
class WaterfallBuilder(
    private val firstRecordOffset: Long,
    private val totalLength: Long,
    val columns: Int = DEFAULT_COLUMNS,
    private val maxLayers: Int = 6,
    private val chainsToKeep: Int = 2,
) {
    private val layers = LinkedHashMap<Long, WaterfallLayer>()
    private val span: Long = (totalLength - firstRecordOffset).coerceAtLeast(1)

    fun columnOf(offset: Long): Int {
        val c = ((offset - firstRecordOffset) * columns / span).toInt()
        return c.coerceIn(0, columns - 1)
    }

    fun accept(offset: Long, row: Int, record: CsiRecord) {
        if (!record.geometryConsistent || record.isAllZero()) return
        val column = columnOf(offset)
        val keep = minOf(chainsToKeep, record.chains)
        for (chain in 0 until keep) {
            val key = key(record.ntone, chain)
            var layer = layers[key]
            if (layer == null) {
                if (layers.size >= maxLayers) continue
                layer = WaterfallLayer(record.ntone, chain, columns)
                layers[key] = layer
            }
            record.chainAmplitude(chain)?.let { layer.accumulate(column, row, it) }
        }
    }

    /** Layers ordered by how many records they carry, most first. */
    fun build(): List<WaterfallLayer> = layers.values.sortedByDescending { it.records }

    private fun key(toneCount: Int, chain: Int): Long = (toneCount.toLong() shl 8) or chain.toLong()

    companion object {
        /**
         * Overview width.
         *
         * Wide enough that a 4K editor tab never upsamples the image, narrow
         * enough that six layers of 996 tones stay under 50 MB.
         */
        const val DEFAULT_COLUMNS: Int = 1600
    }
}

/** An exact amplitude image over a bounded range of records, read on demand. */
class WaterfallWindow(
    val firstRow: Int,
    val rowCount: Int,
    val toneCount: Int,
    val chain: Int,
    /** `amplitude[column * toneCount + tone]`, NaN where the record was skipped. */
    val amplitude: FloatArray,
    val maxAmplitude: Float,
    /** Index rows the columns map to, so a click resolves to a record. */
    val rowOf: IntArray,
) {
    companion object {
        /**
         * Most records a zoomed window will read.
         *
         * Above this the overview is shown instead. The cap exists because the
         * read is one contiguous span, and a span of a hundred thousand records
         * is tens of megabytes over a network filesystem.
         */
        const val MAX_RECORDS: Int = 12_000

        /**
         * Read records `[first, first+count)` and build an exact image.
         *
         * One contiguous read: the index gives the byte offset of the first
         * record and the length of the last, so even a source with no random
         * access costs a single request.
         */
        fun load(
            source: ByteSource,
            index: CaptureIndex,
            first: Int,
            count: Int,
            toneCount: Int,
            chain: Int,
            cancelled: () -> Boolean = { false },
        ): WaterfallWindow {
            val n = count.coerceAtMost(index.size - first).coerceAtLeast(0)
            val amp = FloatArray(n * toneCount) { Float.NaN }
            val rowOf = IntArray(n) { first + it }
            var max = 0f
            if (n == 0) return WaterfallWindow(first, 0, toneCount, chain, amp, 0f, rowOf)

            val startOffset = index.offset[first]
            val endOffset = index.offset[first + n - 1] + index.framedLength[first + n - 1]
            var row = first
            CsiqReader.scan(source, startOffset) { framed ->
                if (cancelled()) return@scan false
                if (framed.offset >= endOffset) return@scan false
                val rec = try {
                    TlvCodec.decode(framed.payload)
                } catch (_: Exception) {
                    row++
                    return@scan true
                }
                if (rec.ntone == toneCount && rec.geometryConsistent && !rec.isAllZero()) {
                    rec.chainAmplitude(chain)?.let { a ->
                        val base = (row - first) * toneCount
                        for (t in 0 until minOf(toneCount, a.size)) {
                            amp[base + t] = a[t]
                            if (a[t] > max) max = a[t]
                        }
                    }
                }
                row++
                row < first + n
            }
            return WaterfallWindow(first, n, toneCount, chain, amp, max, rowOf)
        }
    }
}
