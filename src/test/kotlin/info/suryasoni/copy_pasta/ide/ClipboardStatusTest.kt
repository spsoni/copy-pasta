package info.suryasoni.copy_pasta.ide

import info.suryasoni.copy_pasta.format.Envelope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ClipboardStatusTest {
    private val text = Envelope.encode(ByteArray(100) { it.toByte() }, encrypted = false, summary = "3 files, 4.1 KB")

    @Test
    fun `empty or unrelated clipboard is none`() {
        assertEquals(ClipboardStatus.None, ClipboardStatus.of(null, null))
        assertEquals(ClipboardStatus.None, ClipboardStatus.of("", null))
        assertEquals(ClipboardStatus.None, ClipboardStatus.of("fun main() = println(42)", null))
    }

    @Test
    fun `copy-pasta text is ready until it is pasted`() {
        val ready = ClipboardStatus.of(text, pastedFingerprint = null)
        assertTrue(ready is ClipboardStatus.Ready)
        assertEquals("3 files, 4.1 KB", (ready as ClipboardStatus.Ready).summary)

        val pasted = ClipboardStatus.of(text, pastedFingerprint = ClipboardStatus.fingerprint(text))
        assertEquals(ClipboardStatus.Pasted("3 files, 4.1 KB", false, ready.fingerprint), pasted)
    }

    @Test
    fun `a new copy after a paste is ready again`() {
        val other = Envelope.encode(ByteArray(100) { (it + 1).toByte() }, encrypted = true, summary = "1 file, 100 B")
        val status = ClipboardStatus.of(other, pastedFingerprint = ClipboardStatus.fingerprint(text))
        assertTrue(status is ClipboardStatus.Ready && status.encrypted)
        assertNotEquals(ClipboardStatus.fingerprint(text), ClipboardStatus.fingerprint(other))
    }
}
