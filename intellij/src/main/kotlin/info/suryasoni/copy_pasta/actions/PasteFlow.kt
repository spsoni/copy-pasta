package info.suryasoni.copy_pasta.actions

import com.intellij.ide.projectView.ProjectView
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import info.suryasoni.copy_pasta.CopyPastaBundle.message
import info.suryasoni.copy_pasta.format.Crypto
import info.suryasoni.copy_pasta.format.Envelope
import info.suryasoni.copy_pasta.format.InvalidArchiveException
import info.suryasoni.copy_pasta.format.WrongPassphraseException
import info.suryasoni.copy_pasta.format.ZipCodec
import info.suryasoni.copy_pasta.ide.ClipboardWatcher
import info.suryasoni.copy_pasta.ide.Notifier
import info.suryasoni.copy_pasta.ide.PasteWriter
import info.suryasoni.copy_pasta.ide.PlannedFile
import info.suryasoni.copy_pasta.ui.PassphraseDialog
import info.suryasoni.copy_pasta.ui.PastePreviewDialog
import info.suryasoni.copy_pasta.ui.PasteTextDialog
import java.awt.datatransfer.DataFlavor
import java.util.concurrent.Callable

/** The paste pipeline shared by the Paste actions and the status bar widget: decode, preview, write. */
internal object PasteFlow {
    /**
     * Pastes the clipboard into [target]. When the clipboard has no Copy-Pasta text, asks for it instead (text box
     * or file) rather than failing.
     */
    fun pasteFromClipboard(project: Project, target: VirtualFile) {
        val clipboard = CopyPasteManager.getInstance().getContents<String>(DataFlavor.stringFlavor).orEmpty()
        // Only cheap checks on the EDT. Decoding (up to tens of MB of Base64) happens in the background task.
        val text = if (Envelope.looksLikeEnvelope(clipboard)) clipboard else PasteTextDialog.ask(project) ?: return
        paste(project, target, text)
    }

    private fun paste(project: Project, target: VirtualFile, text: String) {
        val passphrase = if (Envelope.isEncrypted(text)) PassphraseDialog.ask(project, confirm = false) ?: return else null

        object : Task.Backgroundable(project, message("paste.progress"), true) {
            private var planned: List<PlannedFile> = emptyList()

            override fun run(indicator: ProgressIndicator) {
                val decoded = Envelope.decode(text)
                val zip = passphrase?.let { Crypto.decrypt(decoded.payload, it) } ?: decoded.payload
                val files = ZipCodec.decode(zip)
                indicator.checkCanceled()
                planned = ReadAction.nonBlocking(Callable { PasteWriter.plan(target, files) }).executeSynchronously()
            }

            override fun onSuccess() {
                if (planned.isEmpty()) {
                    Notifier.notify(project, message("paste.empty"), NotificationType.WARNING)
                    return
                }
                val dialog = PastePreviewDialog(project, target, planned)
                if (!dialog.showAndGet()) return
                val written =
                    try {
                        PasteWriter.write(project, target, dialog.selectedFiles)
                    } catch (e: Exception) {
                        // Some files may already be written; the Local History label lets the user undo them.
                        Notifier.error(project, message("paste.writeFailed", e.message ?: e.javaClass.simpleName))
                        return
                    }
                ClipboardWatcher.getInstance().markPasted(text)
                Notifier.notify(
                    project,
                    message("paste.done", written.size, target.presentableUrl),
                    NotificationType.INFORMATION,
                    NotificationAction.createSimpleExpiring(message("paste.show")) { reveal(project, written) },
                )
            }

            override fun onThrowable(error: Throwable) {
                if (error is Envelope.EnvelopeException) {
                    // A cut-off or damaged message isn't a dead end: offer the dialog, where the user can paste a
                    // complete copy or open the file the sender saved.
                    val problem = PasteTextDialog.problemMessage(error)
                    Notifier.notify(
                        project,
                        problem,
                        NotificationType.ERROR,
                        NotificationAction.createSimpleExpiring(message("paste.tryAgain")) {
                            PasteTextDialog.ask(project, initialText = text, initialError = problem)?.let { paste(project, target, it) }
                        },
                    )
                    return
                }
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
            OpenFileDescriptor(project, first).navigate(true)
        } else {
            ProjectView.getInstance(project).select(null, first, true)
        }
    }
}
