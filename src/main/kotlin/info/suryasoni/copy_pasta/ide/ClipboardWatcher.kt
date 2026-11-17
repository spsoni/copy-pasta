package info.suryasoni.copy_pasta.ide

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationActivationListener
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.wm.IdeFrame
import com.intellij.util.messages.Topic
import java.awt.datatransfer.DataFlavor

/**
 * Keeps [status] in step with the clipboard, for the status bar widget and the Paste menu text.
 *
 * It re-checks when the IDE's clipboard changes and when an IDE window is activated, which is when text copied
 * in a chat app becomes visible to the IDE. It never polls the system clipboard and never changes its contents.
 */
@Service(Service.Level.APP)
class ClipboardWatcher : Disposable {
    @Volatile
    var status: ClipboardStatus = ClipboardStatus.None
        private set

    @Volatile
    private var pastedFingerprint: Long? = null

    init {
        CopyPasteManager.getInstance().addContentChangedListener({ _, _ -> refresh() }, this)
        ApplicationManager
            .getApplication()
            .messageBus
            .connect(this)
            .subscribe(
                ApplicationActivationListener.TOPIC,
                object : ApplicationActivationListener {
                    override fun applicationActivated(ideFrame: IdeFrame) = refresh()
                },
            )
        refresh()
    }

    /** Re-reads the clipboard on the EDT and publishes a change, if any, on [TOPIC]. */
    fun refresh() {
        ApplicationManager.getApplication().invokeLater({ update(readClipboard()) }, ModalityState.any())
    }

    @Volatile
    private var copiedFingerprint: Long? = null

    /** Records that [text] was pasted, so the widget shows it as done. The clipboard itself is left alone. */
    fun markPasted(text: String) {
        pastedFingerprint = ClipboardStatus.fingerprint(text)
        refresh()
    }

    /** Records that this IDE copied [text], so the sender isn't invited to paste their own files back. */
    fun markCopied(text: String) {
        copiedFingerprint = ClipboardStatus.fingerprint(text)
        refresh()
    }

    private fun update(text: String?) {
        val next = ClipboardStatus.of(text, pastedFingerprint, copiedFingerprint)
        if (next == status) return
        status = next
        ApplicationManager.getApplication().messageBus.syncPublisher(TOPIC).statusChanged(next)
    }

    private fun readClipboard(): String? = try {
        CopyPasteManager.getInstance().getContents<String>(DataFlavor.stringFlavor)
    } catch (e: Exception) {
        // Another app may hold the system clipboard, or offer a flavor that fails to convert. Treat it as empty.
        LOG.debug("Could not read the clipboard", e)
        null
    }

    override fun dispose() = Unit

    fun interface Listener {
        fun statusChanged(status: ClipboardStatus)
    }

    companion object {
        private val LOG = logger<ClipboardWatcher>()

        @Topic.AppLevel
        val TOPIC: Topic<Listener> = Topic.create("Copy-Pasta clipboard status", Listener::class.java)

        fun getInstance(): ClipboardWatcher = service()
    }
}
