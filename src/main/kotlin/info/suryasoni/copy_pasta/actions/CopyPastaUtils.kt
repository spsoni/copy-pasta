package info.suryasoni.copy_pasta.actions

import com.intellij.openapi.components.service
import info.suryasoni.copy_pasta.settings.CopyPastaSettingsState
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.text.DecimalFormat
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import kotlin.math.log
import kotlin.math.pow

object CopyPastaUtils {

    private val excludePatterns: List<String>
        get() {
            val settings = service<CopyPastaSettingsState>().state
            return settings.excludePatterns.split(",").map { it.trim() }
        }

    fun isTarGzFile(bytes: ByteArray): Boolean {
        return bytes.size > 2 && bytes[0] == 0x1F.toByte() && bytes[1] == 0x8B.toByte()
    }

    fun untarFiles(tais: TarArchiveInputStream, destinationDir: File) {
        var entry: TarArchiveEntry? = tais.nextEntry as? TarArchiveEntry
        while (entry != null) {
            val file = File(destinationDir, entry.name)
            if (shouldExclude(file)) {
                entry = tais.nextEntry as? TarArchiveEntry
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
            entry = tais.nextEntry as? TarArchiveEntry
        }
    }

    private fun shouldExclude(file: File): Boolean {
        val path = file.path.replace(File.separatorChar, '/')
        return excludePatterns.any { pattern ->
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
                taos.write(bytes)
                taos.closeArchiveEntry()
            }
        }
    }

    private fun File.mode(): Int {
        var mode = 0
        if (canRead()) mode = mode or 0b100_000_000
        if (canWrite()) mode = mode or 0b010_000_000
        if (canExecute()) mode = mode or 0b001_000_000
        return mode
    }

    fun humanReadableByteCount(bytes: Long): String {
        val unit = 1024
        if (bytes < unit) return "$bytes B"

        val exp = (log(bytes.toDouble(), unit.toDouble())).toInt()
        val pre = "KMGTPE"[exp - 1]

        return DecimalFormat("0.#").format(bytes / unit.toDouble().pow(exp)) + " " + pre + "B"
    }

    fun hashKey(key: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(key.toByteArray())
        return hash.take(16).toByteArray().toString(Charsets.UTF_8)
    }

    fun encrypt(data: ByteArray, key: String): ByteArray {
        val secretKey = SecretKeySpec(key.toByteArray(), "AES")
        val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey)
        return cipher.doFinal(data)
    }

    fun decrypt(data: ByteArray, key: String): ByteArray {
        val secretKey = SecretKeySpec(key.toByteArray(), "AES")
        val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, secretKey)
        return cipher.doFinal(data)
    }
}
