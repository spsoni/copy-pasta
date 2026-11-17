package info.suryasoni.copy_pasta.ui

import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.openapi.util.ThrowableComputable
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.JBFont
import info.suryasoni.copy_pasta.CopyPastaBundle.message
import info.suryasoni.copy_pasta.format.Envelope
import java.awt.Font
import java.io.IOException
import java.nio.file.Files
import javax.swing.JComponent
import javax.swing.event.DocumentEvent

/**
 * Shown by Paste when the clipboard has no usable Copy-Pasta text: the user can paste the message here or open a
 * file that contains it (for example a `.txt` attachment). Returns the text through [ask].
 *
 * Kept cheap on the EDT: the text box is validated once per edit, not on DialogWrapper's 300 ms timer; the full
 * decode check runs once, on Continue, and only for texts up to [FULL_CHECK_CHARS]. A file is read under a
 * progress dialog and never put into the text box (Swing struggles with tens of MB), so choosing it finishes the
 * dialog straight away.
 */
class PasteTextDialog private constructor(private val project: Project, initialText: String?, initialError: String?) :
    DialogWrapper(project) {
    private val textArea =
        JBTextArea(14, 72).apply {
            font = JBFont.create(Font(Font.MONOSPACED, Font.PLAIN, font.size))
            lineWrap = false
        }
    private var result: String? = null
    private var validated: ValidationInfo? = null
    private var dirty = true

    init {
        title = message("pasteText.title")
        setOKButtonText(message("pasteText.ok"))
        if (initialText != null) textArea.text = initialText
        textArea.document.addDocumentListener(
            object : DocumentAdapter() {
                override fun textChanged(e: DocumentEvent) {
                    dirty = true
                }
            },
        )
        init()
        if (initialError != null) setErrorText(initialError, textArea)
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
        if (!dirty) return validated
        dirty = false
        val text = textArea.text
        validated =
            when {
                text.isBlank() -> ValidationInfo(message("pasteText.empty"), textArea)
                !Envelope.looksLikeEnvelope(text) -> ValidationInfo(message("pasteText.notCopyPasta"), textArea)
                else -> null
            }
        return validated
    }

    override fun doOKAction() {
        if (result == null) {
            val text = textArea.text
            // Small texts are fully checked here, so a cut-off or damaged message is reported in the dialog. Large
            // ones are decoded in the background after Continue, and problems come back as a notification.
            if (text.length <= FULL_CHECK_CHARS) {
                try {
                    Envelope.decode(text)
                } catch (e: Envelope.EnvelopeException) {
                    setErrorText(problemMessage(e), textArea)
                    return
                }
            }
            result = text
        }
        super.doOKAction()
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
        val text =
            try {
                ProgressManager.getInstance().runProcessWithProgressSynchronously(
                    ThrowableComputable<String, IOException> { read(file) },
                    message("pasteText.reading"),
                    // Not cancellable: a local read of at most 80 MB is quick, and it avoids handling cancellation.
                    false,
                    project,
                )
            } catch (e: IOException) {
                setErrorText(message("pasteText.fileUnreadable", e.message ?: e.javaClass.simpleName), textArea)
                return
            }
        if (!Envelope.looksLikeEnvelope(text)) {
            setErrorText(message("pasteText.fileNotCopyPasta", file.name), textArea)
            return
        }
        result = text
        super.doOKAction()
    }

    /**
     * Reads a chosen attachment. It usually lives outside the project, so it's read from disk directly rather than
     * through VFS content loading, which could cache tens of MB in the IDE's content storage for a one-off read.
     */
    private fun read(file: VirtualFile): String {
        val path = file.fileSystem.getNioPath(file)
        val bytes = if (path != null) Files.readAllBytes(path) else file.contentsToByteArray()
        return String(bytes, file.charset)
    }

    companion object {
        /** Base64 of the 50 MB copy limit plus line breaks and surrounding text. */
        private const val MAX_FILE_BYTES = 80L * 1024 * 1024
        private const val FULL_CHECK_CHARS = 2_000_000

        /**
         * Returns the pasted or loaded text, or null if the user cancelled. [initialText] and [initialError] reopen
         * the dialog on text that failed, for example a message the chat app cut off.
         */
        fun ask(project: Project, initialText: String? = null, initialError: String? = null): String? {
            val dialog = PasteTextDialog(project, initialText?.takeIf { it.length <= FULL_CHECK_CHARS }, initialError)
            return if (dialog.showAndGet()) dialog.result else null
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
