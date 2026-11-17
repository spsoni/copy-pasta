package info.suryasoni.copy_pasta.ide

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileTypes.FileTypeRegistry
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.vcs.changes.ChangeListManager
import com.intellij.openapi.vfs.VFileProperty
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileVisitor
import info.suryasoni.copy_pasta.format.FileEntry
import java.nio.file.Files

class CopyTooLargeException(val totalBytes: Long) : Exception("selection too large")

/**
 * Collects the files to copy. It uses what the IDE already knows instead of a hand-maintained pattern list:
 * VCS-ignored files (.gitignore etc.), IDE-excluded folders and the IDE's global ignore list are skipped.
 * Symlinks are not followed. Explicitly selected items are always included, even if they are ignored.
 * Unsaved editor changes are copied as they appear in the editor.
 */
class CopyCollector(private val project: Project) {
    class Result(val files: List<FileEntry>, val skippedIgnored: Int, val skippedLarge: List<String>) {
        val totalBytes: Long get() = files.sumOf { it.bytes.size.toLong() }
    }

    fun collect(roots: List<VirtualFile>, indicator: ProgressIndicator? = null): Result {
        var skippedIgnored = 0
        val found = LinkedHashMap<String, VirtualFile>() // archive path -> file (first one wins on clashes)

        ReadAction.run<RuntimeException> {
            val fileIndex = ProjectFileIndex.getInstance(project)
            val changes = ChangeListManager.getInstance(project)
            val registry = FileTypeRegistry.getInstance()

            for (root in roots.filter { it.isValid }) {
                // Exclusion and VCS-ignore are inherited by everything below. If the user explicitly selected an
                // excluded/ignored folder, they want its contents, so only the name-based global ignore list applies.
                val rootIgnored = fileIndex.isExcluded(root) || changes.isIgnoredFile(root)
                VfsUtilCore.visitChildrenRecursively(
                    root,
                    object : VirtualFileVisitor<Unit>(NO_FOLLOW_SYMLINKS) {
                        override fun visitFile(file: VirtualFile): Boolean {
                            indicator?.checkCanceled()
                            if (file != root) {
                                val ignored =
                                    file.`is`(VFileProperty.SYMLINK) ||
                                        registry.isFileIgnored(file) ||
                                        (!rootIgnored && (fileIndex.isExcluded(file) || changes.isIgnoredFile(file)))
                                if (ignored) {
                                    skippedIgnored++
                                    return false
                                }
                            }
                            if (!file.isDirectory) found.putIfAbsent(archivePath(root, file), file)
                            return true
                        }
                    },
                )
            }
        }

        val files = ArrayList<FileEntry>(found.size)
        val skippedLarge = ArrayList<String>()
        var total = 0L
        for ((path, file) in found) {
            indicator?.checkCanceled()
            if (file.length > MAX_FILE_BYTES) {
                skippedLarge += path
                continue
            }
            val bytes = contentOf(file)
            total += bytes.size
            if (total > MAX_TOTAL_BYTES) throw CopyTooLargeException(total)
            files += FileEntry(path, bytes, isExecutable(file))
        }
        return Result(files, skippedIgnored, skippedLarge)
    }

    private fun archivePath(root: VirtualFile, file: VirtualFile): String =
        if (file == root) root.name else root.name + "/" + VfsUtilCore.getRelativePath(file, root, '/')

    /** Disk content, or the editor's text when the document has unsaved changes. */
    private fun contentOf(file: VirtualFile): ByteArray {
        val unsavedText =
            ReadAction.compute<String?, RuntimeException> {
                val documents = FileDocumentManager.getInstance()
                documents.getCachedDocument(file)?.takeIf(documents::isDocumentUnsaved)?.text
            } ?: return file.contentsToByteArray()
        val separator = file.detectedLineSeparator ?: "\n"
        return (file.bom ?: ByteArray(0)) + unsavedText.replace("\n", separator).toByteArray(file.charset)
    }

    // getNioPath is null for file systems that aren't on disk (e.g. in-memory), where there is no executable bit.
    private fun isExecutable(file: VirtualFile): Boolean =
        !SystemInfo.isWindows && file.fileSystem.getNioPath(file)?.let(Files::isExecutable) == true

    companion object {
        const val MAX_FILE_BYTES = 10L * 1024 * 1024
        const val MAX_TOTAL_BYTES = 50L * 1024 * 1024
    }
}
