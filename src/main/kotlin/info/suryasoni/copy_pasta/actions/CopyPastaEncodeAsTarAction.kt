package info.suryasoni.copy_pasta.actions

import com.intellij.notification.Notification
import com.intellij.notification.NotificationType
import com.intellij.notification.Notifications
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.components.service
import info.suryasoni.copy_pasta.settings.CopyPastaSettingsState
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Base64
import java.util.zip.GZIPOutputStream

class CopyPastaEncodeAsTarAction : AnAction() {

    override fun actionPerformed(event: AnActionEvent) {
        val selectedFiles = event.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY) ?: return
        if (selectedFiles.isEmpty()) {
            return
        }

        val settings = service<CopyPastaSettingsState>().state
        val maxBase64Size = settings.maxBase64Size
        val encryptionKey = CopyPastaUtils.hashKey(settings.encryptionKey)
        val enableEncryption = settings.enableEncryption // Renamed for clarity

        try {
            val byteArrayOutputStream = ByteArrayOutputStream()
            GZIPOutputStream(byteArrayOutputStream).use { gzos ->
                TarArchiveOutputStream(gzos).use { taos ->
                    taos.setLongFileMode(TarArchiveOutputStream.LONGFILE_GNU)
                    selectedFiles.forEach { virtualFile ->
                        virtualFile.parent?.path?.let { parentPath ->
                            CopyPastaUtils.tarFiles(File(parentPath), File(virtualFile.path), taos)
                        }
                    }
                }
            }

            val tarGzBytes = byteArrayOutputStream.toByteArray()
            val finalBytes = if (enableEncryption) CopyPastaUtils.encrypt(tarGzBytes, encryptionKey) else tarGzBytes

            val base64EncodedTarGz = Base64.getEncoder().encodeToString(finalBytes)

            if (base64EncodedTarGz.length > maxBase64Size) {
                notifyUser(
                    "Payload Too Large",
                    "The encoded archive is too large to copy to the clipboard. Size: ${CopyPastaUtils.humanReadableByteCount(base64EncodedTarGz.length.toLong())}",
                    NotificationType.ERROR
                )
                return
            }

            val stringSelection = StringSelection(base64EncodedTarGz)
            val clipboard = Toolkit.getDefaultToolkit().systemClipboard
            clipboard.setContents(stringSelection, null)

            notifyUser(
                "Archive Copied",
                "The archive (${CopyPastaUtils.humanReadableByteCount(tarGzBytes.size.toLong())}) has been copied to the clipboard.",
                NotificationType.INFORMATION
            )

        } catch (e: Exception) {
            notifyUser("Error", "Failed to encode files: ${e.message}", NotificationType.ERROR)
        }
    }

    private fun notifyUser(title: String, content: String, type: NotificationType) {
        val notification = Notification("Copy-Pasta", title, content, type)
        Notifications.Bus.notify(notification)
    }
}
