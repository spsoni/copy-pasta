package info.suryasoni.copy_pasta.format

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

class ZipCodecTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val files =
        listOf(
            FileEntry("proj/src/Main.kt", "fun main() {}".toByteArray()),
            FileEntry("proj/gradlew", "#!/bin/sh\n".toByteArray(), executable = true),
            FileEntry("proj/empty.txt", ByteArray(0)),
        )

    @Test
    fun `round trip keeps paths, content and the executable bit`() {
        val decoded = ZipCodec.decode(ZipCodec.encode(files))
        assertEquals(files.map { it.path }, decoded.map { it.path })
        files.zip(decoded).forEach { (a, b) ->
            assertArrayEquals(a.bytes, b.bytes)
            assertEquals(a.executable, b.executable)
        }
    }

    @Test
    fun `payload is a standard zip readable by other tools`() {
        val file = tmp.newFile("payload.zip").apply { writeBytes(ZipCodec.encode(files)) }
        ZipFile(file).use { zip ->
            assertEquals(files.map { it.path }.toSet(), zip.entries().asSequence().map { it.name }.toSet())
            assertEquals("fun main() {}", zip.getInputStream(zip.getEntry("proj/src/Main.kt")).readBytes().decodeToString())
        }
    }

    @Test
    fun `unsafe paths are rejected`() {
        for (evil in listOf("../escape.txt", "a/../../b", "/etc/passwd", "C:/Windows/x", "a\\..\\..\\b")) {
            assertThrows(evil, InvalidArchiveException::class.java) { ZipCodec.decode(rawZip(evil to "x")) }
        }
        assertEquals("a/b.txt", ZipCodec.safePath("./a//b.txt"))
    }

    @Test
    fun `zip bombs are stopped by the size and entry limits`() {
        val big = rawZip("big.bin" to "0".repeat(2_000_000)) // compresses to a few KB
        assertThrows(InvalidArchiveException::class.java) { ZipCodec.decode(big, ZipCodec.Limits(maxTotalBytes = 1_000_000)) }

        val many = rawZip(*Array(20) { "f$it.txt" to "x" })
        assertThrows(InvalidArchiveException::class.java) { ZipCodec.decode(many, ZipCodec.Limits(maxEntries = 10)) }
    }

    @Test
    fun `non-zip and duplicate entries are rejected`() {
        assertThrows(InvalidArchiveException::class.java) { ZipCodec.decode("hello".toByteArray()) }
        assertThrows(InvalidArchiveException::class.java) { ZipCodec.decode(rawZip("a.txt" to "1", "./a.txt" to "2")) }
    }

    @Test
    fun `foreign zips without the private field decode as non-executable`() {
        val decoded = ZipCodec.decode(rawZip("x.sh" to "echo"))
        assertFalse(decoded.single().executable)
        assertTrue(decoded.single().bytes.isNotEmpty())
    }

    private fun rawZip(vararg entries: Pair<String, String>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            for ((name, content) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray())
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }
}
