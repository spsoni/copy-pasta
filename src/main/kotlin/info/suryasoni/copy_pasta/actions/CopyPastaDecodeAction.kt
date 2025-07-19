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
import java.io.*
import java.util.*
import java.util.zip.GZIPInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream


class CopyPastaDecodeAction : AnAction() {

    override fun actionPerformed(event: AnActionEvent) {
        val projectDir: VirtualFile? = event.getData(CommonDataKeys.VIRTUAL_FILE)
        if (projectDir == null || !projectDir.isDirectory) {
            return
        }

        val settings = service<CopyPastaSettingsState>().state
        val encryptionKey = CopyPastaUtils.hashKey(settings.encryptionKey)
        val enableDecryption = settings.enableDecryption

        try {
            // Get the Base64 string from clipboard
            val clipboard = Toolkit.getDefaultToolkit().systemClipboard
            val base64EncodedData = clipboard.getData(DataFlavor.stringFlavor) as? String ?: return

            // Decode the Base64 string
            val decodedBytes = Base64.getDecoder().decode(base64EncodedData.replace("\n", ""))

            // Check if the data is encrypted
            if (isEncrypted(decodedBytes)) {
                if (!enableDecryption) {
                    notify("Decryption Disabled", "The file is encrypted, but decryption is disabled.", NotificationType.ERROR)
                    return
                }

                try {
                    // Attempt to decrypt the data
                    val finalBytes = CopyPastaUtils.decrypt(decodedBytes, encryptionKey)
                    processDecryptedData(finalBytes, projectDir)
                } catch (e: Exception) {
                    notify("Decryption Failed", "The file is encrypted, but the pass key is incorrect.", NotificationType.ERROR)
                }
            } else {
                // Process the data if it's not encrypted
                processDecryptedData(decodedBytes, projectDir)
            }

            // Clear the clipboard
            val emptyStringSelection = StringSelection("")
            clipboard.setContents(emptyStringSelection, null)

            // Refresh the project directory in IntelliJ
            LocalFileSystem.getInstance().refreshAndFindFileByIoFile(File(projectDir.path))?.refresh(true, true)

        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun isEncrypted(data: ByteArray): Boolean {
        // Simple heuristic to check if data is encrypted (e.g., check for non-printable characters)
        return data.any { it < 32 || it > 126 }
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
            }
        }

        // Send notification
        notify("Unarchive Complete", "The files have been successfully unarchived and clipboard cleared.", NotificationType.INFORMATION)
    }

    private fun notify(title: String, content: String, type: NotificationType) {
        val notification = Notification("Copy-Pasta", title, content, type)
        Notifications.Bus.notify(notification)
    }
}
