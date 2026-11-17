package info.suryasoni.copy_pasta.ide

import com.intellij.history.LocalHistory
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import info.suryasoni.copy_pasta.CopyPastaBundle
import info.suryasoni.copy_pasta.format.FileEntry
import java.io.IOException

enum class PasteStatus { NEW, OVERWRITE, IDENTICAL, BLOCKED }

class PlannedFile(val entry: FileEntry, val status: PasteStatus)

/**
 * Plans and performs a paste through the IDE's virtual file system. Writing in a single write command after a Local
 * History label means the IDE sees every change and the whole paste can be reverted from Local History.
 */
object PasteWriter {
    /** Works out what each entry would do in [target]. Call inside a read action. */
    fun plan(target: VirtualFile, files: List<FileEntry>): List<PlannedFile> = files.map { PlannedFile(it, statusOf(target, it)) }

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
                existing.contentsToByteArray().contentEquals(
                    entry.bytes,
                ) -> PasteStatus.IDENTICAL
            else -> PasteStatus.OVERWRITE
        }
    }

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
                    if (entry.executable && !SystemInfo.isWindows) {
                        file.fileSystem.getNioPath(file)?.toFile()?.setExecutable(true)
                    }
                    file
                }
            }
    }
}
