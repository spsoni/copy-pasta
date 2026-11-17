package info.suryasoni.copy_pasta.ide

import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import info.suryasoni.copy_pasta.format.FileEntry

class PasteWriterTest : BasePlatformTestCase() {
    private fun entry(path: String, text: String) = FileEntry(path, text.toByteArray())

    fun `test plan marks new, identical, overwrite and blocked entries`() {
        val temp = myFixture.tempDirFixture
        val target = temp.findOrCreateDir("target")
        temp.createFile("target/same.txt", "same")
        temp.createFile("target/changed.txt", "old")
        temp.createFile("target/file", "a file, not a folder")
        temp.findOrCreateDir("target/dir")

        val plan =
            PasteWriter.plan(
                target,
                listOf(
                    entry("new/a.txt", "a"),
                    entry("same.txt", "same"),
                    entry("changed.txt", "new"),
                    entry("file/inner.txt", "x"),
                    entry("dir", "x"),
                ),
            )
        assertEquals(
            listOf(PasteStatus.NEW, PasteStatus.IDENTICAL, PasteStatus.OVERWRITE, PasteStatus.BLOCKED, PasteStatus.BLOCKED),
            plan.map { it.status },
        )
    }

    fun `test entries that clash with each other or target VCS metadata are blocked`() {
        val target = myFixture.tempDirFixture.findOrCreateDir("clash")
        val files =
            listOf(
                entry("util", "a file"), // another entry needs "util" as a folder
                entry("util/a.kt", "x"),
                entry(".git/hooks/pre-commit", "evil"),
                entry("sub/.GIT/config", "evil"),
            )
        assertEquals(
            listOf(PasteStatus.BLOCKED, PasteStatus.NEW, PasteStatus.BLOCKED, PasteStatus.BLOCKED),
            PasteWriter.plan(target, files).map { it.status },
        )
    }

    fun `test case-only duplicates are blocked on case-insensitive file systems`() {
        val target = myFixture.tempDirFixture.findOrCreateDir("case")
        val files = listOf(entry("README.md", "a"), entry("readme.md", "b"), entry("Docs/x", "1"), entry("docs", "2"))

        assertEquals(
            listOf(PasteStatus.NEW, PasteStatus.BLOCKED, PasteStatus.NEW, PasteStatus.BLOCKED),
            PasteWriter.plan(target, files, caseSensitive = false).map { it.status },
        )
        assertEquals(
            listOf(PasteStatus.NEW, PasteStatus.NEW, PasteStatus.NEW, PasteStatus.NEW),
            PasteWriter.plan(target, files, caseSensitive = true).map { it.status },
        )
    }

    fun `test write creates folders and overwrites files through the VFS`() {
        val temp = myFixture.tempDirFixture
        val target = temp.findOrCreateDir("out")
        temp.createFile("out/changed.txt", "old")

        val written = PasteWriter.write(project, target, listOf(entry("deep/nested/a.txt", "a"), entry("changed.txt", "new")))

        assertEquals(2, written.size)
        assertEquals("a", VfsUtilCore.loadText(target.findFileByRelativePath("deep/nested/a.txt")!!))
        assertEquals("new", VfsUtilCore.loadText(target.findChild("changed.txt")!!))
    }
}
