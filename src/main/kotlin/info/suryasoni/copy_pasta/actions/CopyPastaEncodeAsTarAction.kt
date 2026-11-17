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
import java.awt.datatransfer.StringSelection
import java.io.File

class CopyPastaEncodeAsTarAction : AnAction() {

    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(event: AnActionEvent) {
        val files = event.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY)
        event.presentation.isEnabledAndVisible = !files.isNullOrEmpty() && files.all(VirtualFile::isInLocalFileSystem)
    }

    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project
        val sources = event.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY)
            ?.filter(VirtualFile::isInLocalFileSystem)
            ?.map { File(it.path) }
            .orEmpty()
        if (sources.isEmpty()) return

        val settings = service<CopyPastaSettingsState>().state.copy()

        object : Task.Backgroundable(project, "Copy-Pasta: encoding", true) {
            private var encoded: String? = null
            private var archiveSize = 0

            override fun run(indicator: ProgressIndicator) {
                val patterns = CopyPastaUtils.parseExcludePatterns(settings.excludePatterns)
                val tarGz = CopyPastaUtils.createTarGz(sources, patterns)
                indicator.checkCanceled()
                val payload = if (settings.enableEncryption) CopyPastaUtils.encrypt(tarGz, settings.encryptionKey) else tarGz
                archiveSize = tarGz.size
                encoded = CopyPastaUtils.toBase64(payload)
            }

            override fun onSuccess() {
                val text = encoded ?: return
                if (text.length > settings.maxBase64Size) {
                    CopyPastaNotifier.error(
                        project,
                        "Payload too large",
                        "The encoded archive is ${CopyPastaUtils.humanReadableByteCount(text.length.toLong())}, " +
                            "above the ${CopyPastaUtils.humanReadableByteCount(settings.maxBase64Size.toLong())} limit " +
                            "set in Settings | Tools | Copy-Pasta.",
                    )
                    return
                }
                CopyPasteManager.getInstance().setContents(StringSelection(text))
                val size = CopyPastaUtils.humanReadableByteCount(archiveSize.toLong())
                val encryptedNote = if (settings.enableEncryption) " (encrypted)" else ""
                CopyPastaNotifier.info(project, "Archive copied", "The archive ($size)$encryptedNote has been copied to the clipboard.")
            }

            override fun onThrowable(error: Throwable) {
                CopyPastaNotifier.error(project, "Copy-Pasta error", "Failed to encode files: ${error.message}")
            }
        }.queue()
    }
}
