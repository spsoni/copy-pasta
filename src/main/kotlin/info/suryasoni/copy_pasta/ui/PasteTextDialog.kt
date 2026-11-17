package info.suryasoni.copy_pasta.ui

import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.JBFont
import info.suryasoni.copy_pasta.CopyPastaBundle.message
import info.suryasoni.copy_pasta.format.Envelope
import java.io.IOException
import javax.swing.JComponent

/**
 * Shown by Paste when the clipboard has no Copy-Pasta text: the user can paste the message here or open a file
 * that contains it (for example a `.txt` attachment). Problems with the text are shown inline, so there's no dead
 * end. Returns the text through [ask].
 */
class PasteTextDialog private constructor(private val project: Project) : DialogWrapper(project) {
    private val textArea =
        JBTextArea(14, 72).apply {
            font = JBFont.create(java.awt.Font(java.awt.Font.MONOSPACED, java.awt.Font.PLAIN, font.size))
            lineWrap = false
        }

    init {
        title = message("pasteText.title")
        setOKButtonText(message("pasteText.ok"))
        init()
    }

    override fun createCenterPanel(): JComponent = panel {
        row { text(message("pasteText.explanation")) }
        row {
            scrollCell(textArea)
                .align(Align.FILL)
                .resizableColumn()
                .focused()
        }.resizableRow()
        row {
            button(message("pasteText.openFile")) { openFile() }
            comment(message("pasteText.fileHint"))
        }
    }

    override fun doValidate(): ValidationInfo? {
        val text = textArea.text
        if (text.isBlank()) return ValidationInfo(message("pasteText.empty"), textArea)
        if (!Envelope.looksLikeEnvelope(text)) return ValidationInfo(message("pasteText.notCopyPasta"), textArea)
        // Small texts are fully checked here so a cut-off or damaged message is reported in the dialog. Large ones
        // are decoded in the background after OK, and problems come back as a notification.
        if (text.length <= FULL_CHECK_CHARS) {
            try {
                Envelope.decode(text)
            } catch (e: Envelope.EnvelopeException) {
                return ValidationInfo(problemMessage(e), textArea)
            }
        }
        return null
    }

    private fun openFile() {
        val descriptor = FileChooserDescriptorFactory.singleFile().withTitle(message("pasteText.openFile.title"))
        FileChooser.chooseFile(descriptor, project, contentPanel, null) { file -> load(file) }
    }

    private fun load(file: VirtualFile) {
        if (file.length > MAX_FILE_BYTES) {
            setErrorText(message("pasteText.fileTooLarge", StringUtil.formatFileSize(MAX_FILE_BYTES)), textArea)
            return
        }
        try {
            textArea.text = String(file.contentsToByteArray(), file.charset)
            textArea.caretPosition = 0
            setErrorText(null)
        } catch (e: IOException) {
            setErrorText(message("pasteText.fileUnreadable", e.message ?: e.javaClass.simpleName), textArea)
        }
    }

    companion object {
        /** Base64 of the 50 MB copy limit plus line breaks and surrounding text. */
        private const val MAX_FILE_BYTES = 80L * 1024 * 1024
        private const val FULL_CHECK_CHARS = 2_000_000

        /** Returns the pasted or loaded text, or null if the user cancelled. */
        fun ask(project: Project): String? {
            val dialog = PasteTextDialog(project)
            return if (dialog.showAndGet()) dialog.textArea.text else null
        }

        /** The user-facing message for an [Envelope.EnvelopeException]. */
        fun problemMessage(e: Envelope.EnvelopeException): String = when (e.problem) {
            Envelope.Problem.NOT_FOUND -> message("paste.notFound")
            Envelope.Problem.INCOMPLETE ->
                if (e.expected > 0) message("paste.incompleteOf", e.received, e.expected) else message("paste.incomplete")
            Envelope.Problem.CORRUPTED -> message("paste.corrupted")
            Envelope.Problem.NEWER_VERSION -> message("paste.newerVersion")
        }
    }
}
