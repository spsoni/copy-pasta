package info.suryasoni.copy_pasta.actions

import com.intellij.ide.projectView.ProjectView
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.vfs.VirtualFile
import info.suryasoni.copy_pasta.CopyPastaBundle.message
import info.suryasoni.copy_pasta.format.Crypto
import info.suryasoni.copy_pasta.format.Envelope
import info.suryasoni.copy_pasta.format.InvalidArchiveException
import info.suryasoni.copy_pasta.format.WrongPassphraseException
import info.suryasoni.copy_pasta.format.ZipCodec
import info.suryasoni.copy_pasta.ide.Notifier
import info.suryasoni.copy_pasta.ide.PasteWriter
import info.suryasoni.copy_pasta.ide.PlannedFile
import info.suryasoni.copy_pasta.ui.PassphraseDialog
import info.suryasoni.copy_pasta.ui.PastePreviewDialog
import java.awt.datatransfer.DataFlavor

/**
 * Recreates files from Copy-Pasta text on the clipboard: it decodes, shows a preview, then writes into the selected
 * folder (the parent folder when a file is selected, or the project root from the Edit menu).
 */
class PasteAction : DumbAwareAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(event: AnActionEvent) {
        event.presentation.isEnabledAndVisible = event.project != null && targetDirectory(event) != null
    }

    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        val target = targetDirectory(event) ?: return
        val clipboard = CopyPasteManager.getInstance().getContents<String>(DataFlavor.stringFlavor).orEmpty()

        val decoded =
            try {
                Envelope.decode(clipboard)
            } catch (e: Envelope.EnvelopeException) {
                Notifier.error(project, envelopeProblem(e))
                return
            }
        val passphrase = if (decoded.encrypted) PassphraseDialog.ask(project, confirm = false) ?: return else null

        object : Task.Backgroundable(project, message("paste.progress"), true) {
            private var planned: List<PlannedFile> = emptyList()

            override fun run(indicator: ProgressIndicator) {
                val zip = passphrase?.let { Crypto.decrypt(decoded.payload, it) } ?: decoded.payload
                val files = ZipCodec.decode(zip)
                indicator.checkCanceled()
                planned = ReadAction.compute<List<PlannedFile>, RuntimeException> { PasteWriter.plan(target, files) }
            }

            override fun onSuccess() {
                if (planned.isEmpty()) {
                    Notifier.notify(project, message("paste.empty"), NotificationType.WARNING)
                    return
                }
                val dialog = PastePreviewDialog(project, target, planned)
                if (!dialog.showAndGet()) return
                val written = PasteWriter.write(project, target, dialog.selectedFiles)
                Notifier.notify(
                    project,
                    message("paste.done", written.size, target.presentableUrl),
                    NotificationType.INFORMATION,
                    NotificationAction.createSimpleExpiring(message("paste.show")) { reveal(project, written) },
                )
            }

            override fun onThrowable(error: Throwable) {
                val content =
                    when (error) {
                        is WrongPassphraseException -> message("paste.wrongPassphrase")
                        is InvalidArchiveException -> message("paste.invalidArchive", error.message.orEmpty())
                        else -> message("paste.failed", error.message ?: error.javaClass.simpleName)
                    }
                Notifier.error(project, content)
            }

            override fun onFinished() {
                passphrase?.fill('\u0000')
            }
        }.queue()
    }

    private fun reveal(project: Project, files: List<VirtualFile>) {
        val first = files.firstOrNull { it.isValid } ?: return
        if (files.size == 1) {
            FileEditorManager.getInstance(project).openFile(first, true)
        } else {
            ProjectView.getInstance(project).select(null, first, true)
        }
    }

    private fun targetDirectory(event: AnActionEvent): VirtualFile? {
        val selected = event.getData(CommonDataKeys.VIRTUAL_FILE)
        val dir =
            when {
                selected == null -> event.project?.guessProjectDir()
                selected.isDirectory -> selected
                else -> selected.parent
            }
        return dir?.takeIf { it.isValid && it.isWritable }
    }

    private fun envelopeProblem(e: Envelope.EnvelopeException): String = when (e.problem) {
        Envelope.Problem.NOT_FOUND -> message("paste.notFound")
        Envelope.Problem.INCOMPLETE ->
            if (e.expected > 0) message("paste.incompleteOf", e.received, e.expected) else message("paste.incomplete")
        Envelope.Problem.CORRUPTED -> message("paste.corrupted")
        Envelope.Problem.NEWER_VERSION -> message("paste.newerVersion")
    }
}
