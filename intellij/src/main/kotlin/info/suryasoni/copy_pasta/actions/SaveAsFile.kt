package info.suryasoni.copy_pasta.actions

import com.intellij.notification.NotificationType
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.fileChooser.FileSaverDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import info.suryasoni.copy_pasta.CopyPastaBundle.message
import info.suryasoni.copy_pasta.ide.Notifier
import java.io.IOException
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Saves Copy-Pasta text as a `.txt` file to send as an attachment; the recipient opens it from Paste's dialog.
 * Used by the copy notification and the status bar icon's menu.
 */
internal object SaveAsFile {
    /**
     * Local date and time in ISO order with no separators that file systems reject (`/` and `:`), so names are
     * valid everywhere and sort chronologically: `copy-pasta-2026-11-17-135212.txt`. The user's regional format
     * isn't used because it often contains `/` or `:`.
     */
    private val TIMESTAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmmss")

    fun defaultFileName(now: LocalDateTime = LocalDateTime.now()): String = "copy-pasta-${TIMESTAMP.format(now)}.txt"

    fun save(project: Project, text: String) {
        val descriptor = FileSaverDescriptor(message("save.title"), message("save.description"), "txt")
        val wrapper = FileChooserFactory.getInstance().createSaveFileDialog(descriptor, project).save(defaultFileName()) ?: return
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
}
