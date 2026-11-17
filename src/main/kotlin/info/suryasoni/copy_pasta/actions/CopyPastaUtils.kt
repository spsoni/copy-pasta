package info.suryasoni.copy_pasta.actions

import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.SecureRandom
import java.text.DecimalFormat
import java.util.Base64
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import kotlin.math.log
import kotlin.math.pow

/**
 * Pure (IDE-independent) logic for building, encrypting and extracting Copy-Pasta payloads.
 *
 * Payload format (before Base64):
 *  - plain:     a gzip-compressed tar archive
 *  - encrypted: "CPv2" | salt (16) | iv (12) | AES-256-GCM ciphertext+tag,
 *               key derived from the passphrase with PBKDF2-HMAC-SHA256.
 */
object CopyPastaUtils {

    private val ENCRYPTED_MAGIC = "CPv2".toByteArray(Charsets.US_ASCII)
    private const val SALT_BYTES = 16
    private const val IV_BYTES = 12
    private const val GCM_TAG_BITS = 128
    private const val PBKDF2_ITERATIONS = 100_000
    private const val KEY_BITS = 256

    private val MODE_EXECUTABLE = "100755".toInt(8)
    private val MODE_REGULAR = "100644".toInt(8)
    private val OWNER_EXECUTE_BIT = "100".toInt(8)

    private val random = SecureRandom()

    // ---------------------------------------------------------------- exclusion

    /** Parses comma- or newline-separated glob patterns (`*` and `?` wildcards). */
    fun parseExcludePatterns(raw: String): List<Regex> =
        raw.split(',', '\n').map { it.trim() }.filter { it.isNotEmpty() }.map(::globToRegex)

    private fun globToRegex(glob: String): Regex {
        val pattern = StringBuilder()
        for (c in glob) {
            when {
                c == '*' -> pattern.append(".*")
                c == '?' -> pattern.append('.')
                c.isLetterOrDigit() -> pattern.append(c)
                else -> pattern.append(Regex.escape(c.toString()))
            }
        }
        return Regex(pattern.toString())
    }

    /**
     * True when [relativePath] (using `/` separators) matches a pattern, either as a whole or
     * starting at any path segment, so the pattern `.git/` + `*` also excludes `sub/.git/config`.
     * Directories are matched with a trailing slash, so `build/` + `*` excludes the `build` directory itself.
     * (Patterns are spelled out like this because a slash-star would open a nested comment in Kotlin.)
     */
    fun isExcluded(relativePath: String, isDirectory: Boolean, patterns: List<Regex>): Boolean {
        if (patterns.isEmpty()) return false
        val path = if (isDirectory) "$relativePath/" else relativePath
        var start = 0
        while (true) {
            val candidate = path.substring(start)
            if (patterns.any { it.matches(candidate) }) return true
            val next = path.indexOf('/', start)
            if (next < 0 || next == path.length - 1) return false
            start = next + 1
        }
    }

    // ---------------------------------------------------------------- archive

