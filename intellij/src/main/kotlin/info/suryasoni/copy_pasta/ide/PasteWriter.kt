package info.suryasoni.copy_pasta.ide

import com.intellij.history.LocalHistory
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.vfs.VFileProperty
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import info.suryasoni.copy_pasta.CopyPastaBundle
import info.suryasoni.copy_pasta.format.FileEntry
import info.suryasoni.copy_pasta.format.FileMode
import info.suryasoni.copy_pasta.format.PathRules
import java.io.IOException
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes

enum class PasteStatus { NEW, OVERWRITE, IDENTICAL, BLOCKED }

class PlannedFile(val entry: FileEntry, val status: PasteStatus)

/**
 * Plans and performs a paste through the IDE's virtual file system. Writing in a single write command after a Local
 * History label means the IDE sees every change and the whole paste can be reverted from Local History.
 */
object PasteWriter {
    /** True when a segment of [path] must never be written: VCS metadata anywhere, unsafe names on Windows. */
    private fun forbiddenName(path: String, windows: Boolean): Boolean =
        path.split('/').any { PathRules.isVcsMetadata(it) || (windows && PathRules.isUnsafeOnWindows(it)) }

    /**
     * True when an existing component of [path] below [target], or the file itself, is a symbolic link, leads out of
     * the target (a Windows junction or mount point), or is a special file (FIFO, socket, device). Writing there would
     * follow the link out of the target folder or overwrite the file it points to. Checked on disk without following
     * links, so broken links count too; the VFS flag covers file systems without a disk path.
     *
     * Windows reports every reparse point as "other", including OneDrive placeholders, which are ordinary folders
     * for our purposes. So there, a non-symlink reparse point is blocked only when its real path leaves the target.
     */
    private fun linkOnPath(target: VirtualFile, path: String, windows: Boolean = SystemInfo.isWindows): Boolean {
        val segments = path.split('/')
        val base = target.fileSystem.getNioPath(target)
        if (base != null) {
            try {
                var current: Path = base
                for (segment in segments) {
                    current = current.resolve(segment)
                    val attributes =
                        try {
                            Files.readAttributes(current, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
                        } catch (_: IOException) {
                            return false // doesn't exist: nothing below it exists either
                        }
                    if (attributes.isSymbolicLink) return true
                    if (attributes.isOther && (!windows || !current.toRealPath().startsWith(base.toRealPath()))) return true
                }
                return false
            } catch (_: InvalidPathException) {
                return true // a name this platform can't represent (e.g. non-ASCII under a C locale): don't write it
            } catch (_: IOException) {
                return true // toRealPath failed on something that exists: be safe
            }
        }
        var dir = target
        for (segment in segments) {
            val child = dir.findChild(segment) ?: return false
            if (child.`is`(VFileProperty.SYMLINK)) return true
            dir = child
        }
        return false
    }

    /**
     * Works out what each entry would do in [target]. Call inside a read action. Entries are BLOCKED when they would
     * write into VCS metadata, through a symbolic link, under a name Windows can't hold safely (on Windows), duplicate
     * another entry (case-insensitively on case-insensitive file systems), sit where another entry needs a folder, or
     * clash with an existing file or folder.
     */
    fun plan(
        target: VirtualFile,
        files: List<FileEntry>,
        caseSensitive: Boolean = target.fileSystem.isCaseSensitive,
        windows: Boolean = SystemInfo.isWindows,
    ): List<PlannedFile> {
        fun key(path: String) = PathRules.sameFileKey(path, caseSensitive)
        val folders =
            files.flatMapTo(HashSet()) { file ->
                file.path.split('/').dropLast(1).runningReduce { parent, segment -> "$parent/$segment" }.map(::key)
            }
        val seen = HashSet<String>()
        return files.map { entry ->
            val status =
                when {
                    forbiddenName(entry.path, windows) -> PasteStatus.BLOCKED
                    !seen.add(key(entry.path)) -> PasteStatus.BLOCKED
                    key(entry.path) in folders -> PasteStatus.BLOCKED
                    linkOnPath(target, entry.path) -> PasteStatus.BLOCKED
                    else -> statusOf(target, entry)
                }
            PlannedFile(entry, status)
        }
    }

    private fun statusOf(target: VirtualFile, entry: FileEntry): PasteStatus {
        val segments = entry.path.split('/')
        var dir = target
        for (segment in segments.dropLast(1)) {
            val child = dir.findChild(segment) ?: return PasteStatus.NEW
            if (!child.isDirectory || child.`is`(VFileProperty.SYMLINK)) return PasteStatus.BLOCKED
            dir = child
        }
        val existing = dir.findChild(segments.last()) ?: return PasteStatus.NEW
        return when {
            existing.`is`(VFileProperty.SYMLINK) -> PasteStatus.BLOCKED
            existing.isDirectory -> PasteStatus.BLOCKED
            existing.length == entry.bytes.size.toLong() &&
                existing.contentsToByteArray().contentEquals(entry.bytes) &&
                !permissionsWouldChange(existing, entry) -> PasteStatus.IDENTICAL
            else -> PasteStatus.OVERWRITE
        }
    }

    /**
     * The permissions a paste gives a file that now has [current] (macOS and Linux only), or null to leave them:
     * - a known mode goes through [FileMode.applied], so it never widens group or other access beyond the
     *   recipient's umask or the existing file;
     * - text from Copy-Pasta 2.0 carries only the executable flag, which adds owner-execute, as 2.0 did;
     * - otherwise the file keeps what it has.
     */
    private fun targetMode(entry: FileEntry, current: Int): Int? = when {
        entry.mode != null -> FileMode.applied(entry.mode, current)
        entry.executable -> current or FileMode.OWNER_EXECUTE
        else -> null
    }

    private fun permissionsWouldChange(existing: VirtualFile, entry: FileEntry): Boolean {
        val current = PosixMode.of(existing) ?: return false
        return targetMode(entry, current)?.let { it != current } ?: false
    }

    /** Applies the sender's permissions after writing. The content is already written, so a failure is only logged. */
    private fun applyPermissions(file: VirtualFile, entry: FileEntry) {
        if (entry.mode == null && !entry.executable) return
        val current = PosixMode.of(file) ?: return
        val target = targetMode(entry, current) ?: return
        if (target == current) return
        try {
            PosixMode.set(file, target)
        } catch (e: UnsupportedOperationException) {
            LOG.debug("No POSIX permissions on ${file.presentableUrl}", e) // e.g. FAT on a USB drive
        } catch (e: IOException) {
            LOG.warn("Could not set permissions on ${file.presentableUrl}", e)
        }
    }

    private val LOG = logger<PasteWriter>()

    /** Writes [files] into [target]. Call on the EDT. Returns the written files. */
    fun write(project: Project, target: VirtualFile, files: List<FileEntry>): List<VirtualFile> {
        LocalHistory.getInstance().putSystemLabel(project, CopyPastaBundle.message("paste.localHistoryLabel"))
        val documents = FileDocumentManager.getInstance()
        return WriteCommandAction
            .writeCommandAction(project)
            .withName(CopyPastaBundle.message("paste.commandName"))
            .compute<List<VirtualFile>, IOException> {
                files.map { entry ->
                    // The preview only offers entries plan() allowed, but the tree may have changed since: check again
                    // right before writing, and never follow a link out of the target.
                    if (forbiddenName(entry.path, SystemInfo.isWindows) || linkOnPath(target, entry.path)) {
                        throw IOException("refusing to write ${entry.path}: it goes through a symbolic link or a protected name")
                    }
                    val parentPath = entry.path.substringBeforeLast('/', "")
                    val name = entry.path.substringAfterLast('/')
                    val dir =
                        if (parentPath.isEmpty()) {
                            target
                        } else {
                            VfsUtil.createDirectoryIfMissing(target, parentPath)
                                ?: throw IOException("cannot create folder $parentPath")
                        }
                    val existing = dir.findChild(name)
                    // Save unsaved edits first so Local History holds them, and the reload doesn't raise a conflict.
                    existing?.let { documents.getCachedDocument(it) }?.takeIf(documents::isDocumentUnsaved)?.let(documents::saveDocument)
                    val file = existing ?: dir.createChildData(this, name)
                    file.setBinaryContent(entry.bytes)
                    applyPermissions(file, entry)
                    file
                }
            }
    }
}
