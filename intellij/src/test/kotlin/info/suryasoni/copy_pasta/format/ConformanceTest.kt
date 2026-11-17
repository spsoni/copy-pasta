package info.suryasoni.copy_pasta.format

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/**
 * Runs every case in `spec/vectors/manifest.json` through the plugin's decode pipeline. These vectors are the
 * cross-implementation contract (see `spec/FORMAT.md`): the VS Code extension and the desktop CLI run the same cases.
 */
@RunWith(Parameterized::class)
class ConformanceTest(private val case: Vectors.Case) {
    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun cases(): List<Vectors.Case> = Vectors.loadCases().also { check(it.isNotEmpty()) { "no vectors in ${Vectors.dir}" } }
    }

    @Test
    fun decodes() {
        val text = case.text()
        val started = System.nanoTime()
        val result = runCatching { Vectors.decodeAll(text, case.passphrase) }
        val millis = (System.nanoTime() - started) / 1_000_000
        case.maxMillis?.let { assertTrue("${case.name} took $millis ms, budget $it ms", millis <= it) }

        if (case.error != null) {
            val error = result.exceptionOrNull()
            assertEquals("${case.name}: ${case.description}", case.error, error?.let(Vectors::category) ?: "decoded without error")
            return
        }
        val decoded = result.getOrElse { throw AssertionError("${case.name} failed with ${Vectors.category(it)}", it) }
        val expected = case.files!!.sortedBy { it.path }
        val actual = decoded.sortedBy { it.path }
        assertEquals(expected.map { it.path }, actual.map { it.path })
        for ((want, got) in expected.zip(actual)) {
            assertArrayEquals("content of ${want.path}", want.bytes, got.bytes)
            assertEquals("mode of ${want.path}", Vectors.octal(want.mode), Vectors.octal(got.mode))
            assertEquals("executable flag of ${want.path}", want.executable, got.executable)
        }
        case.encrypted?.let { assertEquals("encrypted label", it, Envelope.isEncrypted(text)) }
        case.summary?.let { assertEquals("summary line", it, Envelope.peek(text)?.summary) }
    }
}

class ModeRulesConformanceTest {
    @Test
    fun `safe and applied match spec modes json`() {
        val rules = Vectors.loadModeRules()
        assertTrue(rules.isNotEmpty())
        for (rule in rules) {
            val label = "sender ${Vectors.octal(rule.sender)}, current ${Vectors.octal(rule.current)}"
            assertEquals("safe: $label", Vectors.octal(rule.safe), Vectors.octal(FileMode.safe(rule.sender)))
            assertEquals("applied: $label", Vectors.octal(rule.applied), Vectors.octal(FileMode.applied(rule.sender, rule.current)))
        }
    }
}
