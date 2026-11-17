package info.suryasoni.copy_pasta.ide

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import info.suryasoni.copy_pasta.format.Vectors

/**
 * The `blocked` and `blockedOnWindows` expectations in `spec/vectors/manifest.json`: entries that decode fine but
 * that a paste must never write. Checked against an empty, case-insensitive target (the strictest case), once as a
 * Windows target and once as a macOS/Linux one.
 */
class ConformancePlanTest : BasePlatformTestCase() {
    fun `test blocked entries in the spec vectors`() {
        val cases = Vectors.loadCases().filter { it.error == null }
        assertTrue(cases.any { it.blocked.isNotEmpty() })
        assertTrue(cases.any { it.blockedOnWindows.isNotEmpty() })
        for (case in cases) {
            val target = myFixture.tempDirFixture.findOrCreateDir("target-${case.name}")
            val files = Vectors.decodeAll(case.text(), case.passphrase)
            for (windows in listOf(false, true)) {
                val blocked =
                    PasteWriter
                        .plan(target, files, caseSensitive = false, windows = windows)
                        .filter { it.status == PasteStatus.BLOCKED }
                        .map { it.entry.path }
                val expected = if (windows) case.blocked + case.blockedOnWindows else case.blocked
                assertEquals("blocked entries for ${case.name} (windows=$windows)", expected.sorted(), blocked.sorted())
            }
        }
    }
}
