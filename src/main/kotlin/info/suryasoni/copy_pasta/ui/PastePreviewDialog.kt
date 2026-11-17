package info.suryasoni.copy_pasta.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.CheckBoxList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.JBUI
import info.suryasoni.copy_pasta.CopyPastaBundle.message
import info.suryasoni.copy_pasta.format.FileEntry
import info.suryasoni.copy_pasta.ide.PasteStatus
import info.suryasoni.copy_pasta.ide.PlannedFile
import javax.swing.JComponent

/**
 * Shows what a paste will do before anything is written: each file marked new, overwrite or identical, with
 * checkboxes. Identical files start unchecked. Entries that clash with an existing folder are listed as skipped.
 */
class PastePreviewDialog(project: Project, private val target: VirtualFile, private val planned: List<PlannedFile>) :
    DialogWrapper(project) {
    private val pastable = planned.filter { it.status != PasteStatus.BLOCKED }
    private val blocked = planned.filter { it.status == PasteStatus.BLOCKED }
    private val list = CheckBoxList<PlannedFile>()

    init {
        title = message("preview.title")
        pastable.forEach { list.addItem(it, label(it), it.status != PasteStatus.IDENTICAL) }
        list.setCheckBoxListListener { _, _ -> updateOkButton() }
        init()
        updateOkButton()
    }

    val selectedFiles: List<FileEntry>
        get() = pastable.filter { list.isItemSelected(it) }.map { it.entry }

    override fun createCenterPanel(): JComponent = panel {
        row { label(message("preview.target", target.presentableUrl)) }
        row {
            cell(JBScrollPane(list).apply { preferredSize = JBUI.size(620, 320) }).align(Align.FILL)
        }.resizableRow()
        row {
            comment(
                message(
                    "preview.summary",
                    count(PasteStatus.NEW),
                    count(PasteStatus.OVERWRITE),
                    count(PasteStatus.IDENTICAL),
                ),
            )
        }
        if (blocked.isNotEmpty()) {
            val names = blocked.take(3).joinToString { StringUtil.escapeXmlEntities(it.entry.path) }
            row { comment(message("preview.blocked", blocked.size, names)) }
        }
    }

    override fun getDimensionServiceKey(): String = "CopyPasta.PastePreviewDialog"

    private fun count(status: PasteStatus) = planned.count { it.status == status }

    private fun label(file: PlannedFile): String {
        val status =
            when (file.status) {
                PasteStatus.NEW -> message("preview.status.new")
                PasteStatus.OVERWRITE -> message("preview.status.overwrite")
                PasteStatus.IDENTICAL -> message("preview.status.identical")
                PasteStatus.BLOCKED -> ""
            }
        val executable = if (file.entry.executable) ", " + message("preview.executable") else ""
        // Starts with the status, never the path, so a path like "<html>…" can't switch Swing into HTML rendering.
        return "[$status]  ${file.entry.path}  (${StringUtil.formatFileSize(file.entry.bytes.size.toLong())}$executable)"
    }

    private fun updateOkButton() {
        val count = pastable.count { list.isItemSelected(it) }
        setOKButtonText(message("preview.ok", count))
        isOKActionEnabled = count > 0
    }
}
