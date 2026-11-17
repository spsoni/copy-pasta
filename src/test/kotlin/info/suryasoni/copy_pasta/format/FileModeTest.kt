package info.suryasoni.copy_pasta.format

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FileModeTest {
    private fun octal(text: String) = text.toInt(8)

    @Test
    fun `safe keeps private and executable files, never group or world writable, owner can always edit`() {
        assertEquals(octal("600"), FileMode.safe(octal("600")))
        assertEquals(octal("755"), FileMode.safe(octal("755")))
        assertEquals(octal("644"), FileMode.safe(octal("644")))
        assertEquals(octal("700"), FileMode.safe(octal("700")))
        assertEquals(octal("640"), FileMode.safe(octal("640")))
        assertEquals(octal("755"), FileMode.safe(octal("777")))
        assertEquals(octal("644"), FileMode.safe(octal("664")))
        assertEquals(octal("644"), FileMode.safe(octal("444"))) // read-only becomes editable for the owner
        assertEquals(octal("600"), FileMode.safe(0))
        assertEquals(octal("755"), FileMode.safe(octal("4777"))) // setuid and friends are dropped
    }

    @Test
    fun `applied never widens group or other access beyond the recipient's umask`() {
        val umask022 = octal("644")
        val umask002 = octal("664")
        val umask077 = octal("600")
        assertEquals(octal("755"), FileMode.applied(octal("755"), umask022)) // read allows execute
        assertEquals(octal("644"), FileMode.applied(octal("644"), umask022))
        assertEquals(octal("600"), FileMode.applied(octal("600"), umask022)) // narrowing follows the sender
        assertEquals(octal("755"), FileMode.applied(octal("777"), umask002)) // still never group-writable
        assertEquals(octal("600"), FileMode.applied(octal("644"), umask077))
        assertEquals(octal("700"), FileMode.applied(octal("755"), umask077)) // owner bits follow the sender
        assertEquals(octal("640"), FileMode.applied(octal("644"), octal("640"))) // existing file keeps its limit
    }

    @Test
    fun `describe matches ls`() {
        assertEquals("rwxr-xr-x", FileMode.describe(octal("755")))
        assertEquals("rw-------", FileMode.describe(octal("600")))
        assertEquals("r--r-----", FileMode.describe(octal("440")))
    }

    @Test
    fun `preview labels only unusual permissions`() {
        assertEquals(null, FileMode.label(octal("644"), executable = false))
        assertEquals(null, FileMode.label(null, executable = false))
        assertEquals(FileMode.Label.Executable, FileMode.label(octal("755"), executable = true))
        assertEquals(FileMode.Label.Executable, FileMode.label(null, executable = true)) // 2.0 text
        assertEquals(FileMode.Label.Bits("rw-------"), FileMode.label(octal("600"), executable = false))
        assertEquals(FileMode.Label.Bits("rwxr-xr-x").bits, FileMode.describe(FileMode.safe(octal("777"))))
    }

    @Test
    fun `modes round trip through the zip`() {
        val files =
            listOf(
                FileEntry("key.pem", "secret".toByteArray(), executable = false, mode = octal("600")),
                FileEntry("run.sh", "#!/bin/sh".toByteArray(), executable = true, mode = octal("755")),
                FileEntry("windows.txt", "x".toByteArray()),
            )
        val decoded = ZipCodec.decode(ZipCodec.encode(files))
        assertEquals(listOf(octal("600"), octal("755"), null), decoded.map { it.mode })
        assertEquals(listOf(false, true, false), decoded.map { it.executable })
        assertArrayEquals("secret".toByteArray(), decoded[0].bytes)
    }

    @Test
    fun `text written by 2_0 still reads, with the executable flag and no mode`() {
        // 2.0 wrote a one-byte field, and only for executable files.
        val legacy = ZipCodec.encode(listOf(FileEntry("run.sh", "#!/bin/sh".toByteArray(), executable = true)))
        val decoded = ZipCodec.decode(legacy).single()
        assertTrue(decoded.executable)
        assertEquals(null, decoded.mode)
    }

    @Test
    fun `2_0 readers still see the executable flag in 2_1 text`() {
        // The 2.0 reader: first matching field, data byte 0 == 1.
        fun legacyHasExecFlag(extra: ByteArray?): Boolean {
            if (extra == null) return false
            var i = 0
            while (i + 4 <= extra.size) {
                val id = (extra[i].toInt() and 0xFF) or ((extra[i + 1].toInt() and 0xFF) shl 8)
                val size = (extra[i + 2].toInt() and 0xFF) or ((extra[i + 3].toInt() and 0xFF) shl 8)
                if (id == 0x4350 && size >= 1 && i + 4 < extra.size) return extra[i + 4].toInt() == 1
                i += 4 + size
            }
            return false
        }
        val zip = ZipCodec.encode(
            listOf(FileEntry("run.sh", ByteArray(1), true, octal("750")), FileEntry("a.txt", ByteArray(1), false, octal("600"))),
        )
        val extras = mutableListOf<ByteArray?>()
        java.util.zip.ZipInputStream(zip.inputStream()).use { input ->
            while (true) extras += (input.nextEntry ?: break).extra
        }
        assertTrue(legacyHasExecFlag(extras[0]))
        assertFalse(legacyHasExecFlag(extras[1]))
    }
}
