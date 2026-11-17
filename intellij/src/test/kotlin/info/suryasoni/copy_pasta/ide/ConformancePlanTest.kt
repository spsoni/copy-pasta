package info.suryasoni.copy_pasta.ide

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import info.suryasoni.copy_pasta.format.Vectors

/**
 * The `blocked` expectations in `spec/vectors/manifest.json`: entries that decode fine but that a paste must never
 * write. Checked against an empty target on a case-insensitive file system, the strictest case.
 */
class ConformancePlanTest : BasePlatformTestCase() {
    fun `test blocked entries in the spec vectors`() {
        val cases = Vectors.loadCases().filter { it.error == null }
        assertTrue(cases.any { it.blocked.isNotEmpty() })
        for (case in cases) {
            val target = myFixture.tempDirFixture.findOrCreateDir("target-${case.name}")
            val files = Vectors.decodeAll(case.text(), case.passphrase)
            val blocked = PasteWriter.plan(target, files, caseSensitive = false).filter {
                it.status == PasteStatus.BLOCKED
            }.map { it.entry.path }
            assertEquals("blocked entries for ${case.name}", case.blocked.sorted(), blocked.sorted())
        }
    }
}
