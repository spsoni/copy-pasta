package info.suryasoni.copy_pasta.actions

import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.zip.GZIPOutputStream
import javax.crypto.AEADBadTagException

class CopyPastaUtilsTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val defaultPatterns = CopyPastaUtils.parseExcludePatterns("*.pyc, .git/*\nbuild/*")

    @Test
    fun `exclude patterns match at any path segment`() {
        assertTrue(CopyPastaUtils.isExcluded("proj/a/b.pyc", false, defaultPatterns))
        assertTrue(CopyPastaUtils.isExcluded("proj/.git/config", false, defaultPatterns))
        assertTrue(CopyPastaUtils.isExcluded("proj/build", true, defaultPatterns))
        assertFalse(CopyPastaUtils.isExcluded("proj/build.gradle", false, defaultPatterns))
        assertFalse(CopyPastaUtils.isExcluded("proj/xgit/config", false, defaultPatterns))
        assertFalse(CopyPastaUtils.isExcluded("proj/src/Main.kt", false, defaultPatterns))
    }

    @Test
    fun `archive round trip keeps structure, executable bit and honours excludes`() {
        val src = tmp.newFolder("proj")
        File(src, "src").mkdirs()
        File(src, "src/Main.kt").writeText("fun main() {}")
        File(src, "run.sh").apply {
            writeText("#!/bin/sh\n")
            setExecutable(true)
        }
        File(src, ".git").mkdirs()
        File(src, ".git/HEAD").writeText("ref")
        File(src, "junk.pyc").writeText("x")

        val tarGz = CopyPastaUtils.createTarGz(listOf(src), defaultPatterns)
        assertTrue(CopyPastaUtils.isGzip(tarGz))

        val dest = tmp.newFolder("dest")
        val count = CopyPastaUtils.extractTarGz(tarGz, dest, emptyList())

        assertEquals(2, count)
        assertEquals("fun main() {}", File(dest, "proj/src/Main.kt").readText())
        assertTrue(File(dest, "proj/run.sh").canExecute())
        assertFalse(File(dest, "proj/.git").exists())
        assertFalse(File(dest, "proj/junk.pyc").exists())
    }

    @Test
    fun `extraction rejects path traversal`() {
        val evil = ByteArrayOutputStream().also { out ->
            TarArchiveOutputStream(GZIPOutputStream(out)).use { taos ->
                val data = "pwned".toByteArray()
                taos.putArchiveEntry(TarArchiveEntry("../escape.txt").apply { size = data.size.toLong() })
                taos.write(data)
                taos.closeArchiveEntry()
            }
        }.toByteArray()

        val dest = tmp.newFolder("dest")
        assertThrows(IOException::class.java) { CopyPastaUtils.extractTarGz(evil, dest, emptyList()) }
        assertFalse(File(dest.parentFile, "escape.txt").exists())
    }

    @Test
    fun `encryption round trip and wrong key`() {
        val data = "hello world".toByteArray()
        val encrypted = CopyPastaUtils.encrypt(data, "secret")

        assertTrue(CopyPastaUtils.isEncrypted(encrypted))
        assertFalse(CopyPastaUtils.isGzip(encrypted))
        assertArrayEquals(data, CopyPastaUtils.decrypt(encrypted, "secret"))
        assertThrows(AEADBadTagException::class.java) { CopyPastaUtils.decrypt(encrypted, "other") }
    }

    @Test
    fun `plain gzip is not mistaken for encrypted data`() {
        val tarGz = CopyPastaUtils.createTarGz(emptyList(), emptyList())
        assertFalse(CopyPastaUtils.isEncrypted(tarGz))
        assertTrue(CopyPastaUtils.isGzip(tarGz))
    }

    @Test
    fun `base64 decoding tolerates line breaks`() {
        val bytes = ByteArray(200) { it.toByte() }
        val wrapped = CopyPastaUtils.toBase64(bytes).chunked(60).joinToString("\r\n")
        assertArrayEquals(bytes, CopyPastaUtils.fromBase64(wrapped))
    }

    @Test
    fun `human readable sizes`() {
        assertEquals("512 B", CopyPastaUtils.humanReadableByteCount(512))
        assertEquals("1.5 KB", CopyPastaUtils.humanReadableByteCount(1536))
        assertEquals("1 MB", CopyPastaUtils.humanReadableByteCount(1024L * 1024))
    }
}
