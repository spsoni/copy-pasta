package info.suryasoni.copy_pasta.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.components.service
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.vfs.VirtualFile
import info.suryasoni.copy_pasta.settings.CopyPastaSettingsState
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.io.File
import javax.crypto.AEADBadTagException

class CopyPastaDecodeAction : AnAction() {

    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(event: AnActionEvent) {
        event.presentation.isEnabledAndVisible = targetDirectory(event) != null
    }

    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project
        val targetDir = targetDirectory(event) ?: return
        val clipboardText = CopyPasteManager.getInstance().getContents<String>(DataFlavor.stringFlavor)
        if (clipboardText.isNullOrBlank()) {
            CopyPastaNotifier.error(project, "Nothing to paste", "The clipboard is empty.")
            return
        }

        val settings = service<CopyPastaSettingsState>().state.copy()
        val destination = File(targetDir.path)

        object : Task.Backgroundable(project, "Copy-Pasta: extracting", false) {
            private var fileCount = 0

            override fun run(indicator: ProgressIndicator) {
                val decoded = try {
                    CopyPastaUtils.fromBase64(clipboardText)
                } catch (_: IllegalArgumentException) {
                    throw UserFacingException("Invalid data", "The clipboard does not contain valid Base64 data.")
                }

                val tarGz = when {
                    CopyPastaUtils.isEncrypted(decoded) -> {
                        if (!settings.enableDecryption) {
                            throw UserFacingException(
                                "Decryption disabled",
                                "The data is encrypted, but decryption is disabled in settings.",
                            )
                        }
                        try {
                            CopyPastaUtils.decrypt(decoded, settings.encryptionKey)
                        } catch (_: AEADBadTagException) {
                            throw UserFacingException("Decryption failed", "The data is encrypted with a different key.")
                        }
                    }
                    CopyPastaUtils.isGzip(decoded) -> decoded
                    else -> throw UserFacingException(
                        "Invalid format",
                        "The clipboard does not contain a Copy-Pasta archive (it may come from an older, incompatible version).",
                    )
                }

                val patterns = CopyPastaUtils.parseExcludePatterns(settings.excludePatterns)
                fileCount = CopyPastaUtils.extractTarGz(tarGz, destination, patterns)
            }

            override fun onSuccess() {
                targetDir.refresh(true, true)
                CopyPasteManager.getInstance().setContents(StringSelection(""))
                CopyPastaNotifier.info(
                    project,
                    "Extraction complete",
                    "$fileCount file(s) extracted to ${targetDir.name}; the clipboard has been cleared.",
                )
            }

            override fun onThrowable(error: Throwable) {
                if (error is UserFacingException) {
                    CopyPastaNotifier.error(project, error.title, error.message)
                } else {
                    CopyPastaNotifier.error(project, "Copy-Pasta error", "Failed to decode data: ${error.message}")
                }
            }
        }.queue()
    }

    /** The selected directory, or the parent of a selected file. */
    private fun targetDirectory(event: AnActionEvent): VirtualFile? {
        val file = event.getData(CommonDataKeys.VIRTUAL_FILE) ?: return null
        if (!file.isInLocalFileSystem) return null
        return if (file.isDirectory) file else file.parent
    }

    private class UserFacingException(val title: String, override val message: String) : Exception(message)
}
