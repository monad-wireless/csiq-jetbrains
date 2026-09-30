package io.monadcount.csiq.ide.ui

import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import io.monadcount.csiq.format.fmt
import io.monadcount.csiq.model.CaptureIndex
import io.monadcount.csiq.model.TimeAxisMode
import java.awt.BorderLayout
import javax.swing.JPanel
import javax.swing.ListSelectionModel
import javax.swing.table.AbstractTableModel

/**
 * Every record, as a table.
 *
 * The model reads the index rather than the capture, so scrolling a capture of
 * a million records touches no bytes on disk and issues no request to an object
 * store. Only the views that draw a waveform go back to the source.
 */
class RecordsPanel(private val state: CaptureViewState) : JPanel(BorderLayout()) {

    private val model = IndexTableModel(state)
    private val table = JBTable(model).apply {
        setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
        autoResizeMode = JBTable.AUTO_RESIZE_ALL_COLUMNS
        rowHeight = JBUI.scale(20)
        setShowGrid(false)
    }

    private var syncing = false

    init {
        add(JBScrollPane(table), BorderLayout.CENTER)

        table.selectionModel.addListSelectionListener {
            if (it.valueIsAdjusting || syncing) return@addListSelectionListener
            val row = table.selectedRow
            if (row >= 0) state.selectedRow = table.convertRowIndexToModel(row)
        }

        state.addListener {
            when (it) {
                CaptureViewState.Change.SELECTION -> selectCurrent()
                CaptureViewState.Change.TIME -> {
                    model.fireTableStructureChanged()
                    resizeColumns()
                    selectCurrent()
                }
                else -> Unit
            }
        }
        resizeColumns()
        selectCurrent()
    }

    private fun resizeColumns() {
        if (table.columnModel.columnCount > 2) {
            table.columnModel.getColumn(2).preferredWidth = JBUI.scale(150)
        }
    }

    private fun selectCurrent() {
        val row = state.selectedRow
        if (row !in 0 until model.rowCount) return
        val viewRow = table.convertRowIndexToView(row)
        if (viewRow < 0 || table.selectedRow == viewRow) return
        syncing = true
        try {
            table.setRowSelectionInterval(viewRow, viewRow)
            table.scrollRectToVisible(table.getCellRect(viewRow, 0, true))
        } finally {
            syncing = false
        }
    }

    private class IndexTableModel(private val state: CaptureViewState) : AbstractTableModel() {

        private val index = state.capture.index

        /**
         * The time column follows whichever clock the editor is showing.
         *
         * A table whose time column disagreed with the waterfall's axis would
         * be worse than no table: the two would be read side by side.
         */
        private fun timeColumnName(): String {
            val zone = state.formatter(0.0).zoneName
            return when (state.timeMode) {
                TimeAxisMode.ELAPSED -> "t (s)"
                TimeAxisMode.WALL_CLOCK -> "wall clock ($zone)"
                TimeAxisMode.HOST_CLOCK -> "host clock ($zone)"
            }
        }

        private val columns: List<String>
            get() = listOf(
                "#", "t (s)", timeColumnName(), "ftm", "seq", "source MAC",
                "rssi0", "rssi1", "tones", "rx x tx", "phy", "bw", "notes",
            )

        override fun getRowCount(): Int = index.size

        override fun getColumnCount(): Int = columns.size

        override fun getColumnName(column: Int): String = columns[column]

        override fun getValueAt(row: Int, column: Int): Any = when (column) {
            0 -> row.toLong()
            1 -> "%.3f".fmt(state.time.elapsedSeconds(row))
            2 -> state.time.nanosFor(row, state.timeMode)
                ?.let { state.formatter(0.0).full(it) }
                ?: "-"
            3 -> index.ftm[row].toLong() and 0xFFFF_FFFFL
            4 -> (index.seq[row].toInt() and 0xFF).toLong()
            5 -> index.srcMacString(row)
            6 -> rssiText(index.rssi0[row])
            7 -> rssiText(index.rssi1[row])
            8 -> (index.ntone[row].toInt() and 0xFFFF).toLong()
            9 -> "${index.nrx[row]} x ${index.ntx[row]}"
            10 -> buildString {
                append(index.modulationOf(row).label)
                val mcs = index.mcs[row].toInt()
                if (mcs >= 0) append(" MCS").append(mcs)
            }
            11 -> index.bandwidthOf(row)?.label ?: "not recorded"
            12 -> notes(row)
            else -> ""
        }

        private fun rssiText(v: Short): String = when (v) {
            CaptureIndex.RSSI_ABSENT -> "-"
            io.monadcount.csiq.format.Csiq.RSSI_NO_MEASUREMENT -> "no measurement"
            else -> "$v"
        }

        /**
         * The facts about a record that change how it may be used.
         *
         * Each one is a rule from the format specification, so a person reading
         * the table sees why a record is unusual rather than having to know.
         */
        private fun notes(row: Int): String {
            val out = ArrayList<String>(3)
            if (index.flag(row, CaptureIndex.FLAG_ALL_ZERO)) out += "all-zero CSI"
            if (index.flag(row, CaptureIndex.FLAG_STALE_CHAIN)) out += "stale chain"
            if (!index.flag(row, CaptureIndex.FLAG_MONO_PRESENT)) out += "own transmission"
            if (index.flag(row, CaptureIndex.FLAG_GEOMETRY_BAD)) out += "geometry mismatch"
            if (index.flag(row, CaptureIndex.FLAG_RSSI_POSITIVE)) out += "positive RSSI"
            if (index.flag(row, CaptureIndex.FLAG_UNKNOWN_TLV)) out += "unknown TLV"
            if (index.flag(row, CaptureIndex.FLAG_NODE_STATE)) out += "node state tick"
            return out.joinToString(", ")
        }

        override fun getColumnClass(column: Int): Class<*> = when (column) {
            0, 3, 4, 8 -> Long::class.javaObjectType
            else -> String::class.java
        }
    }
}
