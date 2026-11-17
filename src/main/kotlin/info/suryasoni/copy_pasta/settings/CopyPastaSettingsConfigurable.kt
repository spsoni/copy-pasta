package info.suryasoni.copy_pasta.settings

import com.intellij.openapi.components.service
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.dsl.builder.COLUMNS_LARGE
import com.intellij.ui.dsl.builder.bindIntText
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.columns
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.builder.rows

class CopyPastaSettingsConfigurable : BoundConfigurable("Copy-Pasta") {

    override fun createPanel(): DialogPanel {
        val state = service<CopyPastaSettingsState>().state
        return panel {
            row("Exclude patterns:") {
                textArea()
                    .bindText(state::excludePatterns)
                    .rows(4)
                    .columns(COLUMNS_LARGE)
                    .comment("Comma- or newline-separated globs (<code>*</code>, <code>?</code>), matched against relative paths.")
            }
            row("Max clipboard payload (bytes):") {
                intTextField(1024..Int.MAX_VALUE).bindIntText(state::maxBase64Size)
            }
            group("Encryption") {
                row {
                    checkBox("Encrypt copied content").bindSelected(state::enableEncryption)
                }
                row {
                    checkBox("Decrypt pasted content").bindSelected(state::enableDecryption)
                }
                row("Encryption key:") {
                    passwordField()
                        .bindText(state::encryptionKey)
                        .columns(COLUMNS_LARGE)
                        .comment("Sender and recipient must use the same key. Change the default to keep content private.")
                }
            }
        }
    }
}
