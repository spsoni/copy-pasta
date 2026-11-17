package info.suryasoni.copy_pasta.ide

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.testFramework.PsiTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class CopyCollectorTest : BasePlatformTestCase() {
    fun `test skips excluded and globally ignored files but honours explicit selections`() {
        val temp = myFixture.tempDirFixture
        val root = temp.findOrCreateDir("proj")
        temp.createFile("proj/src/Main.kt", "fun main() {}")
        temp.createFile("proj/.DS_Store", "junk")
        temp.createFile("proj/out/Main.class", "bin")
        val out = temp.getFile("proj/out")!!
        PsiTestUtil.addExcludedRoot(module, out)
        try {
            val result = CopyCollector(project).collect(listOf(root))
            assertEquals(listOf("proj/src/Main.kt"), result.files.map { it.path })
            assertEquals(2, result.skippedIgnored)

            val explicit = CopyCollector(project).collect(listOf(out))
            assertEquals(listOf("out/Main.class"), explicit.files.map { it.path })
        } finally {
            PsiTestUtil.removeExcludedRoot(module, out)
        }
    }

    fun `test copies unsaved editor text`() {
        val file = myFixture.tempDirFixture.createFile("doc/a.txt", "on disk")
        val document = FileDocumentManager.getInstance().getDocument(file)!!
        WriteCommandAction.runWriteCommandAction(project) { document.setText("in editor") }

        val result = CopyCollector(project).collect(listOf(file))
        assertEquals("a.txt", result.files.single().path)
        assertEquals("in editor", result.files.single().bytes.decodeToString())
    }

    fun `test multiple roots keep their own names`() {
        val temp = myFixture.tempDirFixture
        val a = temp.createFile("one/x.txt", "1")
        val b = temp.findOrCreateDir("two")
        temp.createFile("two/y.txt", "2")

        val paths = CopyCollector(project).collect(listOf(a, b)).files.map { it.path }
        assertEquals(listOf("x.txt", "two/y.txt"), paths)
    }
}
