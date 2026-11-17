package info.suryasoni.copy_pasta.ide

import com.intellij.history.LocalHistory
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import info.suryasoni.copy_pasta.CopyPastaBundle
import info.suryasoni.copy_pasta.format.FileEntry
import info.suryasoni.copy_pasta.format.FileMode
import java.io.IOException

enum class PasteStatus { NEW, OVERWRITE, IDENTICAL, BLOCKED }

class PlannedFile(val entry: FileEntry, val status: PasteStatus)

/**
 * Plans and performs a paste through the IDE's virtual file system. Writing in a single write command after a Local
 * History label means the IDE sees every change and the whole paste can be reverted from Local History.
 */
object PasteWriter {
    /** Folders a pasted archive must never write into: VCS metadata can execute code (hooks, fsmonitor config). */
    private val FORBIDDEN_SEGMENTS = setOf(".git", ".hg", ".svn", ".bzr", "cvs")

    /**
     * Works out what each entry would do in [target]. Call inside a read action. Entries are BLOCKED when they would
     * write into VCS metadata, duplicate another entry (case-insensitively on case-insensitive file systems), sit
     * where another entry needs a folder, or clash with an existing file or folder.
     */
    fun plan(target: VirtualFile, files: List<FileEntry>, caseSensitive: Boolean = target.fileSystem.isCaseSensitive): List<PlannedFile> {
        fun key(path: String) = if (caseSensitive) path else path.lowercase()
        val folders =
            files.flatMapTo(HashSet()) { file ->
                file.path.split('/').dropLast(1).runningReduce { parent, segment -> "$parent/$segment" }.map(::key)
            }
        val seen = HashSet<String>()
        return files.map { entry ->
            val status =
                when {
                    entry.path.split('/').any { it.lowercase() in FORBIDDEN_SEGMENTS } -> PasteStatus.BLOCKED
                    !seen.add(key(entry.path)) -> PasteStatus.BLOCKED
                    key(entry.path) in folders -> PasteStatus.BLOCKED
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
            if (!child.isDirectory) return PasteStatus.BLOCKED // a file sits where a folder is needed
            dir = child
        }
        val existing = dir.findChild(segments.last()) ?: return PasteStatus.NEW
        return when {
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
