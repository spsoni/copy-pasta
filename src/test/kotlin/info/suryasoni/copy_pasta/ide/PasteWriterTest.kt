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
