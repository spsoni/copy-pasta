package info.suryasoni.copy_pasta.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.IconLoader
import com.intellij.openapi.wm.CustomStatusBarWidget
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.StatusBarWidgetFactory
import com.intellij.ui.ClickListener
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import info.suryasoni.copy_pasta.CopyPastaBundle.message
import info.suryasoni.copy_pasta.actions.PasteAction
import info.suryasoni.copy_pasta.actions.PasteFlow
import info.suryasoni.copy_pasta.ide.ClipboardStatus
import info.suryasoni.copy_pasta.ide.ClipboardWatcher
import java.awt.Cursor
import java.awt.event.MouseEvent
import javax.swing.JComponent
import javax.swing.Timer

/** Registers [ClipboardStatusWidget]. Users can hide it from the status bar's context menu. */
class ClipboardStatusWidgetFactory : StatusBarWidgetFactory {
    override fun getId() = ClipboardStatusWidget.ID

    override fun getDisplayName() = message("widget.displayName")

    override fun createWidget(project: Project): StatusBarWidget = ClipboardStatusWidget(project)
}

/**
 * A status bar icon that appears while the clipboard holds Copy-Pasta text. It pulses briefly when new text
 * arrives, dims once that text has been pasted, and fades out when the clipboard holds something else. Clicking
 * it pastes into the project root (after the usual preview).
 */
class ClipboardStatusWidget(private val project: Project) : CustomStatusBarWidget {
    private val readyIcon = AllIcons.Actions.MenuPaste
    private val dimIcon = IconLoader.getDisabledIcon(readyIcon)
    private val label =
        JBLabel(readyIcon).apply {
            border = JBUI.Borders.empty(0, 6)
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            isVisible = false
        }
    private var animation: Timer? = null
    private var shownFingerprint: Long? = null

    init {
        object : ClickListener() {
            override fun onClick(event: MouseEvent, clickCount: Int): Boolean {
                val target = PasteAction.projectRoot(project) ?: return false
                PasteFlow.pasteFromClipboard(project, target)
                return true
            }
        }.installOn(label)
        ApplicationManager
            .getApplication()
            .messageBus
            .connect(this)
            .subscribe(ClipboardWatcher.TOPIC, ClipboardWatcher.Listener { render(it) })
        render(ClipboardWatcher.getInstance().status, animate = false)
    }

    override fun ID() = ID

    override fun getComponent(): JComponent = label

    private fun render(status: ClipboardStatus, animate: Boolean = true) {
        stopAnimation()
        when (status) {
            is ClipboardStatus.Ready -> {
                label.icon = readyIcon
                label.toolTipText = readyTooltip(status.summary, status.encrypted)
                label.isVisible = true
                if (animate && status.fingerprint != shownFingerprint) pulse()
                shownFingerprint = status.fingerprint
            }
            is ClipboardStatus.Pasted -> {
                label.icon = dimIcon
                label.toolTipText = message("widget.tooltip.pasted", status.summary ?: message("widget.summary.unknown"))
                label.isVisible = true
                shownFingerprint = status.fingerprint
            }
            ClipboardStatus.None -> {
                shownFingerprint = null
                if (animate && label.isVisible) fadeOut() else label.isVisible = false
            }
        }
    }

    private fun readyTooltip(summary: String?, encrypted: Boolean): String {
        val what = summary ?: message("widget.summary.unknown")
        return message(if (encrypted) "widget.tooltip.readyEncrypted" else "widget.tooltip.ready", what)
    }

    /** Three quick blinks, then steady: noticeable once, never distracting. */
    private fun pulse() {
        var ticks = 0
        animation =
            Timer(PULSE_MS) {
                ticks++
                label.icon = if (ticks % 2 == 1) dimIcon else readyIcon
                if (ticks >= PULSE_TICKS) stopAnimation(restore = readyIcon)
            }.apply { start() }
    }

    /** Dims, then disappears. */
    private fun fadeOut() {
        label.icon = dimIcon
        animation =
            Timer(FADE_MS) { stopAnimation(hide = true) }.apply {
                isRepeats = false
                start()
            }
    }

    private fun stopAnimation(restore: javax.swing.Icon? = null, hide: Boolean = false) {
        animation?.stop()
        animation = null
        restore?.let { label.icon = it }
        if (hide) label.isVisible = false
    }

    override fun dispose() = stopAnimation()

    companion object {
        const val ID = "CopyPasta.ClipboardStatus"
        private const val PULSE_MS = 180
        private const val PULSE_TICKS = 6
        private const val FADE_MS = 700
    }
}
