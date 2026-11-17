package info.suryasoni.copy_pasta.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.dsl.builder.COLUMNS_MEDIUM
import com.intellij.ui.dsl.builder.columns
import com.intellij.ui.dsl.builder.panel
import info.suryasoni.copy_pasta.CopyPastaBundle.message
import javax.swing.JComponent

/** Asks for a passphrase. With [confirm], asks twice (used when encrypting). The passphrase is never stored. */
class PassphraseDialog private constructor(project: Project?, private val confirm: Boolean) : DialogWrapper(project) {
    private val passphraseField = JBPasswordField()
    private val confirmField = JBPasswordField()

    init {
        title = message(if (confirm) "passphrase.title.encrypt" else "passphrase.title.decrypt")
        init()
    }

    override fun createCenterPanel(): JComponent = panel {
        row(message("passphrase.label")) {
            cell(passphraseField)
                .columns(COLUMNS_MEDIUM)
                .focused()
                .validationOnApply { if (it.password.isEmpty()) error(message("passphrase.empty")) else null }
        }
        if (confirm) {
            row(message("passphrase.confirm")) {
                cell(confirmField)
                    .columns(COLUMNS_MEDIUM)
                    .validationOnApply {
                        if (!it.password.contentEquals(passphraseField.password)) error(message("passphrase.mismatch")) else null
                    }
            }
            row { comment(message("passphrase.hint")) }
        }
    }

    companion object {
        /** Returns the passphrase, or null if the user cancelled. Clear the array after use. */
        fun ask(project: Project?, confirm: Boolean): CharArray? {
            val dialog = PassphraseDialog(project, confirm)
            val passphrase = if (dialog.showAndGet()) dialog.passphraseField.password else null
            // Best effort: drop the text from the fields' documents (the JVM may still hold copies until GC).
            dialog.passphraseField.text = ""
            dialog.confirmField.text = ""
            return passphrase
        }
    }
}
