package info.suryasoni.copy_pasta.actions

import com.intellij.openapi.components.service
import info.suryasoni.copy_pasta.settings.CopyPastaSettingsState
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import java.io.*
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

object CopyPastaUtils {

    private val EXCLUDE_PATTERNS: List<String>
        get() {
            val settings = service<CopyPastaSettingsState>().state
            return settings.excludePatterns.split(",").map { it.trim() }
        }

    fun isTarGzFile(bytes: ByteArray): Boolean {
        return bytes.size > 2 && bytes[0] == 0x1F.toByte() && bytes[1] == 0x8B.toByte()
    }

    fun untarFiles(tais: TarArchiveInputStream, destinationDir: File) {
        var entry: TarArchiveEntry? = tais.nextEntry as TarArchiveEntry?
        while (entry != null) {
            val file = File(destinationDir, entry.name)
            if (shouldExclude(file)) {
                entry = tais.nextEntry as TarArchiveEntry?
                continue
            }

            if (entry.isDirectory) {
                if (!file.isDirectory && !file.mkdirs()) {
                    throw IOException("Failed to create directory ${file.absolutePath}")
                }
            } else {
                file.parentFile?.let { parent ->
                    if (!parent.isDirectory && !parent.mkdirs()) {
                        throw IOException("Failed to create directory ${parent.absolutePath}")
                    }
                }

                if (file.exists() && !file.isDirectory) {
                    file.delete()
                }

                FileOutputStream(file).use { fos ->
                    val buffer = ByteArray(1024)
                    var length: Int
                    while (tais.read(buffer).also { length = it } > 0) {
                        fos.write(buffer, 0, length)
                    }
                }

                // Restore file permissions
                file.setExecutable((entry.mode and 0b001_000_000) != 0)
                file.setReadable((entry.mode and 0b100_000_000) != 0)
                file.setWritable((entry.mode and 0b010_000_000) != 0)
            }
            entry = tais.nextEntry as TarArchiveEntry?
        }
    }

    fun shouldExclude(file: File): Boolean {
        val path = file.path.replace(File.separatorChar, '/')
        return EXCLUDE_PATTERNS.any { pattern ->
            path.matches(Regex(pattern.replace(".", "\\.").replace("*", ".*").replace("?", ".")))
        }
    }

    fun tarFiles(rootFolder: File, sourceFile: File, taos: TarArchiveOutputStream) {
        if (shouldExclude(sourceFile)) {
            return
        }

        if (sourceFile.isDirectory) {
            sourceFile.listFiles()?.forEach { file ->
                tarFiles(rootFolder, file, taos)
            }
        } else {
            FileInputStream(sourceFile).use { fis ->
                val tarEntryName = rootFolder.toURI().relativize(sourceFile.toURI()).path
                val entry = TarArchiveEntry(sourceFile, tarEntryName)
                entry.mode = sourceFile.mode() // Preserve file permissions
                taos.putArchiveEntry(entry)
                val bytes = fis.readBytes()
                taos.write(bytes, 0, bytes.size)
                taos.closeArchiveEntry()
            }
        }
    }

    fun File.mode(): Int {
        var mode = 0
        if (canRead()) mode = mode or 0b100_000_000
        if (canWrite()) mode = mode or 0b010_000_000
        if (canExecute()) mode = mode or 0b001_000_000
        return mode
    }

    fun encrypt(data: ByteArray, key: ByteArray): ByteArray {
        val secretKey = SecretKeySpec(key, "AES")
        val cipher = Cipher.getInstance("AES")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey)
        return cipher.doFinal(data)
    }

    fun decrypt(data: ByteArray, key: ByteArray): ByteArray {
        val secretKey = SecretKeySpec(key, "AES")
        val cipher = Cipher.getInstance("AES")
        cipher.init(Cipher.DECRYPT_MODE, secretKey)
        return cipher.doFinal(data)
    }

    fun hashKey(key: String): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(key.toByteArray()) // Use the full 32 bytes for AES-256
    }

    fun humanReadableByteCount(bytes: Long, si: Boolean = true): String {
        val unit = if (si) 1000 else 1024
        if (bytes < unit) return "$bytes B"
        val exp = (Math.log(bytes.toDouble()) / Math.log(unit.toDouble())).toInt()
        val pre = (if (si) "kMGTPE" else "KMGTPE")[exp - 1] + if (si) "" else "i"
        return String.format("%.1f %sB", bytes / Math.pow(unit.toDouble(), exp.toDouble()), pre)
    }
}
