package info.suryasoni.copy_pasta.format

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Story 003: rules added after the independent review of spec/FORMAT.md against this code. */
class HardeningTest {
    private fun zipOf(name: String, bytes: ByteArray = "x".toByteArray()): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry(name))
            zip.write(bytes)
            zip.closeEntry()
        }
        return out.toByteArray()
    }

    @Test
    fun `control characters and NUL in entry names are rejected`() {
        for (name in listOf("a\u0001b.txt", "dir/\u001F.txt", "nul\u0000.txt")) {
            assertThrows(name, InvalidArchiveException::class.java) { ZipCodec.decode(zipOf(name)) }
        }
    }

    @Test
    fun `truncated deflate data is an invalid archive, not an unexpected error`() {
        val zip = zipOf("big.txt", ByteArray(100_000) { (it * 31).toByte() })
        assertThrows(InvalidArchiveException::class.java) { ZipCodec.decode(zip.copyOfRange(0, zip.size / 2)) }
    }

    @Test
    fun `an entry name that isn't valid UTF-8 is an invalid archive`() {
        val zip = zipOf("aaaa.txt")
        val at = (0..zip.size - 4).first { String(zip, it, 4, Charsets.ISO_8859_1) == "aaaa" } // local header name
        zip[at] = 0xC3.toByte() // a lead byte followed by ASCII: malformed
        assertThrows(InvalidArchiveException::class.java) { ZipCodec.decode(zip) }
    }

    @Test(timeout = 2000)
    fun `a CPv2 payload too short for a GCM tag is rejected before key derivation`() {
        // 5,000,000 iterations would take seconds; the size check must come first.
        val payload = ByteBuffer.allocate(8).put("CPv2".toByteArray()).putInt(5_000_000).array() + ByteArray(16 + 12 + 15)
        assertThrows(InvalidArchiveException::class.java) { Crypto.decrypt(payload, "pw".toCharArray()) }
    }

    @Test
    fun `passphrases with unpaired surrogates are refused`() {
        assertTrue(Crypto.isWellFormed("ok ✓ 😀".toCharArray()))
        for (bad in listOf("\uD800", "a\uDC00b", "x\uD83D")) {
            assertEquals(bad, false, Crypto.isWellFormed(bad.toCharArray()))
            assertThrows(IllegalArgumentException::class.java) { Crypto.encrypt(ByteArray(1), bad.toCharArray(), iterations = 1) }
        }
    }

    @Test
    fun `markers are case-insensitive for ASCII letters only`() {
        val text = Envelope.encode(zipOf("a.txt"), encrypted = false, "1 file, 1 byte")
        val lowerMarkers = text.replace("BEGIN COPY-PASTA", "begin copy-pasta").replace("END COPY-PASTA", "end Copy-Pasta")
        assertEquals(1, ZipCodec.decode(Envelope.decode(lowerMarkers).payload).size)
        for (exotic in listOf(text.replace("PASTA", "PAſTA"), text.replace("BEGIN", "BEGıN"), text.replace("BEGIN", "BEGİN"))) {
            val error = assertThrows(Envelope.EnvelopeException::class.java) { Envelope.decode(exotic) }
            assertEquals(Envelope.Problem.NOT_FOUND, error.problem)
        }
    }

    @Test
    fun `a non-ASCII character in a complete body is corrupted, whatever the length`() {
        // 7 ASCII characters and an emoji (2 UTF-16 units): shorter than declared in UTF-16, so this used to be
        // reported as INCOMPLETE. The END marker is there, so the text is damaged, not cut off.
        val text = "-----BEGIN COPY-PASTA-----\nFormat: 2; length: 10\nAAAAAAA😀\n-----END COPY-PASTA-----\n"
        val error = assertThrows(Envelope.EnvelopeException::class.java) { Envelope.decode(text) }
        assertEquals(Envelope.Problem.CORRUPTED, error.problem)
    }
}
