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
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileVisitor
import info.suryasoni.copy_pasta.format.FileEntry
import info.suryasoni.copy_pasta.format.FileMode
import java.nio.file.Files
import java.util.concurrent.Callable

class CopyTooLargeException : Exception("selection too large")

/**
 * Collects the files to copy. It uses what the IDE already knows instead of a hand-maintained pattern list:
 * VCS-ignored files (.gitignore etc.), IDE-excluded folders and the IDE's global ignore list are skipped.
 * Symlinks are not followed. Explicitly selected items are always included, even if they are ignored.
 * Unsaved editor changes are copied as they appear in the editor.
 *
 * Archive paths start at each selected item's name. If two selected items share a name (`a/src` and `b/src`), paths
 * are taken relative to their common parent instead, so nothing merges.
 */
class CopyCollector(private val project: Project) {
    class Result(val files: List<FileEntry>, val skippedIgnored: Int, val skippedLarge: List<String>, val skippedDuplicates: Int) {
        val totalBytes: Long get() = files.sumOf { it.bytes.size.toLong() }
    }

    private class Walk(val found: Map<String, VirtualFile>, val skippedIgnored: Int, val skippedDuplicates: Int)

    /** Call from a background thread: it uses cancellable read actions that restart if a write action interrupts. */
    fun collect(roots: List<VirtualFile>, indicator: ProgressIndicator? = null): Result {
        val walk = ReadAction.nonBlocking(Callable { walk(roots, indicator) }).executeSynchronously()

        val files = ArrayList<FileEntry>(walk.found.size)
        val skippedLarge = ArrayList<String>()
        var total = 0L
        for ((path, file) in walk.found) {
            indicator?.checkCanceled()
            if (file.length > MAX_FILE_BYTES) {
                skippedLarge += path
                continue
            }
            val bytes = contentOf(file)
            total += bytes.size
            if (total > MAX_TOTAL_BYTES) throw CopyTooLargeException()
            val mode = PosixMode.of(file)
            files += FileEntry(path, bytes, executable = mode?.let(FileMode::isOwnerExecutable) ?: isExecutable(file), mode = mode)
        }
        return Result(files, walk.skippedIgnored, skippedLarge, walk.skippedDuplicates)
    }

    /** No side effects: a non-blocking read action re-runs it if a write action interrupts. */
    private fun walk(selection: List<VirtualFile>, indicator: ProgressIndicator?): Walk {
        // Items inside another selected folder are already covered by it.
        val valid = selection.filter { it.isValid }.distinct()
        val roots = valid.filter { root -> valid.none { other -> other != root && VfsUtilCore.isAncestor(other, root, true) } }
        val base = if (roots.map { it.name }.toSet().size < roots.size) VfsUtil.getCommonAncestor(roots) else null

        var skippedIgnored = 0
        var skippedDuplicates = 0
        val found = LinkedHashMap<String, VirtualFile>()
        val fileIndex = ProjectFileIndex.getInstance(project)
        val changes = ChangeListManager.getInstance(project)
        val registry = FileTypeRegistry.getInstance()

        for (root in roots) {
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
                        if (!file.isDirectory && found.putIfAbsent(archivePath(base, root, file), file) != null) {
                            skippedDuplicates++
                        }
                        return true
                    }
                },
            )
        }
        return Walk(found, skippedIgnored, skippedDuplicates)
    }

    private fun archivePath(base: VirtualFile?, root: VirtualFile, file: VirtualFile): String {
        base?.let { VfsUtilCore.getRelativePath(file, it, '/') }?.let { return it }
        return if (file == root) root.name else root.name + "/" + VfsUtilCore.getRelativePath(file, root, '/')
    }

    /** Disk content, or the editor's text when the document has unsaved changes. */
    private fun contentOf(file: VirtualFile): ByteArray {
        val unsavedText =
            ReadAction
                .nonBlocking(
                    Callable {
                        val documents = FileDocumentManager.getInstance()
                        documents.getCachedDocument(file)?.takeIf(documents::isDocumentUnsaved)?.text
                    },
                ).executeSynchronously() ?: return file.contentsToByteArray()
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