    /** Builds a tar.gz of [sources]; each source is stored under its own name at the archive root. */
    fun createTarGz(sources: List<File>, patterns: List<Regex>): ByteArray {
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { gzos ->
            TarArchiveOutputStream(gzos).use { taos ->
                taos.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX)
                sources.forEach { addToTar(it, it.name, taos, patterns) }
            }
        }
        return out.toByteArray()
    }

    private fun addToTar(source: File, entryName: String, taos: TarArchiveOutputStream, patterns: List<Regex>) {
        if (Files.isSymbolicLink(source.toPath())) return // avoid cycles and escaping the selection
        if (isExcluded(entryName, source.isDirectory, patterns)) return

        if (source.isDirectory) {
            source.listFiles()?.sortedBy { it.name }?.forEach { child ->
                addToTar(child, "$entryName/${child.name}", taos, patterns)
            }
        } else if (source.isFile) {
            val entry = TarArchiveEntry(source, entryName)
            entry.mode = if (source.canExecute()) MODE_EXECUTABLE else MODE_REGULAR
            taos.putArchiveEntry(entry)
            Files.copy(source.toPath(), taos)
            taos.closeArchiveEntry()
        }
    }

    /**
     * Extracts a tar.gz into [destinationDir], overwriting existing files.
     * Entries that would land outside [destinationDir] (path traversal) are rejected.
     * Returns the number of files written.
     */
    fun extractTarGz(tarGz: ByteArray, destinationDir: File, patterns: List<Regex>): Int {
        val root: Path = destinationDir.toPath().toAbsolutePath().normalize()
        var written = 0
        TarArchiveInputStream(GZIPInputStream(ByteArrayInputStream(tarGz))).use { tais ->
            while (true) {
                val entry = tais.nextEntry ?: break
                val name = entry.name.trimEnd('/')
                if (name.isEmpty()) continue

                val target = root.resolve(name).normalize()
                if (!target.startsWith(root) || target == root) {
                    throw IOException("Refusing to extract entry outside the target directory: ${entry.name}")
                }
                if (isExcluded(name, entry.isDirectory, patterns)) continue

                when {
                    entry.isDirectory -> Files.createDirectories(target)
                    entry.isFile -> {
                        Files.createDirectories(target.parent)
                        Files.copy(tais, target, StandardCopyOption.REPLACE_EXISTING)
                        if (entry.mode and OWNER_EXECUTE_BIT != 0) target.toFile().setExecutable(true)
                        written++
                    }
                    // Symlinks, hard links, devices etc. are ignored on purpose.
                }
            }
        }
        return written
    }

    fun isGzip(bytes: ByteArray): Boolean = bytes.size > 2 && bytes[0] == 0x1F.toByte() && bytes[1] == 0x8B.toByte()

    // ---------------------------------------------------------------- crypto

    fun isEncrypted(bytes: ByteArray): Boolean =
        bytes.size > ENCRYPTED_MAGIC.size && bytes.copyOfRange(0, ENCRYPTED_MAGIC.size).contentEquals(ENCRYPTED_MAGIC)

    fun encrypt(data: ByteArray, passphrase: String): ByteArray {
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        val iv = ByteArray(IV_BYTES).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, deriveKey(passphrase, salt), GCMParameterSpec(GCM_TAG_BITS, iv))
        return ENCRYPTED_MAGIC + salt + iv + cipher.doFinal(data)
    }

    /** Throws [javax.crypto.AEADBadTagException] when the passphrase is wrong or the data was tampered with. */
    fun decrypt(payload: ByteArray, passphrase: String): ByteArray {
        require(isEncrypted(payload)) { "Not an encrypted Copy-Pasta payload" }
        val saltStart = ENCRYPTED_MAGIC.size
        val ivStart = saltStart + SALT_BYTES
        val dataStart = ivStart + IV_BYTES
        require(payload.size > dataStart) { "Encrypted payload is truncated" }

        val salt = payload.copyOfRange(saltStart, ivStart)
        val iv = payload.copyOfRange(ivStart, dataStart)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, deriveKey(passphrase, salt), GCMParameterSpec(GCM_TAG_BITS, iv))
        return cipher.doFinal(payload, dataStart, payload.size - dataStart)
    }

    private fun deriveKey(passphrase: String, salt: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec(passphrase.toCharArray(), salt, PBKDF2_ITERATIONS, KEY_BITS)
        try {
            val key = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
            return SecretKeySpec(key, "AES")
        } finally {
            spec.clearPassword()
        }
    }

    // ---------------------------------------------------------------- encoding

    fun toBase64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

    /** Decodes Base64, ignoring whitespace/line breaks added by chat clients. Throws IllegalArgumentException. */
    fun fromBase64(text: String): ByteArray = Base64.getDecoder().decode(text.filterNot { it.isWhitespace() })

    fun humanReadableByteCount(bytes: Long): String {
        val unit = 1024
        if (bytes < unit) return "$bytes B"
        val exp = log(bytes.toDouble(), unit.toDouble()).toInt().coerceAtMost(6)
        return DecimalFormat("0.#").format(bytes / unit.toDouble().pow(exp)) + " " + "KMGTPE"[exp - 1] + "B"
    }
}
