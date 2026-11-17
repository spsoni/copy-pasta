package info.suryasoni.copy_pasta.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.vfs.VirtualFile
import info.suryasoni.copy_pasta.CopyPastaBundle.message
import info.suryasoni.copy_pasta.ide.ClipboardStatus
import info.suryasoni.copy_pasta.ide.ClipboardWatcher

/**
 * Recreates files from Copy-Pasta text: it decodes, shows a preview, then writes into the selected folder (the
 * parent folder when a file is selected, or the project root from the Edit menu). It stays enabled when the
 * clipboard holds something else and then asks for the text instead (see [PasteFlow]).
 *
 * In the Project view menu, the item shows what's on the clipboard, e.g. "Paste… (3 files, 4.1 KB)". That reads
 * the [ClipboardWatcher]'s cached status, never the clipboard itself, because update() runs on every menu open.
 */
open class PasteAction(private val showClipboardSummary: Boolean = true) : DumbAwareAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(event: AnActionEvent) {
        val presentation = event.presentation
        presentation.isEnabledAndVisible = event.project != null && targetDirectory(event) != null
        if (!showClipboardSummary || !presentation.isVisible) return
        val summary = (ClipboardWatcher.getInstance().status as? ClipboardStatus.WithText)?.summary
        presentation.text = if (summary != null) message("action.CopyPasta.Paste.textWithSummary", summary) else templateText
    }

    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        val target = targetDirectory(event) ?: return
        PasteFlow.pasteFromClipboard(project, target)
    }

    private fun targetDirectory(event: AnActionEvent): VirtualFile? {
        val selected = event.getData(CommonDataKeys.VIRTUAL_FILE)
        return if (selected == null) projectRoot(event.project) else writableDir(if (selected.isDirectory) selected else selected.parent)
    }

    companion object {
        /** Where the status bar widget pastes: the project root, if it can be written to. */
        fun projectRoot(project: Project?): VirtualFile? = writableDir(project?.guessProjectDir())

        private fun writableDir(dir: VirtualFile?): VirtualFile? = dir?.takeIf { it.isValid && it.isWritable }
    }
}

/** The Edit menu entry: same action, fixed text. */
class PasteFromEditMenuAction : PasteAction(showClipboardSummary = false)
