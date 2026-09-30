package io.monadcount.csiq.ide.ui

import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.ui.JBUI
import io.monadcount.csiq.format.CsiRecord
import io.monadcount.csiq.format.Csiq
import io.monadcount.csiq.format.RawTlv
import io.monadcount.csiq.format.RawStream
import io.monadcount.csiq.format.TlvType
import io.monadcount.csiq.format.fmt
import io.monadcount.csiq.format.le16
import io.monadcount.csiq.format.le32
import io.monadcount.csiq.format.le64
import java.awt.BorderLayout
import java.awt.Font
import javax.swing.JPanel
import javax.swing.JTextArea
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreeSelectionModel

/**
 * The selected record, field by field and byte by byte.
 *
 * A self-describing format deserves a viewer that shows the self-description.
 * Each field carries the specification's own wording for what its value means,
 * an unknown type code is shown with whatever the format reserves that code
 * for, and the 272-byte driver header is decoded at the offsets the appendix
 * documents rather than left as an opaque blob.
 */
class TlvPanel(
    private val state: CaptureViewState,
    private val loader: RecordLoader,
) : JPanel(BorderLayout()) {

    private val root = DefaultMutableTreeNode("record")
    private val treeModel = DefaultTreeModel(root)
    private val tree = Tree(treeModel).apply {
        isRootVisible = true
        showsRootHandles = true
        selectionModel.selectionMode = TreeSelectionModel.SINGLE_TREE_SELECTION
    }
    private val hex = JTextArea().apply {
        isEditable = false
        font = Font(Font.MONOSPACED, Font.PLAIN, JBUI.scaleFontSize(12f))
        lineWrap = false
    }

    init {
        val splitter = OnePixelSplitter(false, 0.45f)
        splitter.firstComponent = JBScrollPane(tree)
        splitter.secondComponent = JBScrollPane(hex)
        add(splitter, BorderLayout.CENTER)

        tree.addTreeSelectionListener {
            val node = tree.lastSelectedPathComponent as? DefaultMutableTreeNode ?: return@addTreeSelectionListener
            when (val payload = node.userObject) {
                is TlvNode -> showHex(payload.tlv.value, payload.tlv.offsetInPayload)
                else -> Unit
            }
        }

        state.addListener {
            if (it == CaptureViewState.Change.SELECTION) refresh()
        }
        refresh()
    }

    private fun refresh() {
        root.removeAllChildren()
        root.userObject = "Reading record ${state.selectedRow}..."
        treeModel.reload()
        hex.text = ""
        loader.request(state.selectedRow, keepRawTlvs = true) { record ->
            if (record == null) {
                root.userObject = "Record ${state.selectedRow} could not be read."
                treeModel.reload()
            } else {
                populate(record)
            }
        }
    }

    private fun populate(r: CsiRecord) {
        root.removeAllChildren()
        val payloadBytes = r.rawTlvs?.sumOf { 5 + it.length } ?: 0
        root.userObject = "record ${state.selectedRow}  -  $payloadBytes byte payload, " +
            "${r.rawTlvs?.size ?: 0} fields"

        r.rawTlvs?.forEach { tlv ->
            val node = DefaultMutableTreeNode(TlvNode(tlv, decode(tlv, r)))
            if (tlv.code == TlvType.VENDOR_HDR.code) addVendorHeader(node, tlv.value)
            root.add(node)
        }

        if (r.unknownCodes.isNotEmpty()) {
            val node = DefaultMutableTreeNode(
                "skipped ${r.unknownCodes.size} field(s) this reader does not implement",
            )
            r.unknownCodes.distinct().forEach { code ->
                node.add(DefaultMutableTreeNode("0x%02x - %s".fmt(code, TlvType.reservedFor(code))))
            }
            root.add(node)
        }

        treeModel.reload()
        for (i in 0 until tree.rowCount.coerceAtMost(40)) tree.expandRow(i)
        showHex(r.rawTlvs?.firstOrNull()?.value ?: ByteArray(0), 0)
    }

    /** One line of plain meaning per field, using the value the bytes carry. */
    private fun decode(tlv: RawTlv, r: CsiRecord): String {
        val v = tlv.value
        return when (tlv.code) {
            TlvType.FTM.code -> "${le32(v, 0).toLong() and 0xFFFF_FFFFL} ticks  " +
                "(%.6f s into this wrap)".fmt((le32(v, 0).toLong() and 0xFFFF_FFFFL) / 320e6)
            TlvType.US.code -> "${le32(v, 0).toLong() and 0xFFFF_FFFFL} us"
            TlvType.UNIX_TS_NS.code -> {
                val ns = le64(v, 0)
                "$ns  (${java.time.Instant.ofEpochSecond(ns / 1_000_000_000L, ns % 1_000_000_000L)})"
            }
            TlvType.RNF.code -> "0x%08x  ->  %s, %s, antennas %s".fmt(
                le32(v, 0),
                r.phy?.toString() ?: "no rate information",
                r.bandwidth?.label ?: "bandwidth not recorded",
                antennaText(r.antennaSel),
            )
            TlvType.PHY.code -> r.phy?.toString() ?: "-"
            TlvType.NRX.code -> "${v[0].toInt() and 0xFF} receive chains"
            TlvType.NTX.code -> "${v[0].toInt() and 0xFF} transmit streams"
            TlvType.NTONE.code -> "${le16(v, 0)} subcarriers"
            TlvType.SRC_MAC.code -> r.srcMacString
            TlvType.CHANNEL.code -> "channel ${le32(v, 0)}"
            TlvType.WIDTH.code -> "${r.width.label}  (the MONITOR's width, a session constant)"
            TlvType.RSSI.code -> r.rssi.joinToString("  ") { s ->
                if (s == Csiq.RSSI_NO_MEASUREMENT) "no measurement" else "$s dBm"
            }
            TlvType.SEQ.code -> "${v[0].toInt() and 0xFF}  (driver report counter, wraps at 256)"
            TlvType.BW_ANTSEL.code -> "${r.bandwidth?.label ?: "unknown"}, antennas ${antennaText(r.antennaSel)}"
            TlvType.MONO_US.code -> "${le64(v, 0)} us monotonic  (this frame was genuinely received)"
            TlvType.VENDOR_HDR.code -> "${v.size} bytes of driver header, kept verbatim"
            TlvType.CSI_MATRIX.code -> "${v.size / 2} i16 values = ${v.size / 4} complex coefficients " +
                "(${r.ntone} tones x ${r.chains} chains), chain-major, imaginary first"
            TlvType.NODE_TEMP_MC.code -> "%.1f C SoC die  (stored in millidegrees)".fmt(le32(v, 0) / 1000.0)
            TlvType.NODE_NIC_TEMP_C.code -> "${le32(v, 0)} C NIC die  (stored in WHOLE degrees)"
            TlvType.NODE_THROTTLE.code -> "0x%08x%s".fmt(
                le32(v, 0),
                if (le32(v, 0) != 0) "  - the SoC was capped" else "",
            )
            TlvType.NODE_SPOOL_FREE.code -> "${le64(v, 0)} bytes free on the capture spool"
            TlvType.NODE_LOAD_M.code -> "%.3f one-minute load".fmt(le32(v, 0) / 1000.0)
            else -> "${v.size} bytes"
        }
    }

    private fun antennaText(sel: Int?): String = when (sel) {
        null -> "not recorded"
        0 -> "none named (normal on a receive record)"
        1 -> "A"
        2 -> "B"
        3 -> "A+B"
        else -> "mask 0x%02x".fmt(sel)
    }

    /**
     * The driver header, decoded where the appendix says the fields are.
     *
     * The four unnamed per-chain values are shown as what they are: correlated
     * with RSSI by the header survey, semantics not established. Naming them
     * would assert a meaning nobody has established.
     */
    private fun addVendorHeader(node: DefaultMutableTreeNode, hdr: ByteArray) {
        fun add(text: String) = node.add(DefaultMutableTreeNode(text))
        if (hdr.size < RawStream.Offsets.RNF + 4) {
            add("header is ${hdr.size} bytes, too short to decode")
            return
        }
        add("@8   ftm          ${le32(hdr, RawStream.Offsets.FTM).toLong() and 0xFFFF_FFFFL}")
        add("@46  nrx          ${hdr[RawStream.Offsets.NRX].toInt() and 0xFF}")
        add("@47  ntx          ${hdr[RawStream.Offsets.NTX].toInt() and 0xFF}")
        add("@52  ntone        ${le16(hdr, RawStream.Offsets.NTONE)}")
        add("@60  rssi_a       ${RawStream.rssiDbm(hdr[RawStream.Offsets.RSSI_A].toInt())} dBm")
        add("@64  rssi_b       ${RawStream.rssiDbm(hdr[RawStream.Offsets.RSSI_B].toInt())} dBm")
        add(
            "@68  src_mac      " + (0 until 6).joinToString(":") {
                "%02x".fmt(hdr[RawStream.Offsets.SRC_MAC + it])
            },
        )
        add("@76  seq          ${hdr[RawStream.Offsets.SEQ].toInt() and 0xFF}")
        add("@88  us           ${le32(hdr, RawStream.Offsets.US).toLong() and 0xFFFF_FFFFL}")
        add("@92  rnf          0x%08x".fmt(le32(hdr, RawStream.Offsets.RNF)))
        if (hdr.size >= RawStream.Offsets.MONO_US + 8) {
            val mono = le64(hdr, RawStream.Offsets.MONO_US)
            add("@200 mono_us      " + if (mono == 0L) "0  (own transmission)" else "$mono")
        }
        if (hdr.size > RawStream.Offsets.CHANNEL) {
            add("@208 unix_ts_ns   ${le64(hdr, RawStream.Offsets.UNIX_TS_NS)}")
            add("@216 channel      ${hdr[RawStream.Offsets.CHANNEL].toInt() and 0xFF}")
        }
        val unnamed = DefaultMutableTreeNode("unnamed per-chain u16, correlated with RSSI (IP-130)")
        for (o in RawStream.UNNAMED_U16_OFFSETS) {
            if (hdr.size >= o + 2) unnamed.add(DefaultMutableTreeNode("@$o  ${le16(hdr, o)}"))
        }
        if (unnamed.childCount > 0) node.add(unnamed)
    }

    private fun showHex(bytes: ByteArray, baseOffset: Int) {
        val limit = 4096
        val sb = StringBuilder()
        val shown = minOf(bytes.size, limit)
        var i = 0
        while (i < shown) {
            sb.append("%08x  ".fmt(baseOffset + i))
            for (j in 0 until 16) {
                if (i + j < shown) sb.append("%02x ".fmt(bytes[i + j])) else sb.append("   ")
                if (j == 7) sb.append(' ')
            }
            sb.append(' ')
            for (j in 0 until 16) {
                if (i + j >= shown) break
                val c = bytes[i + j].toInt() and 0xFF
                sb.append(if (c in 32..126) c.toChar() else '.')
            }
            sb.append('\n')
            i += 16
        }
        if (bytes.size > limit) {
            sb.append("\n... ${bytes.size - limit} more bytes not shown\n")
        }
        hex.text = sb.toString()
        hex.caretPosition = 0
    }

    private class TlvNode(val tlv: RawTlv, val decoded: String) {
        override fun toString(): String {
            val type = tlv.type
            val name = type?.label ?: "unknown"
            return "0x%02x  %-14s %5d B   %s".fmt(tlv.code, name, tlv.length, decoded)
        }
    }
}
