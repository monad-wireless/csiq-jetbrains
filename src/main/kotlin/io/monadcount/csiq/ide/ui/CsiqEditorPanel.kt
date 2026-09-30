package io.monadcount.csiq.ide.ui

import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.tabs.JBTabsFactory
import com.intellij.ui.tabs.TabInfo
import com.intellij.util.ui.JBUI
import io.monadcount.csiq.format.ScanEnd
import io.monadcount.csiq.format.fmt
import io.monadcount.csiq.model.Capture
import io.monadcount.csiq.model.TimeAxis
import io.monadcount.csiq.model.TimeAxisMode
import java.awt.BorderLayout
import java.awt.Component
import java.awt.FlowLayout
import java.time.ZoneId
import java.time.ZoneOffset
import javax.swing.BoxLayout
import javax.swing.DefaultListCellRenderer
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JPanel

/**
 * The editor: the capture's identity, a toolbar of what to show, six views, and
 * a status line that never lets a caveat about the data go unsaid.
 */
class CsiqEditorPanel(
    project: Project,
    private val capture: Capture,
    provenance: String,
    siblings: List<String>,
    parent: Disposable,
) : JPanel(BorderLayout()) {

    private val state = CaptureViewState(capture)
    private val loader = RecordLoader(capture)
    private val status = JBLabel()
    private val warnings = JBLabel()

    val preferredFocus: JComponent

    init {
        val tabs = JBTabsFactory.createTabs(project, parent)
        val waterfall = WaterfallPanel(state)
        tabs.addTab(TabInfo(waterfall).setText("Waterfall"))
        tabs.addTab(TabInfo(SpectrumPanel(state, loader)).setText("Spectrum"))
        tabs.addTab(TabInfo(CirPanel(state, loader)).setText("Impulse response"))
        tabs.addTab(TabInfo(RecordsPanel(state)).setText("Records"))
        tabs.addTab(TabInfo(TlvPanel(state, loader)).setText("Fields and bytes"))
        tabs.addTab(TabInfo(SessionPanel(capture, provenance, siblings)).setText("Session"))

        val north = JPanel()
        north.layout = BoxLayout(north, BoxLayout.Y_AXIS)
        north.add(CaptureHeader(capture))
        north.add(buildToolbar())

        add(north, BorderLayout.NORTH)
        add(tabs.component, BorderLayout.CENTER)
        add(buildStatusBar(), BorderLayout.SOUTH)

        preferredFocus = waterfall
        state.addListener { updateStatus() }
        updateStatus()
        updateWarnings()
    }

    private fun buildToolbar(): JComponent {
        val bar = JPanel(FlowLayout(FlowLayout.LEFT, CsiqTheme.gapS, CsiqTheme.gapXs))

        val geometries = capture.health.toneCounts
        if (geometries.size > 1) {
            bar.add(caption("subcarriers"))
            val combo = ComboBox(geometries.map { it.first }.toTypedArray())
            combo.selectedItem = state.toneCount
            combo.renderer = renderer<Int> { tones ->
                val count = geometries.firstOrNull { it.first == tones }?.second ?: 0
                "$tones   (%,d records)".fmt(count)
            }
            combo.addActionListener { (combo.selectedItem as? Int)?.let { state.toneCount = it } }
            bar.add(combo)
        }

        val maxChains = (0 until capture.index.size).take(2000)
            .maxOfOrNull { (capture.index.nrx[it].toInt() and 0xFF) * (capture.index.ntx[it].toInt() and 0xFF) }
            ?.coerceIn(1, 4) ?: 1
        if (maxChains > 1) {
            bar.add(caption("chain"))
            val combo = ComboBox((0 until maxChains).toList().toTypedArray())
            combo.selectedItem = state.chain
            combo.addActionListener { (combo.selectedItem as? Int)?.let { state.chain = it } }
            bar.add(combo)
        }

        // The time axis. Offered only when the capture carries an absolute
        // time: with no anchor, elapsed is the only honest axis and a combo
        // that let you pick a clock which does not exist would be a lie.
        if (state.time.hasAbsoluteTime) {
            bar.add(caption("time"))
            val modes = ComboBox(TimeAxisMode.entries.toTypedArray())
            modes.selectedItem = state.timeMode
            modes.renderer = renderer<TimeAxisMode> { it.label }
            modes.toolTipText = TimeAxisMode.entries.joinToString("\n") { "${it.label}: ${it.description}" }
            modes.addActionListener { (modes.selectedItem as? TimeAxisMode)?.let { m -> state.timeMode = m } }
            bar.add(modes)

            val utc = JBCheckBox("UTC", state.zone == ZoneOffset.UTC)
            utc.toolTipText =
                "The archive stamps UTC. Local time is what an occupancy measurement is read against, " +
                    "so the axis always names the zone it is printing."
            utc.addActionListener {
                state.zone = if (utc.isSelected) ZoneOffset.UTC else ZoneId.systemDefault()
            }
            bar.add(utc)
        }

        bar.add(caption("colour"))
        val maps = ComboBox(ColorMap.ALL.toTypedArray())
        maps.selectedItem = state.colorMap
        maps.renderer = renderer<ColorMap> { it.displayName }
        maps.addActionListener { (maps.selectedItem as? ColorMap)?.let { m -> state.colorMap = m } }
        bar.add(maps)

        val log = JBCheckBox("log amplitude", state.logScale)
        log.toolTipText = "A log scale reveals the nulls a linear one flattens."
        log.addActionListener { state.logScale = log.isSelected }
        bar.add(log)

        val detrend = JBCheckBox("detrend phase", state.detrendPhase)
        detrend.toolTipText =
            "Remove the linear fit across subcarriers, which is carrier frequency offset. " +
                "A display aid, not a calibration."
        detrend.addActionListener { state.detrendPhase = detrend.isSelected }
        bar.add(detrend)

        val reset = JButton("Whole capture")
        reset.toolTipText = "Leave the zoom window. Ctrl-scroll on the waterfall zooms, double-click resets."
        reset.addActionListener { state.resetZoom() }
        bar.add(reset)

        return bar
    }

    private fun caption(text: String): JComponent = JBLabel(text).apply {
        foreground = CsiqTheme.inkSecondary
        font = CsiqTheme.captionFont(font)
    }

    private fun buildStatusBar(): JComponent {
        val panel = JPanel(BorderLayout())
        panel.border = JBUI.Borders.empty(CsiqTheme.gapXs, CsiqTheme.gapM)
        status.font = CsiqTheme.captionFont(status.font)
        warnings.font = CsiqTheme.captionFont(warnings.font)
        warnings.foreground = CsiqTheme.statusWarning
        panel.add(status, BorderLayout.WEST)
        panel.add(warnings, BorderLayout.EAST)
        return panel
    }

    private fun updateStatus() {
        val index = capture.index
        val row = state.selectedRow
        val parts = ArrayList<String>(5)
        if (index.size > 0) {
            parts += "record %,d of %,d".fmt(row, index.size - 1)
            state.time.nanosFor(row, state.timeMode)?.let { parts += state.formatter(0.0).full(it) }
            parts += "%.3f s".fmt(state.time.elapsedSeconds(row))
            parts += index.srcMacString(row)
            parts += index.modulationOf(row).label
        }
        if (state.isZoomed) parts += "zoom %,d records".fmt(state.zoomRowCount)
        status.text = parts.joinToString("     ")
    }

    /**
     * Caveats that change how a number may be used.
     *
     * They sit in the status bar rather than in a tab the reader might not
     * open. Each is spelled out in words, never signalled by colour alone.
     */
    private fun updateWarnings() {
        val h = capture.health
        val out = ArrayList<String>(4)
        (capture.scanEnd as? ScanEnd.Truncated)?.let { out += "truncated at byte ${it.atOffset}" }
        (capture.scanEnd as? ScanEnd.Desync)?.let { out += "desynchronised at byte ${it.atOffset}" }
        capture.recordCountMismatch?.let { out += "record count differs from the sidecar by $it" }

        // A clock that moved under the capture. Oscillator drift between the
        // two clocks measures in milliseconds over hours; a jump of seconds is
        // an NTP step, and it leaves no other mark in the file.
        state.time.clockDivergenceSeconds?.let { d ->
            if (d >= TimeAxis.STEP_THRESHOLD_SECONDS) {
                out += "the host clock and the baseband clock disagree by %.1f s".fmt(d)
            }
        }
        if (state.time.ftmUnwrapSuspect) {
            out += "a %.1f s gap exceeds the baseband clock's wrap, so elapsed time may be short"
                .format(state.time.largestGapSeconds)
        }
        if (h.positiveRssiRecords > 0) out += "%,d records carry positive RSSI".fmt(h.positiveRssiRecords)
        if (h.allZeroShare > 0.02) out += "%.0f %% all-zero CSI".fmt(h.allZeroShare * 100)
        if (h.staleChainShare > 0.02) out += "%.0f %% stale chains".fmt(h.staleChainShare * 100)
        warnings.text = out.joinToString("     ")
        warnings.foreground = if (
            capture.scanEnd !is ScanEnd.Clean ||
            capture.recordCountMismatch != null ||
            (state.time.clockDivergenceSeconds ?: 0.0) >= TimeAxis.STEP_THRESHOLD_SECONDS
        ) {
            CsiqTheme.statusCritical
        } else {
            CsiqTheme.statusWarning
        }
    }

    private inline fun <T> renderer(crossinline text: (T) -> String): DefaultListCellRenderer =
        object : DefaultListCellRenderer() {
            @Suppress("UNCHECKED_CAST")
            override fun getListCellRendererComponent(
                list: JList<*>?,
                value: Any?,
                index: Int,
                isSelected: Boolean,
                cellHasFocus: Boolean,
            ): Component {
                val label = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
                if (value != null) (label as JLabel).text = text(value as T)
                return label
            }
        }
}
