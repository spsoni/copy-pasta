package info.suryasoni.copy_pasta.settings

import com.intellij.openapi.options.Configurable
import com.intellij.openapi.components.service
import com.intellij.util.ui.JBUI
import java.util.*
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JTextArea
import javax.swing.JTextField
import javax.swing.BoxLayout
import javax.swing.JLabel
import javax.swing.JCheckBox
import java.awt.GridBagLayout
import java.awt.GridBagConstraints
import java.awt.Insets
import java.awt.Dimension
import javax.swing.JScrollPane

class CopyPastaSettingsConfigurable : Configurable {

    private var settingsPanel: JPanel? = null
    private var excludePatternsTextArea: JTextArea? = null
    private var maxBase64SizeTextField: JTextField? = null
    private var encryptionKeyTextField: JTextField? = null
    private var enableDecryptionCheckBox: JCheckBox? = null

    override fun createComponent(): JComponent? {
        settingsPanel = JPanel()
        settingsPanel?.layout = BoxLayout(settingsPanel, BoxLayout.Y_AXIS)

        val formPanel = JPanel(GridBagLayout())
        val constraints = GridBagConstraints().apply {
            fill = GridBagConstraints.HORIZONTAL
            gridx = 0
            gridy = GridBagConstraints.RELATIVE
            insets = JBUI.insets(5)
        }

        val excludePatternsLabel = JLabel("Exclude Patterns:")
        excludePatternsTextArea = JTextArea(4, 50).apply { lineWrap = true; wrapStyleWord = true }
        constraints.weightx = 0.1
        formPanel.add(excludePatternsLabel, constraints)
        constraints.weightx = 0.9
        formPanel.add(JScrollPane(excludePatternsTextArea), constraints)

        val maxBase64SizeLabel = JLabel("Max Base64 Size (bytes):")
        maxBase64SizeTextField = JTextField(10).apply {
            maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height)
        }
        constraints.weightx = 0.1
        formPanel.add(maxBase64SizeLabel, constraints)
        constraints.weightx = 0.9
        formPanel.add(maxBase64SizeTextField, constraints)

        val encryptionKeyLabel = JLabel("Encryption Key:")
        encryptionKeyTextField = JTextField(20).apply {
            maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height)
        }
        constraints.weightx = 0.1
        formPanel.add(encryptionKeyLabel, constraints)
        constraints.weightx = 0.9
        formPanel.add(encryptionKeyTextField, constraints)

        val enableDecryptionLabel = JLabel("Enable Decryption:")
        enableDecryptionCheckBox = JCheckBox().apply {
            maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height)
        }
        constraints.weightx = 0.1
        formPanel.add(enableDecryptionLabel, constraints)
        constraints.weightx = 0.9
        formPanel.add(enableDecryptionCheckBox, constraints)

        settingsPanel?.add(formPanel)

        return settingsPanel
    }

    override fun isModified(): Boolean {
        val settings = service<CopyPastaSettingsState>().state
        return excludePatternsTextArea?.text != settings.excludePatterns ||
                parseHumanReadableSize(maxBase64SizeTextField?.text ?: "1 MB") != settings.maxBase64Size ||
                encryptionKeyTextField?.text != settings.encryptionKey ||
                enableDecryptionCheckBox?.isSelected != settings.enableDecryption
    }

    override fun apply() {
        val settings = service<CopyPastaSettingsState>().state
        settings.excludePatterns = excludePatternsTextArea?.text ?: ""
        settings.maxBase64Size = parseHumanReadableSize(maxBase64SizeTextField?.text ?: "1 MB")
        settings.encryptionKey = encryptionKeyTextField?.text ?: "default_key_123456"
        settings.enableDecryption = enableDecryptionCheckBox?.isSelected ?: true
    }

    override fun reset() {
        val settings = service<CopyPastaSettingsState>().state
        excludePatternsTextArea?.text = settings.excludePatterns
        maxBase64SizeTextField?.text = humanReadableByteCount(settings.maxBase64Size.toLong())
        encryptionKeyTextField?.text = settings.encryptionKey
        enableDecryptionCheckBox?.isSelected = settings.enableDecryption
    }

    override fun getDisplayName(): String {
        return "Copy-Pasta Settings"
    }

    private fun parseHumanReadableSize(size: String): Int {
        val unit = size.takeLast(2).trim().uppercase(Locale.getDefault())
        val value = size.dropLast(2).trim().toDoubleOrNull() ?: return 1 * 1024 * 1024 // Default to 1 MB if parsing fails

        return when (unit) {
            "KB" -> (value * 1024).toInt()
            "MB" -> (value * 1024 * 1024).toInt()
            "GB" -> (value * 1024 * 1024 * 1024).toInt()
            else -> value.toInt() // Assume bytes if no unit
        }
    }

    private fun humanReadableByteCount(bytes: Long, si: Boolean = true): String {
        val unit = if (si) 1000 else 1024
        if (bytes < unit) return "$bytes B"
        val exp = (Math.log(bytes.toDouble()) / Math.log(unit.toDouble())).toInt()
        val pre = (if (si) "kMGTPE" else "KMGTPE")[exp - 1] + if (si) "" else "i"
        return String.format("%.1f %sB", bytes / Math.pow(unit.toDouble(), exp.toDouble()), pre)
    }
}
