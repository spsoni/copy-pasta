package info.suryasoni.copy_pasta.format

import java.util.Base64

/**
 * The text that goes on the clipboard. It is built to survive chat apps and email:
 *
 * ```
 * -----BEGIN COPY-PASTA-----
 * Copy-Pasta: 3 files, 4.1 KB. Paste it with the Copy-Pasta plugin for JetBrains IDEs: https://github.com/spsoni/copy-pasta
 * Format: 2; length: 5612
 * <Base64, wrapped at 76 columns>
 * -----END COPY-PASTA-----
 * ```
 *
 * The markers are found anywhere in the text, so surrounding chat text doesn't matter. Quote prefixes (`> `),
 * CRLF and re-wrapping are tolerated. The human-readable line tells the recipient what this is. The length lets a
 * truncated paste be reported as incomplete instead of failing with a confusing error.
 */
object Envelope {
    const val FORMAT_VERSION = 2
    const val HOME_URL = "https://github.com/spsoni/copy-pasta"
    private const val LINE_WIDTH = 76
    private const val PLAIN_LABEL = "COPY-PASTA"
    private const val ENCRYPTED_LABEL = "COPY-PASTA ENCRYPTED"

    private val BEGIN = Regex("""-{3,}\s*BEGIN (COPY-PASTA(?: ENCRYPTED)?)\s*-{3,}""", RegexOption.IGNORE_CASE)
    private val END = Regex("""-{3,}\s*END COPY-PASTA(?: ENCRYPTED)?\s*-{3,}""", RegexOption.IGNORE_CASE)
    private val FORMAT_LINE = Regex("""Format:\s*(\d+)\s*;\s*length:\s*(\d+)""", RegexOption.IGNORE_CASE)
    private val QUOTE_PREFIX = Regex("""^[\s>]+""")
    private val BASE64_LINE = Regex("""[A-Za-z0-9+/=]+""")

    class Decoded(val payload: ByteArray, val encrypted: Boolean)

    enum class Problem { NOT_FOUND, INCOMPLETE, CORRUPTED, NEWER_VERSION }

    class EnvelopeException(val problem: Problem, val received: Int = 0, val expected: Int = 0) : Exception(problem.name)

    /** [summary] is the human-readable part, e.g. "3 files, 4.1 KB". */
    fun encode(payload: ByteArray, encrypted: Boolean, summary: String): String {
        val body = Base64.getEncoder().encodeToString(payload)
        val label = if (encrypted) ENCRYPTED_LABEL else PLAIN_LABEL
        return buildString {
            append("-----BEGIN ").append(label).append("-----\n")
            append("Copy-Pasta: ").append(summary)
            append(". Paste it with the Copy-Pasta plugin for JetBrains IDEs: ").append(HOME_URL).append('\n')
            append("Format: ").append(FORMAT_VERSION).append("; length: ").append(body.length).append('\n')
            body.chunked(LINE_WIDTH).forEach { append(it).append('\n') }
            append("-----END ").append(label).append("-----\n")
        }
    }

    /** True when [text] looks like Copy-Pasta text (cheap check, no decoding). */
    fun looksLikeEnvelope(text: String): Boolean = BEGIN.containsMatchIn(text)

    fun decode(text: String): Decoded {
        val begin = BEGIN.find(text) ?: throw EnvelopeException(Problem.NOT_FOUND)
        val encrypted = begin.groupValues[1].contains("ENCRYPTED", ignoreCase = true)
        val rest = text.substring(begin.range.last + 1)
        val end = END.find(rest)
        val lines = (if (end != null) rest.substring(0, end.range.first) else rest)
            .lines()
            .map { it.replace(QUOTE_PREFIX, "").trim() }

        val formatIndex = lines.indexOfFirst { FORMAT_LINE.containsMatchIn(it) }
        var expected = -1
        val bodyLines = if (formatIndex >= 0) {
            val match = FORMAT_LINE.find(lines[formatIndex])!!
            if (match.groupValues[1].toInt() > FORMAT_VERSION) throw EnvelopeException(Problem.NEWER_VERSION)
            expected = match.groupValues[2].toInt()
            lines.drop(formatIndex + 1)
        } else {
            lines.filter { BASE64_LINE.matches(it) } // format line lost: keep only lines that can be Base64
        }
        val body = bodyLines.joinToString("") { line -> line.filterNot { it.isWhitespace() } }

        if (end == null || (expected >= 0 && body.length < expected)) {
            throw EnvelopeException(Problem.INCOMPLETE, received = body.length, expected = maxOf(expected, 0))
        }
        if (expected >= 0 && body.length != expected) throw EnvelopeException(Problem.CORRUPTED)
        val payload =
            try {
                Base64.getDecoder().decode(body)
            } catch (_: IllegalArgumentException) {
                throw EnvelopeException(Problem.CORRUPTED)
            }
        return Decoded(payload, encrypted)
    }
}
