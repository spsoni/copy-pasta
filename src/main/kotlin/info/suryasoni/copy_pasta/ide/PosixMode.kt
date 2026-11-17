package info.suryasoni.copy_pasta.ide

import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.vfs.VirtualFile
import java.io.IOException
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission

/** Reads and writes a file's nine Unix permission bits through java.nio. A no-op on Windows and non-POSIX file systems. */
internal object PosixMode {
    private val BITS: Map<PosixFilePermission, Int> =
        mapOf(
            PosixFilePermission.OWNER_READ to 0b100_000_000,
            PosixFilePermission.OWNER_WRITE to 0b010_000_000,
            PosixFilePermission.OWNER_EXECUTE to 0b001_000_000,
            PosixFilePermission.GROUP_READ to 0b000_100_000,
            PosixFilePermission.GROUP_WRITE to 0b000_010_000,
            PosixFilePermission.GROUP_EXECUTE to 0b000_001_000,
            PosixFilePermission.OTHERS_READ to 0b000_000_100,
            PosixFilePermission.OTHERS_WRITE to 0b000_000_010,
            PosixFilePermission.OTHERS_EXECUTE to 0b000_000_001,
        )

    /** The file's permission bits, or null on Windows, non-disk and non-POSIX file systems, or on an I/O error. */
    fun of(file: VirtualFile): Int? {
        if (SystemInfo.isWindows) return null
        val path = file.fileSystem.getNioPath(file) ?: return null
        return try {
            Files.getPosixFilePermissions(path).sumOf { BITS.getValue(it) }
        } catch (_: UnsupportedOperationException) {
            null
        } catch (_: IOException) {
            null
        }
    }

    /** Sets the file's permission bits. Throws [IOException] or [UnsupportedOperationException] on failure. */
    fun set(file: VirtualFile, mode: Int) {
        val path = file.fileSystem.getNioPath(file) ?: return
        Files.setPosixFilePermissions(path, BITS.filterValues { mode and it != 0 }.keys)
    }
}
