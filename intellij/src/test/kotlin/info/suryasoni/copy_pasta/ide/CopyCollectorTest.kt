package info.suryasoni.copy_pasta.ide

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.PsiTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class CopyCollectorTest : BasePlatformTestCase() {
    /** Runs like production: on a background thread, while the EDT keeps dispatching events. */
    private fun collect(vararg roots: VirtualFile): CopyCollector.Result {
        val future = ApplicationManager.getApplication().executeOnPooledThread<CopyCollector.Result> {
            CopyCollector(project).collect(roots.toList())
        }
        return PlatformTestUtil.waitForFuture(future, 30_000)
    }

    fun `test skips excluded and globally ignored files but honours explicit selections`() {
        val temp = myFixture.tempDirFixture
        val root = temp.findOrCreateDir("proj")
        temp.createFile("proj/src/Main.kt", "fun main() {}")
        temp.createFile("proj/.DS_Store", "junk")
        temp.createFile("proj/out/Main.class", "bin")
        val out = temp.getFile("proj/out")!!
        PsiTestUtil.addExcludedRoot(module, out)
        try {
            val result = collect(root)
            assertEquals(listOf("proj/src/Main.kt"), result.files.map { it.path })
            assertEquals(2, result.skippedIgnored)

            val explicit = collect(out)
            assertEquals(listOf("out/Main.class"), explicit.files.map { it.path })
        } finally {
            PsiTestUtil.removeExcludedRoot(module, out)
        }
    }

    fun `test copies unsaved editor text`() {
        val file = myFixture.tempDirFixture.createFile("doc/a.txt", "on disk")
        val document = FileDocumentManager.getInstance().getDocument(file)!!
        WriteCommandAction.runWriteCommandAction(project) { document.setText("in editor") }

        val result = collect(file)
        assertEquals("a.txt", result.files.single().path)
        assertEquals("in editor", result.files.single().bytes.decodeToString())
    }

    fun `test same-named roots are kept apart and nested selections are not duplicated`() {
        val temp = myFixture.tempDirFixture
        temp.createFile("ws/moduleA/src/Main.kt", "A")
        temp.createFile("ws/moduleB/src/Main.kt", "B")
        val srcA = temp.getFile("ws/moduleA/src")!!
        val srcB = temp.getFile("ws/moduleB/src")!!
        val nested = temp.getFile("ws/moduleA/src/Main.kt")!!

        val result = collect(srcA, srcB, nested)
        assertEquals(listOf("moduleA/src/Main.kt", "moduleB/src/Main.kt"), result.files.map { it.path })
        assertEquals(listOf("A", "B"), result.files.map { it.bytes.decodeToString() })
        assertEquals(0, result.skippedDuplicates)
    }

    fun `test multiple roots keep their own names`() {
        val temp = myFixture.tempDirFixture
        val a = temp.createFile("one/x.txt", "1")
        val b = temp.findOrCreateDir("two")
        temp.createFile("two/y.txt", "2")

        assertEquals(listOf("x.txt", "two/y.txt"), collect(a, b).files.map { it.path })
    }
}
