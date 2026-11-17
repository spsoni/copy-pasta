package info.suryasoni.copy_pasta.format

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * One file in a Copy-Pasta payload. [path] is relative and uses `/` separators. [mode] holds the sender's nine Unix
 * permission bits (see [FileMode]), or null when unknown: copied on Windows, or written by Copy-Pasta 2.0, which
 * only recorded [executable].
 */
class FileEntry(val path: String, val bytes: ByteArray, val executable: Boolean = false, val mode: Int? = null)

class InvalidArchiveException(message: String) : Exception(message)

/**
 * Payload archive: a standard ZIP built with the JDK only (no third-party dependency), so it can also be read with
 * `base64 -d | unzip`. Permissions travel in a small private extra field, because the JDK can't write Unix file
 * modes into the ZIP's external attributes. Decoding rejects unsafe paths and enforces size limits (zip-bomb
 * protection).
 *
 * The extra field (id `0x4350`, "CP") is part of the format-2 compatibility contract:
 * - data byte 0: 1 if executable, else 0. Copy-Pasta 2.0 wrote only this byte, and only for executable files, and
 *   reads only this byte.
 * - data bytes 1–2 (2.1 and later): the nine permission bits, little-endian. Readers that know only byte 0 ignore
 *   them, so 2.0 still restores the executable bit from 2.1 text.
 */
object ZipCodec {
    private const val PERMISSIONS_FIELD_ID = 0x4350 // "CP"
    private val ZIP_MAGIC = byteArrayOf(0x50, 0x4B, 0x03, 0x04)

    class Limits(val maxEntries: Int = 10_000, val maxTotalBytes: Long = 200L * 1024 * 1024)

    fun encode(files: List<FileEntry>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.setLevel(Deflater.BEST_COMPRESSION)
            for (file in files) {
                val entry = ZipEntry(file.path)
                permissionsField(file)?.let { entry.extra = it }
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
                    val permissions = readPermissions(entry.extra)
                    files += FileEntry(path, bytes, permissions.executable, permissions.mode)
                }
            }
        } catch (e: IOException) {
            // ZipException, and EOFException for data cut off mid-entry.
            throw InvalidArchiveException("damaged archive: ${e.message}")
        } catch (e: IllegalArgumentException) {
            // ZipInputStream throws this for an entry name that isn't valid UTF-8.
            throw InvalidArchiveException("damaged archive: ${e.message}")
        }
        return files
    }

    /** Normalises an entry name and rejects anything that could escape the target folder. */
    fun safePath(name: String): String {
        // NUL and control characters have no business in a file name; some file systems and tools truncate at them.
        if (name.any { it < ' ' }) throw InvalidArchiveException("control character in a file name")
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

    private class Permissions(val executable: Boolean, val mode: Int?)

    /** The extra field for [file]: 3 data bytes when the mode is known, the 2.0 one-byte form otherwise. */
    private fun permissionsField(file: FileEntry): ByteArray? {
        val exec: Byte = if (file.executable) 1 else 0
        val header = byteArrayOf(0x50, 0x43) // id 0x4350, little-endian
        val mode = file.mode?.and(FileMode.PERMISSION_BITS)
        return when {
            mode != null -> header + byteArrayOf(0x03, 0x00, exec, (mode and 0xFF).toByte(), (mode shr 8).toByte())
            file.executable -> header + byteArrayOf(0x01, 0x00, exec)
            else -> null
        }
    }

    private fun readPermissions(extra: ByteArray?): Permissions {
        if (extra == null) return Permissions(false, null)
        var i = 0
        while (i + 4 <= extra.size) {
            val id = (extra[i].toInt() and 0xFF) or ((extra[i + 1].toInt() and 0xFF) shl 8)
            val size = (extra[i + 2].toInt() and 0xFF) or ((extra[i + 3].toInt() and 0xFF) shl 8)
            val data = i + 4
            if (id == PERMISSIONS_FIELD_ID && size >= 1 && data + size <= extra.size) {
                val executable = extra[data].toInt() == 1
                val mode =
                    if (size >= 3) {
                        ((extra[data + 1].toInt() and 0xFF) or ((extra[data + 2].toInt() and 0xFF) shl 8)) and FileMode.PERMISSION_BITS
                    } else {
                        null
                    }
                return Permissions(executable, mode)
            }
            i = data + size
        }
        return Permissions(false, null)
    }
}
