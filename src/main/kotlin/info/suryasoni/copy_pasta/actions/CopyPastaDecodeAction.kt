package info.suryasoni.copy_pasta.actions

import com.intellij.notification.Notification
import com.intellij.notification.NotificationType
import com.intellij.notification.Notifications
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.components.service
import info.suryasoni.copy_pasta.settings.CopyPastaSettingsState
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.io.ByteArrayInputStream
import java.io.File
import java.util.Base64
import java.util.zip.GZIPInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream


class CopyPastaDecodeAction : AnAction() {

    override fun actionPerformed(event: AnActionEvent) {
        val projectDir = event.getData(CommonDataKeys.VIRTUAL_FILE) ?: return
        if (!projectDir.isDirectory) {
            return
        }

        val settings = service<CopyPastaSettingsState>().state
        val encryptionKey = CopyPastaUtils.hashKey(settings.encryptionKey)
        val enableDecryption = settings.enableDecryption

        try {
            // Get the Base64 string from clipboard
            val clipboard = Toolkit.getDefaultToolkit().systemClipboard
            val clipboardData = clipboard.getData(DataFlavor.stringFlavor) ?: return
            val base64EncodedData = clipboardData as? String ?: return

            // Decode the Base64 string
            val decodedBytes = try {
                Base64.getDecoder().decode(base64EncodedData.replace("\n", ""))
            } catch (e: IllegalArgumentException) {
                notify("Invalid Data", "The clipboard does not contain valid base64 encoded data.", NotificationType.ERROR)
                return
            }

            // Check if the data is encrypted
            if (isEncrypted(decodedBytes)) {
                if (!enableDecryption) {
                    notify("Decryption Disabled", "The data is encrypted, but decryption is disabled in settings.", NotificationType.ERROR)
                    return
                }

                try {
                    // Attempt to decrypt the data
                    val finalBytes = CopyPastaUtils.decrypt(decodedBytes, encryptionKey)
                    processDecryptedData(finalBytes, projectDir)
                } catch (e: Exception) {
                    notify("Decryption Failed", "The data is encrypted, but the encryption key is incorrect.", NotificationType.ERROR)
                }
            } else {
                // Process the data if it's not encrypted
                processDecryptedData(decodedBytes, projectDir)
            }

            // Clear the clipboard
            val emptyStringSelection = StringSelection("")
            clipboard.setContents(emptyStringSelection, null)

            // Refresh the project directory
            LocalFileSystem.getInstance().refreshAndFindFileByIoFile(File(projectDir.path))?.refresh(true, true)

        } catch (e: Exception) {
            notify("Error", "Failed to decode data: ${e.message}", NotificationType.ERROR)
        }
    }

    private fun isEncrypted(data: ByteArray): Boolean {
        // Simple heuristic to check if data is encrypted
        return data.size > 0 && data.any { byte -> byte < 32 && byte != '\n'.code.toByte() && byte != '\r'.code.toByte() }
    }

    private fun processDecryptedData(data: ByteArray, projectDir: VirtualFile) {
        val destinationDir = File(projectDir.path)
        ByteArrayInputStream(data).use { bais ->
            if (CopyPastaUtils.isTarGzFile(data)) {
                GZIPInputStream(bais).use { gzis ->
                    TarArchiveInputStream(gzis).use { tais ->
                        CopyPastaUtils.untarFiles(tais, destinationDir)
                    }
                }
            } else {
                notify("Invalid Format", "The decoded data is not in the expected tar.gz format.", NotificationType.ERROR)
                return
            }
        }

        notify("Extraction Complete", "The files have been successfully extracted and the clipboard cleared.", NotificationType.INFORMATION)
    }

    private fun notify(title: String, content: String, type: NotificationType) {
        val notification = Notification("Copy-Pasta", title, content, type)
        Notifications.Bus.notify(notification)
    }
}
