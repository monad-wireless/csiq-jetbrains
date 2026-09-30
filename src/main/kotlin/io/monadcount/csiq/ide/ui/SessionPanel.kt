package io.monadcount.csiq.ide.ui

import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.table.JBTable
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.ui.JBUI
import io.monadcount.csiq.format.JsonValue
import io.monadcount.csiq.format.Payload
import io.monadcount.csiq.format.ScanEnd
import io.monadcount.csiq.format.fmt
import io.monadcount.csiq.ide.CaptureSourceFactory
import io.monadcount.csiq.model.AnchorSource
import io.monadcount.csiq.model.Capture
import io.monadcount.csiq.model.TimeAxis
import io.monadcount.csiq.model.TimeAxisFormatter
import java.awt.BorderLayout
import java.time.ZoneId
import java.awt.Dimension
import java.awt.FlowLayout
import javax.swing.BoxLayout
import javax.swing.JPanel
import javax.swing.table.AbstractTableModel
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel

/**
 * What the capture says about itself.
 *
 * The tiles across the top are the numbers a reader needs before anything else.
 * The table under them is derived by counting rather than by reading the
 * session block, so it describes the bytes and not the capturer's intention.
 * The tree at the bottom is the session metadata verbatim: opaque to the
 * container and permissive in schema, so it is rendered as whatever it
 * happens to contain.
 */
