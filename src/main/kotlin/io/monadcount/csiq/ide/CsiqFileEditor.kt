package io.monadcount.csiq.ide

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorPolicy
import com.intellij.openapi.fileEditor.FileEditorProvider
import com.intellij.openapi.fileEditor.FileEditorState
import com.intellij.openapi.fileEditor.FileEditorStateLevel
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import io.monadcount.csiq.format.Payload
import io.monadcount.csiq.ide.ui.CsiqEditorPanel
import io.monadcount.csiq.model.Capture
import io.monadcount.csiq.model.CaptureLoader
import io.monadcount.csiq.model.LoadProgress
import java.awt.BorderLayout
import java.beans.PropertyChangeListener
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JTextArea
import javax.swing.SwingConstants

/** Opens CSIQ containers, compressed containers, and raw driver streams. */
class CsiqEditorProvider : FileEditorProvider, DumbAware {

    /**
     * Decided by name alone.
     *
     * This runs for every file the IDE opens, including files on a remote
     * filesystem, so it must not read a byte. Sniffing happens later, on a
     * background thread, where a network round trip is affordable.
     */
    override fun accept(project: Project, file: VirtualFile): Boolean = CsiqFileType.matches(file)

    override fun createEditor(project: Project, file: VirtualFile): FileEditor =
        CsiqFileEditor(project, file)

    override fun getEditorTypeId(): String = "csiq-capture"

    override fun getPolicy(): FileEditorPolicy = FileEditorPolicy.HIDE_DEFAULT_EDITOR
}

/**
 * The capture editor.
 *
 * Opening shows a panel immediately and indexes in the background, because the
 * pass reads the whole file and the file may be on the other side of a network.
 * The event thread is never used to read a byte.
 */
class CsiqFileEditor(
    private val project: Project,
    private val file: VirtualFile,
) : UserDataHolderBase(), FileEditor {

    private val root = JPanel(BorderLayout())
    private val loadingLabel = JBLabel("Indexing ${file.name}...", SwingConstants.CENTER)
    private var capture: Capture? = null
    private var opened: OpenedCapture? = null
    private var disposed = false

    init {
        root.add(loadingLabel, BorderLayout.CENTER)
        startLoad()
    }

    private fun startLoad() {
        val task = object : Task.Backgroundable(project, "Indexing ${file.name}", true) {
            private var result: Capture? = null
            private var openedCapture: OpenedCapture? = null
            private var failure: Throwable? = null

            override fun run(indicator: ProgressIndicator) {
                try {
                    val open = CaptureSourceFactory.open(file, indicator)
                    openedCapture = open
                    if (open.payload == Payload.UNKNOWN) {
                        throw IllegalStateException(
                            "${file.name} is neither a CSIQ container nor a raw driver stream." +
                                if (open.compression.isCompressed) {
                                    " It expanded from ${open.compression.label} cleanly, so the codec is " +
                                        "right and the content inside is something else."
                                } else {
                                    " Its first bytes match no known envelope."
                                },
                        )
                    }
                    indicator.text = "Indexing records"
                    indicator.isIndeterminate = false
                    result = CaptureLoader.load(
                        source = open.source,
                        payload = open.payload,
                        progress = ProgressBridge(indicator),
                        compression = open.compression,
                        sidecar = open.sidecar,
                    )
                } catch (e: Throwable) {
                    failure = e
                }
            }

            override fun onFinished() {
                ApplicationManager.getApplication().invokeLater {
                    if (disposed) {
                        openedCapture?.close()
                        return@invokeLater
                    }
                    val c = result
                    val f = failure
                    when {
                        c != null -> showCapture(c, openedCapture)
                        f != null -> showError(f)
                        else -> showMessage("Indexing was cancelled.")
                    }
                }
            }
        }
        ProgressManager.getInstance().run(task)
    }

    private fun showCapture(c: Capture, open: OpenedCapture?) {
        capture = c
        opened = open
        root.removeAll()
        val provenance = buildString {
            append(open?.provenance ?: "local disk")
            open?.verdict?.disagreement()?.let { append("   (").append(it).append(")") }
        }
        root.add(
            CsiqEditorPanel(project, c, provenance, open?.siblings ?: emptyList(), this),
            BorderLayout.CENTER,
        )
        root.revalidate()
        root.repaint()
    }

    private fun showError(e: Throwable) {
        val text = JTextArea(
            buildString {
                append(file.presentableUrl).append("\n\n")
                append(e.message ?: e.toString()).append('\n')
                if (VirtualFileByteSource.isRemote(file)) {
                    append("\nThis file is on ")
                    append(VirtualFileByteSource.describe(file.fileSystem))
                    append(". A read that fails there is usually a credential or a permission,\n")
                    append("not a malformed capture.\n")
                }
            },
        )
        text.isEditable = false
        text.border = JBUI.Borders.empty(12)
        root.removeAll()
        root.add(JBScrollPane(text), BorderLayout.CENTER)
        root.revalidate()
        root.repaint()
    }

    private fun showMessage(message: String) {
        root.removeAll()
        root.add(JBLabel(message, SwingConstants.CENTER), BorderLayout.CENTER)
        root.revalidate()
        root.repaint()
    }

    override fun getComponent(): JComponent = root

    override fun getPreferredFocusedComponent(): JComponent = root

    override fun getName(): String = "CSIQ capture"

    override fun setState(state: FileEditorState) = Unit

    override fun getState(level: FileEditorStateLevel): FileEditorState = FileEditorState.INSTANCE

    override fun isModified(): Boolean = false

    override fun isValid(): Boolean = file.isValid

    override fun addPropertyChangeListener(listener: PropertyChangeListener) = Unit

    override fun removePropertyChangeListener(listener: PropertyChangeListener) = Unit

    override fun getFile(): VirtualFile = file

    override fun dispose() {
        disposed = true
        opened?.close()
        opened = null
        capture = null
    }

    private class ProgressBridge(private val indicator: ProgressIndicator) : LoadProgress {
        override fun onBytes(read: Long, total: Long) {
            if (total > 0) indicator.fraction = (read.toDouble() / total).coerceIn(0.0, 1.0)
        }

        override fun onRecords(count: Int) {
            indicator.text2 = "$count records"
        }

        override val isCancelled: Boolean get() = indicator.isCanceled
    }
}
