package io.monadcount.csiq.ide

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.vfs.VirtualFile

/**
 * Open a capture from anywhere the IDE can see, including a remote filesystem.
 *
 * The chooser is not restricted to the local disk, so an S3 or SFTP root
 * mounted by the Remote File Systems plugin appears in it like any other tree.
 * That is the only thing this action does differently from double-clicking a
 * file, and it is the difference that matters for an archive kept on S3.
 */
class OpenCaptureAction : AnAction(), DumbAware {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val descriptor = FileChooserDescriptor(true, false, false, true, false, false)
            .withFileFilter { file: VirtualFile -> CsiqFileType.matches(file) }
            .withTitle("Open CSI Capture")
            .withDescription(
                "A .csiq container, a .csiq.zst container, or a capture.raw driver stream. " +
                    "Remote roots mounted by Big Data Tools or Remote File Systems work here too.",
            )

        FileChooser.chooseFile(descriptor, project, null) { file ->
            FileEditorManager.getInstance(project).openFile(file, true)
        }
    }
}