class SessionPanel(
    private val capture: Capture,
    private val provenance: String,
    private val siblings: List<String>,
) : JPanel(BorderLayout()) {

    init {
        val top = JPanel()
        top.layout = BoxLayout(top, BoxLayout.Y_AXIS)
        top.border = JBUI.Borders.empty(CsiqTheme.gapM, CsiqTheme.gapM, CsiqTheme.gapS, CsiqTheme.gapM)
        top.add(tileRow())

        val facts = JBTable(FactsModel(buildFacts())).apply {
            rowHeight = JBUI.scale(21)
            setShowGrid(false)
            autoResizeMode = JBTable.AUTO_RESIZE_LAST_COLUMN
            columnModel.getColumn(0).preferredWidth = JBUI.scale(210)
            columnModel.getColumn(1).preferredWidth = JBUI.scale(190)
        }

        val lower = OnePixelSplitter(true, 0.58f)
        lower.firstComponent = JBScrollPane(facts)
        lower.secondComponent = JBScrollPane(sessionTree())

        add(top, BorderLayout.NORTH)
        add(lower, BorderLayout.CENTER)
    }

    private fun tileRow(): JPanel {
        val row = JPanel(FlowLayout(FlowLayout.LEFT, CsiqTheme.gapS, 0))
        row.isOpaque = false
        val h = capture.health

        row.add(
            StatTile(
                "records",
                "%,d".fmt(h.records),
                capture.recordCountMismatch?.let { d ->
                    "the sidecar claims %,d: %+,d".fmt(h.records + d, d)
                } ?: "indexed in ${capture.indexMillis} ms",
                if (capture.recordCountMismatch != null) CsiqTheme.Status.CRITICAL else CsiqTheme.Status.NEUTRAL,
            ),
        )
        val time = TimeAxis(capture.index, capture.sidecar)
        time.anchorEpochNanos?.let { anchor ->
            val f = TimeAxisFormatter(ZoneId.systemDefault(), h.ftmSpanSeconds)
            row.add(
                StatTile(
                    "started",
                    f.tick(anchor),
                    f.caption(anchor, anchor + (h.ftmSpanSeconds * 1e9).toLong()),
                ),
            )
        }
        row.add(
            StatTile(
                "duration",
                formatDuration(h.ftmSpanSeconds),
                "on the baseband clock, which an NTP step cannot move",
            ),
        )
        h.meanRateHz?.let {
            row.add(StatTile("mean rate", "%.0f Hz".fmt(it), "records per second"))
        }
        row.add(
            StatTile(
                "all-zero CSI",
                "%.1f %%".fmt(h.allZeroShare * 100),
                "exclude these from any amplitude statistic",
                when {
                    h.allZeroShare > 0.30 -> CsiqTheme.Status.CRITICAL
                    h.allZeroShare > 0.02 -> CsiqTheme.Status.WARNING
                    else -> CsiqTheme.Status.GOOD
                },
            ),
        )
        row.add(
            StatTile(
                "stale chains",
                "%.1f %%".fmt(h.staleChainShare * 100),
                "a chain at -127 dBm repeats an earlier frame",
                if (h.staleChainShare > 0.02) CsiqTheme.Status.WARNING else CsiqTheme.Status.GOOD,
            ),
        )
        row.add(
            StatTile(
                "dropped reports",
                "%,d".fmt(h.seqDroppedReports),
                "${h.seqGaps} gaps in the driver's report counter",
                if (h.seqGaps > 0) CsiqTheme.Status.WARNING else CsiqTheme.Status.GOOD,
            ),
        )
        row.add(
            StatTile(
                "stream ended",
                when (capture.scanEnd) {
                    is ScanEnd.Clean -> "clean"
                    is ScanEnd.Truncated -> "truncated"
                    is ScanEnd.Desync -> "desynced"
                    is ScanEnd.BadRecord -> "bad record"
                },
                when (val e = capture.scanEnd) {
                    is ScanEnd.Clean -> "the file ends on a record boundary"
                    is ScanEnd.Truncated -> "cut at byte ${e.atOffset}"
                    is ScanEnd.Desync -> "stopped at byte ${e.atOffset}"
                    is ScanEnd.BadRecord -> "stopped at byte ${e.atOffset}"
                },
                if (capture.scanEnd is ScanEnd.Clean) CsiqTheme.Status.GOOD else CsiqTheme.Status.CRITICAL,
            ),
        )
        row.preferredSize = Dimension(row.preferredSize.width, JBUI.scale(92))
        return row
    }

    private fun formatDuration(seconds: Double): String = when {
        seconds < 90 -> "%.1f s".fmt(seconds)
        seconds < 5400 -> "%.1f min".fmt(seconds / 60)
        else -> "%.2f h".fmt(seconds / 3600)
    }

    private class Fact(
        val name: String,
        val value: String,
        val note: String = "",
    )

    private fun buildFacts(): List<Fact> {
        val h = capture.health
        val s = capture.sidecar
        val out = ArrayList<Fact>(36)

        out += Fact("source", capture.source.displayName, provenance)
        out += Fact(
            "envelope",
            buildString {
                append(capture.payload.label)
                if (capture.compression.isCompressed) append(", ").append(capture.compression.label)
                if (capture.payload == Payload.CSIQ) capture.header?.let { append("  version ").append(it.version) }
            },
            if (capture.payload == Payload.RAW_DRIVER_STREAM) {
                "driver-coupled: a firmware revision can invalidate these offsets"
            } else {
                ""
            },
        )
        if (siblings.isNotEmpty()) {
            out += Fact(
                "also in this directory",
                siblings.joinToString(", "),
                "one directory per capture session, on disk and in the archive alike",
            )
        }

        // -- identity, from the sidecar -------------------------------------
        if (s != null) {
            out += Fact("session", s.sessionId ?: "-", s.schema ?: "")
            s.runId?.let { out += Fact("run", it, "") }
            s.experiment?.let { out += Fact("experiment", it, "") }
            s.tag?.let {
                out += Fact(
                    "profile tag",
                    it,
                    "copied from the inventory profile, so it names the profile and never the experiment",
                )
            }
            if (s.isSegment) {
                out += Fact(
                    "segment",
                    s.segmentNumber?.toString() ?: "yes",
                    "one segment of ${s.baseSessionId ?: "a longer capture"}; check the sidecar's " +
                        "started_at against the previous segment before treating it as this segment's start",
                )
            }
            out += Fact(
                "lifecycle",
                "${s.status ?: "-"}   ${s.startedAt ?: "?"} to ${s.endedAt ?: "?"}",
                if (capture.lifecycleIsStale) {
                    "the embedded block still says 'capturing'; it was written before csid 0.2.0 and is stale"
                } else {
                    "from metadata.json beside the capture"
                },
            )
            s.hostname?.let { out += Fact("node", it, s.csidVersion?.let { v -> "csid $v" } ?: "") }
            s.filterFingerprint?.let {
                out += Fact(
                    "filter fingerprint",
                    it,
                    when (it) {
                        "no-filter" -> "the radio filtered nothing"
                        "" -> "written before the filter group existed; not recorded"
                        else -> "two differently filtered captures cannot be pooled by accident"
                    },
                )
            }
        }

        // -- radio -----------------------------------------------------------
        out += Fact(
            "monitor width",
            h.monitorWidth.label,
            when {
                capture.payload == Payload.RAW_DRIVER_STREAM && s?.monitorWidth != null ->
                    "the raw header has no width field; this came from metadata.json"
                capture.payload == Payload.RAW_DRIVER_STREAM ->
                    "the raw header has no width field and no metadata.json was found beside it"
                else -> "a session constant; it bounds what the receiver could decode"
            },
        )
        s?.let { sc ->
            sc.channel?.let {
                out += Fact(
                    "channel",
                    "$it${sc.band?.let { b -> "   ${b} GHz" } ?: ""}",
                    sc.controlFreqMhz?.let { f -> "control frequency $f MHz" } ?: "",
                )
            }
            sc.intervalUs?.let {
                out += Fact(
                    "csi interval",
                    if (it == 0L) "0 us" else "$it us",
                    if (it == 0L) "uncapped: the radio reported as fast as it could" else "a clean rate cap",
                )
            }
        }

        // -- measured --------------------------------------------------------
        out += Fact("records indexed", "%,d".fmt(h.records), "counted by this reader")
        s?.summaryRecords?.let {
            out += Fact(
                "records claimed",
                "%,d".fmt(it),
                if (capture.recordCountMismatch == null) {
                    "matches"
                } else {
                    "differs by ${capture.recordCountMismatch}; the object may be truncated"
                },
            )
        }
        // -- the clocks ------------------------------------------------------
        val time = TimeAxis(capture.index, capture.sidecar)
        val formatter = TimeAxisFormatter(ZoneId.systemDefault(), h.ftmSpanSeconds)
        out += Fact(
            "time anchor",
            time.anchorEpochNanos?.let { formatter.full(it) } ?: "none",
            when (time.anchorSource) {
                AnchorSource.FIRST_RECORD ->
                    "the first record's own unix_ts_ns, measured on the same stream it anchors"
                AnchorSource.SIDECAR_STARTED_AT ->
                    "started_at from metadata.json; a segment's is not always that segment's own start"
                AnchorSource.NONE ->
                    "no absolute time in the capture or beside it, so only an elapsed axis is honest"
            },
        )
        time.clockDivergenceSeconds?.let { d ->
            out += Fact(
                "clock disagreement",
                if (d < 1.0) "%.0f ms".fmt(d * 1000) else "%.2f s".fmt(d),
                if (d >= TimeAxis.STEP_THRESHOLD_SECONDS) {
                    "too large for oscillator drift: the host clock was stepped during the capture"
                } else {
                    "consistent with oscillator drift; measured about 1 ppm on this fleet"
                },
            )
        }
        out += Fact(
            "largest record gap",
            "%.3f s".fmt(time.largestGapSeconds),
            if (time.ftmUnwrapSuspect) {
                "longer than the baseband clock's %.2f s wrap, so elapsed time may be short"
                    .format(TimeAxis.FTM_WRAP_SECONDS)
            } else {
                "within the baseband clock's %.2f s wrap, so unwrapping is safe"
                    .format(TimeAxis.FTM_WRAP_SECONDS)
            },
        )
        out += Fact("duration, baseband clock", "%.3f s".fmt(h.ftmSpanSeconds), "ftm, immune to an NTP step")
        h.wallSpanSeconds?.let {
            val drift = kotlin.math.abs(it - h.ftmSpanSeconds)
            out += Fact(
                "duration, host wallclock",
                "%.3f s".fmt(it),
                if (drift > 0.05 * h.ftmSpanSeconds.coerceAtLeast(1.0)) {
                    "disagrees with the baseband clock by more than 5 percent"
                } else {
                    "unix_ts_ns, which an NTP step moves"
                },
            )
        }
        out += Fact(
            "tone geometries",
            h.toneCounts.joinToString(", ") { "${it.first} x${it.second}" },
            if (h.toneCounts.size > 1) "an ambient channel interleaves PHY types frame by frame" else "",
        )
        out += Fact("distinct source MACs", "${h.distinctSourceMacs}", "")
        h.rssiRange?.let { (lo, hi) ->
            out += Fact("RSSI range", "$lo to $hi dBm", "the absolute reference; |H| is shape only")
        }
        out += Fact(
            "own transmissions",
            "%,d".fmt(h.ownTransmissionRecords),
            "no MONO_US, which marks a frame the capturing node sent itself",
        )
        if (h.geometryBadRecords > 0) {
            out += Fact(
                "geometry mismatches",
                "%,d".fmt(h.geometryBadRecords),
                "declared tones and chains do not match the CSI payload length",
            )
        }
        if (h.positiveRssiRecords > 0) {
            out += Fact(
                "positive RSSI",
                "%,d".fmt(h.positiveRssiRecords),
                "written before the 2026-07-22 sign corrigendum; re-derive with `csid export`",
            )
        }
        if (h.unknownTlvRecords > 0) {
            out += Fact(
                "unknown TLV codes",
                "%,d records".fmt(h.unknownTlvRecords),
                "the writer knew a field this reader does not; everything else decoded normally",
            )
        }
        out += Fact(
            "driver header kept",
            "%,d records".fmt(h.vendorHdrRecords),
            if (h.vendorHdrRecords > 0) {
                "lossless provenance: a field promoted later needs no re-capture"
            } else {
                "a field this format cannot name yet would need a re-capture"
            },
        )
        if (h.nodeStateRecords > 0) {
            out += Fact(
                "node-state ticks",
                "%,d".fmt(h.nodeStateRecords),
                "a sparse series, never a per-record column",
            )
        }
        out += Fact("file size", CaptureSourceFactory.formatBytes(capture.source.length), "")
        out += Fact(
            "random access",
            if (capture.source.randomAccess) "yes" else "no",
            if (capture.source.randomAccess) "" else "record reads re-read from the start of the stream",
        )
        return out
    }

    private fun sessionTree(): Tree {
        val embedded = capture.header?.session
        val sidecarJson = capture.sidecar?.json
        val root = DefaultMutableTreeNode("session metadata")

        if (sidecarJson != null) {
            val node = DefaultMutableTreeNode("metadata.json  (beside the capture)")
            addJson(node, sidecarJson)
            root.add(node)
        }
        val header = capture.header
        when {
            header?.session != null -> {
                val node = DefaultMutableTreeNode(
                    "embedded session block  (${header.sessionLength} bytes of UTF-8 JSON)",
                )
                addJson(node, header.session)
                root.add(node)
            }
            capture.payload == Payload.RAW_DRIVER_STREAM ->
                root.add(DefaultMutableTreeNode("a raw driver stream carries no session block"))
            header?.sessionParseError != null ->
                root.add(DefaultMutableTreeNode("session block did not parse: ${header.sessionParseError}"))
            else -> root.add(DefaultMutableTreeNode("no session block in this file"))
        }

        val tree = Tree(DefaultTreeModel(root)).apply {
            isRootVisible = true
            showsRootHandles = true
        }
        for (i in 0 until 4) tree.expandRow(i)
        return tree
    }

    private fun addJson(parent: DefaultMutableTreeNode, value: JsonValue, name: String? = null) {
        when (value) {
            is JsonValue.Obj -> {
                val node = if (name == null) parent else DefaultMutableTreeNode(name).also { parent.add(it) }
                value.fields.forEach { (k, v) -> addJson(node, v, k) }
            }
            is JsonValue.Arr -> {
                val node = DefaultMutableTreeNode("${name ?: "[]"}  (${value.items.size})")
                parent.add(node)
                value.items.forEachIndexed { i, v -> addJson(node, v, "[$i]") }
            }
            is JsonValue.Str -> parent.add(DefaultMutableTreeNode("${name ?: ""}: ${value.value}"))
            is JsonValue.Num -> parent.add(DefaultMutableTreeNode("${name ?: ""}: ${value.raw}"))
            is JsonValue.Bool -> parent.add(DefaultMutableTreeNode("${name ?: ""}: ${value.value}"))
            JsonValue.Null -> parent.add(DefaultMutableTreeNode("${name ?: ""}: null"))
        }
    }

    private class FactsModel(private val facts: List<Fact>) : AbstractTableModel() {
        private val columns = listOf("fact", "value", "what it means")
        override fun getRowCount(): Int = facts.size
        override fun getColumnCount(): Int = columns.size
        override fun getColumnName(column: Int): String = columns[column]
        override fun getValueAt(row: Int, column: Int): Any = when (column) {
            0 -> facts[row].name
            1 -> facts[row].value
            else -> facts[row].note
        }
    }
}
