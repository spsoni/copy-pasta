package info.suryasoni.copy_pasta.actions

import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.fileChooser.FileSaverDescriptor
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import info.suryasoni.copy_pasta.CopyPastaBundle.message
import info.suryasoni.copy_pasta.format.Crypto
import info.suryasoni.copy_pasta.format.Envelope
import info.suryasoni.copy_pasta.format.ZipCodec
import info.suryasoni.copy_pasta.ide.CopyCollector
import info.suryasoni.copy_pasta.ide.CopyTooLargeException
import info.suryasoni.copy_pasta.ide.Notifier
import info.suryasoni.copy_pasta.ui.PassphraseDialog
import java.awt.datatransfer.StringSelection
import java.io.IOException

/** Copies the selected files and folders to the clipboard as Copy-Pasta text. */
open class CopyAction(private val encrypted: Boolean = false) : DumbAwareAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(event: AnActionEvent) {
        event.presentation.isEnabledAndVisible =
            event.project != null &&
            !event.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY).isNullOrEmpty()
    }

    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        val roots = event.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY)?.toList().orEmpty()
        if (roots.isEmpty()) return
        val passphrase = if (encrypted) PassphraseDialog.ask(project, confirm = true) ?: return else null

        object : Task.Backgroundable(project, message("copy.progress"), true) {
            private lateinit var collected: CopyCollector.Result
            private var text: String? = null

            override fun run(indicator: ProgressIndicator) {
                collected = CopyCollector(project).collect(roots, indicator)
                if (collected.files.isEmpty()) return
                val zip = ZipCodec.encode(collected.files)
                val payload = passphrase?.let { Crypto.encrypt(zip, it) } ?: zip
                val summary = message("copy.envelopeSummary", collected.files.size, StringUtil.formatFileSize(collected.totalBytes))
                text = Envelope.encode(payload, encrypted, summary)
            }

            // getContents() is nullable but setContents() isn't, so `contents` is a val in Kotlin: keep the setter call.
            @Suppress("UsePropertyAccessSyntax")
            override fun onSuccess() {
                val copied = text
                if (copied == null) {
                    Notifier.notify(project, message("copy.nothing"), NotificationType.WARNING)
                    return
                }
                CopyPasteManager.getInstance().setContents(StringSelection(copied))
                val lines =
                    mutableListOf(
                        message("copy.done", collected.files.size, StringUtil.formatFileSize(collected.totalBytes), copied.length),
                    )
                if (encrypted) lines += message("copy.encrypted")
                if (collected.skippedIgnored > 0) lines += message("copy.skippedIgnored", collected.skippedIgnored)
                if (collected.skippedLarge.isNotEmpty()) {
                    val names = collected.skippedLarge.take(3).joinToString { StringUtil.escapeXmlEntities(it) }
                    lines += message("copy.skippedLarge", collected.skippedLarge.size, names)
                }
                if (collected.skippedDuplicates > 0) lines += message("copy.skippedDuplicates", collected.skippedDuplicates)
                val tooLongForChat = copied.length > CHAT_FRIENDLY_CHARS
                if (tooLongForChat) lines += message("copy.longForChat")
                val warn = tooLongForChat || collected.skippedLarge.isNotEmpty() || collected.skippedDuplicates > 0
                val actions =
                    if (tooLongForChat) {
                        arrayOf(NotificationAction.createSimpleExpiring(message("copy.saveAsFile")) { saveAsFile(project, copied) })
                    } else {
                        emptyArray()
                    }
                Notifier.notify(
                    project,
                    lines.joinToString("<br>"),
                    if (warn) NotificationType.WARNING else NotificationType.INFORMATION,
                    *actions,
                )
            }

            override fun onThrowable(error: Throwable) {
                val content =
                    when (error) {
                        is CopyTooLargeException -> message("copy.tooLarge", StringUtil.formatFileSize(CopyCollector.MAX_TOTAL_BYTES))
                        else -> message("copy.failed", error.message ?: error.javaClass.simpleName)
                    }
                Notifier.error(project, content)
            }

            override fun onFinished() {
                passphrase?.fill('\u0000')
            }
        }.queue()
    }

    /** Saves [text] as a `.txt` file to send as an attachment; the recipient opens it from Paste's fallback dialog. */
    private fun saveAsFile(project: Project, text: String) {
        val descriptor = FileSaverDescriptor(message("save.title"), message("save.description"), "txt")
        val wrapper = FileChooserFactory.getInstance().createSaveFileDialog(descriptor, project).save(DEFAULT_FILE_NAME) ?: return
        try {
            val saved =
                WriteAction.compute<VirtualFile, IOException> {
                    val file = wrapper.getVirtualFile(true) ?: throw IOException(wrapper.file.path)
                    VfsUtil.saveText(file, text)
                    file
                }
            Notifier.notify(project, message("save.done", saved.presentableUrl), NotificationType.INFORMATION)
        } catch (e: IOException) {
            Notifier.error(project, message("save.failed", e.message ?: e.javaClass.simpleName))
        }
    }

    companion object {
        private const val DEFAULT_FILE_NAME = "copy-pasta.txt"

        /** Slack cuts messages at about 40k characters and Teams at about 28 KB; warn above this. */
        const val CHAT_FRIENDLY_CHARS = 35_000
    }
}

class CopyEncryptedAction : CopyAction(encrypted = true)
