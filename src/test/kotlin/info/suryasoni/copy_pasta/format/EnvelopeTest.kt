package info.suryasoni.copy_pasta.format

import info.suryasoni.copy_pasta.format.Envelope.EnvelopeException
import info.suryasoni.copy_pasta.format.Envelope.Problem
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test
import java.io.File
import kotlin.random.Random

class EnvelopeTest {
    private val payload = Random(42).nextBytes(3000)
    private val text = Envelope.encode(payload, encrypted = false, summary = "2 files, 3 KB")

    @Test
    fun `round trip`() {
        val decoded = Envelope.decode(text)
        assertArrayEquals(payload, decoded.payload)
        assertFalse(decoded.encrypted)
    }

    @Test
    fun `encrypted marker is detected`() {
        val decoded = Envelope.decode(Envelope.encode(payload, encrypted = true, summary = "x"))
        assertTrue(decoded.encrypted)
    }

    @Test
    fun `text is self-describing and wrapped for chat`() {
        val lines = text.lines()
        assertEquals("-----BEGIN COPY-PASTA-----", lines.first())
        assertTrue(lines[1].contains("2 files, 3 KB") && lines[1].contains(Envelope.HOME_URL))
        assertTrue(lines.all { it.length <= 200 })
    }

    @Test
    fun `survives chat and email mangling`() {
        val mangled =
            "hey, here is the code:\r\n\r\n" +
                text.lines().joinToString("\r\n") { "> $it" } + // email quoting + CRLF
                "\r\nthanks!"
        assertArrayEquals(payload, Envelope.decode(mangled).payload)

        val rewrapped = text.replace(Regex("(?m)^([A-Za-z0-9+/=]{40})")) { it.value + "\n" } // chat re-wraps long lines
        assertArrayEquals(payload, Envelope.decode(rewrapped).payload)
    }

    @Test
    fun `wrapped human line does not corrupt the body`() {
        val wrapped = text.replaceFirst(". Paste it with", ".\nPaste it with")
        assertArrayEquals(payload, Envelope.decode(wrapped).payload)
    }

    @Test
    fun `truncated text is reported as incomplete with counts`() {
        val cut = text.substring(0, text.length / 2)
        val e = assertThrows(EnvelopeException::class.java) { Envelope.decode(cut) }
        assertEquals(Problem.INCOMPLETE, e.problem)
        assertEquals(4000, e.expected) // 3000 bytes -> 4000 Base64 characters
        assertTrue(e.received in 1 until 4000)
    }

    @Test
    fun `missing lines in the middle are reported as incomplete`() {
        val lines = text.lines().toMutableList()
        lines.removeAt(5)
        val e = assertThrows(EnvelopeException::class.java) { Envelope.decode(lines.joinToString("\n")) }
        assertEquals(Problem.INCOMPLETE, e.problem)
    }

    @Test
    fun `altered body is reported as corrupted`() {
        val altered = text.replaceFirst(Regex("(?m)^([A-Za-z0-9+/]{10})"), "$1!!")
        assertEquals(Problem.CORRUPTED, assertThrows(EnvelopeException::class.java) { Envelope.decode(altered) }.problem)
    }

    @Test
    fun `plain text is not mistaken for an envelope`() {
        assertFalse(Envelope.looksLikeEnvelope("just some code"))
        assertEquals(Problem.NOT_FOUND, assertThrows(EnvelopeException::class.java) { Envelope.decode("just some code") }.problem)
    }

    @Test
    fun `body decodes without the plugin using base64 and unzip`() {
        val hasTools = listOf("sh", "base64", "unzip", "awk").all { tool ->
            ProcessBuilder("sh", "-c", "command -v $tool").start().waitFor() ==
                0
        }
        Assume.assumeTrue("needs sh, base64, awk and unzip", hasTools)

        val zip = ZipCodec.encode(listOf(FileEntry("proj/hello.txt", "hi there".toByteArray())))
        val file = File.createTempFile("copy-pasta", ".txt").apply { writeText(Envelope.encode(zip, false, "1 file")) }
        try {
            // The README's recipe: take the lines between the Format line and END, Base64-decode, unzip.
            val script =
                "awk '/^Format:/{on=1;next} /^-----END/{on=0} on' \"$1\" | base64 -d > \"$1.zip\" && " +
                    "unzip -p \"$1.zip\" proj/hello.txt"
            val process = ProcessBuilder("sh", "-c", script, "sh", file.path).redirectErrorStream(true).start()
            val output = process.inputStream.readBytes().decodeToString()
            assertEquals(output, 0, process.waitFor())
            assertEquals("hi there", output)
        } finally {
            file.delete()
            File(file.path + ".zip").delete()
        }
    }

    @Test
    fun `newer format is refused with a clear problem`() {
        val newer = text.replace("Format: 2;", "Format: 9;")
        assertEquals(Problem.NEWER_VERSION, assertThrows(EnvelopeException::class.java) { Envelope.decode(newer) }.problem)
    }
}
