package info.suryasoni.copy_pasta.format

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipException
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** One file in a Copy-Pasta payload. [path] is relative and uses `/` separators. */
class FileEntry(val path: String, val bytes: ByteArray, val executable: Boolean = false)

class InvalidArchiveException(message: String) : Exception(message)

/**
 * Payload archive: a standard ZIP built with the JDK only (no third-party dependency), so it can also be read with
 * `base64 -d | unzip`. The executable bit travels in a small private extra field, because the JDK can't write Unix
 * file modes. Decoding rejects unsafe paths and enforces size limits (zip-bomb protection).
 */
object ZipCodec {
    private const val EXEC_FIELD_ID = 0x4350 // "CP"
    private val EXEC_FIELD = byteArrayOf(0x50, 0x43, 0x01, 0x00, 0x01) // id (LE), size = 1 (LE), data
    private val ZIP_MAGIC = byteArrayOf(0x50, 0x4B, 0x03, 0x04)

    class Limits(val maxEntries: Int = 10_000, val maxTotalBytes: Long = 200L * 1024 * 1024)

    fun encode(files: List<FileEntry>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.setLevel(Deflater.BEST_COMPRESSION)
            for (file in files) {
                val entry = ZipEntry(file.path)
                if (file.executable) entry.extra = EXEC_FIELD
                zip.putNextEntry(entry)
                zip.write(file.bytes)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    fun decode(zip: ByteArray, limits: Limits = Limits()): List<FileEntry> {
        if (zip.size < ZIP_MAGIC.size || !zip.copyOfRange(0, ZIP_MAGIC.size).contentEquals(ZIP_MAGIC)) {
            throw InvalidArchiveException("not a ZIP archive")
        }
        val files = mutableListOf<FileEntry>()
        val seen = HashSet<String>()
        var total = 0L
        try {
            ZipInputStream(ByteArrayInputStream(zip)).use { input ->
                while (true) {
                    val entry = input.nextEntry ?: break
                    if (entry.isDirectory) continue
                    if (files.size >= limits.maxEntries) throw InvalidArchiveException("more than ${limits.maxEntries} files")
                    val path = safePath(entry.name)
                    if (!seen.add(path)) throw InvalidArchiveException("duplicate entry: $path")
                    val bytes = readLimited(input, limits.maxTotalBytes - total)
                    total += bytes.size
                    files += FileEntry(path, bytes, hasExecFlag(entry.extra))
                }
            }
        } catch (e: ZipException) {
            throw InvalidArchiveException("damaged archive: ${e.message}")
        }
        return files
    }

    /** Normalises an entry name and rejects anything that could escape the target folder. */
    fun safePath(name: String): String {
        val normalized = name.replace('\\', '/')
        if (normalized.startsWith("/") || Regex("^[A-Za-z]:").containsMatchIn(normalized)) {
            throw InvalidArchiveException("absolute path: $name")
        }
        val segments = normalized.split('/').filter { it.isNotEmpty() && it != "." }
        if (segments.isEmpty() || segments.any { it == ".." }) throw InvalidArchiveException("unsafe path: $name")
        return segments.joinToString("/")
    }

    private fun readLimited(input: InputStream, remaining: Long): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        var read = 0L
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            read += n
            if (read > remaining) throw InvalidArchiveException("archive expands beyond the size limit")
            out.write(buffer, 0, n)
        }
        return out.toByteArray()
    }

    private fun hasExecFlag(extra: ByteArray?): Boolean {
        if (extra == null) return false
        var i = 0
        while (i + 4 <= extra.size) {
            val id = (extra[i].toInt() and 0xFF) or ((extra[i + 1].toInt() and 0xFF) shl 8)
            val size = (extra[i + 2].toInt() and 0xFF) or ((extra[i + 3].toInt() and 0xFF) shl 8)
            if (id == EXEC_FIELD_ID && size >= 1 && i + 4 < extra.size) return extra[i + 4].toInt() == 1
            i += 4 + size
        }
        return false
    }
}
