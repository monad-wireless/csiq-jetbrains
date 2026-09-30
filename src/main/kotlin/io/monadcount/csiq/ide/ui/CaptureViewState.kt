package io.monadcount.csiq.ide.ui

import io.monadcount.csiq.format.fmt
import io.monadcount.csiq.model.Capture
import io.monadcount.csiq.model.TimeAxis
import io.monadcount.csiq.model.TimeAxisFormatter
import io.monadcount.csiq.model.TimeAxisMode
import java.time.ZoneId

/**
 * What the views agree on: which record, which chain, which tone geometry.
 *
 * One state object rather than one per view, so selecting a record in the table
 * moves the spectrum and the impulse response with it and the crosshair in the
 * waterfall follows. A view that disagreed with another about which record is
 * on screen would be worse than no view at all.
 */
class CaptureViewState(val capture: Capture) {

    fun interface Listener {
        fun stateChanged(what: Change)
    }

    enum class Change { SELECTION, GEOMETRY, SCALE, ZOOM, TIME }

    private val listeners = ArrayList<Listener>()

    /** The clocks, shared so every view labels time the same way. */
    val time: TimeAxis = TimeAxis(capture.index, capture.sidecar)

    /**
     * Which clock the axis shows.
     *
     * Defaults to the wall clock when the capture carries one, because an
     * occupancy measurement is read against the hour of the day it happened
     * in. A capture with no absolute time falls back to elapsed rather than
     * inventing an origin.
     */
    var timeMode: TimeAxisMode =
        if (time.hasAbsoluteTime) TimeAxisMode.WALL_CLOCK else TimeAxisMode.ELAPSED
        set(value) {
            val allowed = if (!time.hasAbsoluteTime) TimeAxisMode.ELAPSED else value
            if (field != allowed) { field = allowed; fire(Change.TIME) }
        }

    /** The zone absolute times are printed in. Always named on the axis. */
    var zone: ZoneId = ZoneId.systemDefault()
        set(value) {
            if (field != value) { field = value; fire(Change.TIME) }
        }

    /** A formatter matched to the span currently on screen. */
    fun formatter(spanSeconds: Double): TimeAxisFormatter = TimeAxisFormatter(zone, spanSeconds)

    /**
     * The label for one record under the current mode.
     *
     * Falls back to elapsed seconds whenever the chosen clock has nothing for
     * that record, rather than printing a fabricated instant.
     */
    fun timeLabel(row: Int, formatter: TimeAxisFormatter): String {
        val nanos = time.nanosFor(row, timeMode)
        return if (nanos != null) formatter.tick(nanos) else "%.3f s".fmt(time.elapsedSeconds(row))
    }

    var selectedRow: Int = 0
        set(value) {
            val clamped = value.coerceIn(0, (capture.recordCount - 1).coerceAtLeast(0))
            if (field != clamped) {
                field = clamped
                fire(Change.SELECTION)
            }
        }

    var chain: Int = 0
        set(value) {
            if (field != value) { field = value; fire(Change.GEOMETRY) }
        }

    /** Which tone geometry the waterfall shows. Records of other geometries are gaps. */
    var toneCount: Int = capture.health.toneCounts.firstOrNull()?.first ?: 0
        set(value) {
            if (field != value) { field = value; fire(Change.GEOMETRY) }
        }

    var logScale: Boolean = true
        set(value) {
            if (field != value) { field = value; fire(Change.SCALE) }
        }

    var colorMap: ColorMap = ColorMap.VIRIDIS
        set(value) {
            if (field != value) { field = value; fire(Change.SCALE) }
        }

    /** Detrend the phase plot by removing the linear fit. A display aid only. */
    var detrendPhase: Boolean = true
        set(value) {
            if (field != value) { field = value; fire(Change.SCALE) }
        }

    /** First record of the zoom window, or -1 when the overview is shown. */
    var zoomFirstRow: Int = -1
        private set

    /** Records in the zoom window. */
    var zoomRowCount: Int = 0
        private set

    val isZoomed: Boolean get() = zoomFirstRow >= 0

    fun zoomTo(first: Int, count: Int) {
        val f = first.coerceIn(0, (capture.recordCount - 1).coerceAtLeast(0))
        val c = count.coerceIn(1, capture.recordCount - f)
        if (f != zoomFirstRow || c != zoomRowCount) {
            zoomFirstRow = f
            zoomRowCount = c
            fire(Change.ZOOM)
        }
    }

    fun resetZoom() {
        if (zoomFirstRow != -1) {
            zoomFirstRow = -1
            zoomRowCount = 0
            fire(Change.ZOOM)
        }
    }

    fun addListener(l: Listener) {
        listeners.add(l)
    }

    private fun fire(what: Change) {
        for (l in listeners.toList()) l.stateChanged(what)
    }
}
