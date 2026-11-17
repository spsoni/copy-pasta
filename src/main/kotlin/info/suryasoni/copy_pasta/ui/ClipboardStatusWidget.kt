package info.suryasoni.copy_pasta.ui

import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.Separator
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.util.IconLoader
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.CustomStatusBarWidget
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.StatusBarWidgetFactory
import com.intellij.ui.ClickListener
import com.intellij.ui.awt.RelativePoint
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import info.suryasoni.copy_pasta.CopyPastaBundle.message
import info.suryasoni.copy_pasta.actions.PasteAction
import info.suryasoni.copy_pasta.actions.PasteFlow
import info.suryasoni.copy_pasta.ide.ClipboardStatus
import info.suryasoni.copy_pasta.ide.ClipboardWatcher
import info.suryasoni.copy_pasta.ide.Notifier
import java.awt.Cursor
import java.awt.Point
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
 * arrives, turns grey once that text has been pasted, and fades out when the clipboard holds something else.
 * Clicking it opens a menu: paste into the project root, paste into a chosen folder, or clear the text.
 */
class ClipboardStatusWidget(private val project: Project) : CustomStatusBarWidget {
    // The plugin's own artwork in colour (with a dark-theme variant), so the icon stands out among the status bar's
    // grey icons. Grey means "nothing new": the pulse alternates between the two.
    private val readyIcon = IconLoader.getIcon("/icons/clipboardReady.svg", ClipboardStatusWidget::class.java)
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
                showMenu()
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
            is ClipboardStatus.Copied -> {
                // The sender's own copy: a steady icon confirms it, without the pulse that announces incoming text.
                label.icon = readyIcon
                label.toolTipText = message("widget.tooltip.copied", status.summary ?: message("widget.summary.unknown"))
                label.isVisible = true
                shownFingerprint = status.fingerprint
            }
            ClipboardStatus.None -> {
                shownFingerprint = null
                if (animate && label.isVisible) fadeOut() else label.isVisible = false
            }
        }
    }

    /**
     * The click menu: paste into the project root or a chosen folder, or clear Copy-Pasta text from the clipboard.
     * A menu rather than a direct paste, so the sender's own copy can't be pasted back by a stray click.
     */
    private fun showMenu() {
        val status = ClipboardWatcher.getInstance().status as? ClipboardStatus.WithText ?: return
        val group =
            DefaultActionGroup(
                DumbAwareAction.create(message("widget.menu.pasteRoot")) { pasteInto(PasteAction.projectRoot(project)) },
                DumbAwareAction.create(message("widget.menu.pasteFolder")) { chooseFolderAndPaste() },
                Separator.getInstance(),
                DumbAwareAction.create(message("widget.menu.clear")) { clear() },
            )
        val popup =
            JBPopupFactory
                .getInstance()
                .createActionGroupPopup(
                    message("widget.menu.title", status.summary ?: message("widget.summary.unknown")),
                    group,
                    SimpleDataContext.getProjectContext(project),
                    JBPopupFactory.ActionSelectionAid.MNEMONICS,
                    true,
                )
        // Open upwards from the icon, as status bar popups do.
        popup.show(RelativePoint(label, Point(0, -popup.content.preferredSize.height)))
    }

    private fun pasteInto(target: VirtualFile?) {
        if (target == null) {
            Notifier.error(project, message("widget.noProjectRoot"))
        } else {
            PasteFlow.pasteFromClipboard(project, target)
        }
    }

    private fun chooseFolderAndPaste() {
        val descriptor = FileChooserDescriptorFactory.singleDir().withTitle(message("widget.menu.pasteFolder.title"))
        FileChooser.chooseFile(descriptor, project, label, PasteAction.projectRoot(project)) { folder ->
            if (folder.isWritable) {
                pasteInto(
                    folder,
                )
            } else {
                Notifier.error(project, message("widget.folderNotWritable", folder.presentableUrl))
            }
        }
    }

    private fun clear() {
        val removed = ClipboardWatcher.getInstance().clear()
        Notifier.notify(project, message("widget.cleared", removed), NotificationType.INFORMATION)
    }

    private fun readyTooltip(summary: String?, encrypted: Boolean): String {
        val what = summary ?: message("widget.summary.unknown")
        return message(if (encrypted) "widget.tooltip.readyEncrypted" else "widget.tooltip.ready", what)
    }

    /** A few blinks between colour and grey, then steady colour: noticeable once, never distracting. */
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

        // Four blinks over two seconds: easy to catch out of the corner of an eye, then steady.
        private const val PULSE_MS = 250
        private const val PULSE_TICKS = 8
        private const val FADE_MS = 700
    }
}
