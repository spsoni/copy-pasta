package info.suryasoni.copy_pasta.ide

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import info.suryasoni.copy_pasta.format.Envelope
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection

class ClipboardWatcherTest : BasePlatformTestCase() {
    private val text = Envelope.encode(ByteArray(64) { it.toByte() }, encrypted = false, summary = "2 files, 64 B")

    // getContents() is nullable but setContents() isn't, so `contents` is a val in Kotlin: keep the setter call.
    @Suppress("UsePropertyAccessSyntax")
    private fun copy(value: String) {
        CopyPasteManager.getInstance().setContents(StringSelection(value))
        ClipboardWatcher.getInstance().refresh()
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
    }

    fun `test follows the clipboard and publishes changes`() {
        val seen = mutableListOf<ClipboardStatus>()
        ApplicationManager
            .getApplication()
            .messageBus
            .connect(testRootDisposable)
            .subscribe(ClipboardWatcher.TOPIC, ClipboardWatcher.Listener { seen += it })
        val watcher = ClipboardWatcher.getInstance()

        copy(text)
        val ready = watcher.status
        assertTrue(ready is ClipboardStatus.Ready)
        assertEquals("2 files, 64 B", (ready as ClipboardStatus.Ready).summary)

        watcher.markPasted(text)
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        assertTrue(watcher.status is ClipboardStatus.Pasted)

        copy("something else")
        assertEquals(ClipboardStatus.None, watcher.status)

        assertEquals(listOf(ready, watcher.status), listOf(seen.first { it is ClipboardStatus.Ready }, seen.last()))
        assertTrue(seen.any { it is ClipboardStatus.Pasted })
    }

    fun `test clear removes copy-pasta text from the clipboard and its history, nothing else`() {
        val watcher = ClipboardWatcher.getInstance()
        copy("keep me")
        copy(text)
        copy(Envelope.encode(ByteArray(8), encrypted = true, summary = "1 file, 8 B"))
        assertTrue(watcher.status is ClipboardStatus.Ready)

        assertTrue(watcher.clear() >= 2)
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()

        val history = CopyPasteManager.getInstance().allContents.mapNotNull { it.getTransferData(DataFlavor.stringFlavor) as? String }
        assertTrue(history.none { Envelope.looksLikeEnvelope(it) })
        assertTrue("keep me" in history)
        assertEquals(ClipboardStatus.None, watcher.status)
    }
}
