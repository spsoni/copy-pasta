package info.suryasoni.copy_pasta.format

import java.text.Normalizer

/**
 * Path-segment rules for pasting (spec/FORMAT.md §5.1). IDE-free so every rule is unit-tested and matches the spec.
 * Invisible characters are written as code points, never as literals, so an edit can't silently change them.
 */
object PathRules {
    /**
     * Folders a pasted archive must never write into: VCS metadata can execute code (hooks, fsmonitor config). The
     * `~1` forms are their Windows 8.3 short names (the leading dot is dropped), which resolve to the same folder.
     */
    private val VCS_FOLDERS = setOf(".git", ".hg", ".svn", ".bzr", "cvs", "git~1", "hg~1", "svn~1", "bzr~1")

    /**
     * Code points that HFS+ ignores in file names, so `.git` with a zero-width non-joiner inside is the same folder
     * as `.git` on macOS (the CVE-2014-9390 family): U+200B–200F, U+202A–202E, U+2060–206F, U+FEFF.
     */
    private fun isIgnorable(codePoint: Int): Boolean =
        codePoint in 0x200B..0x200F || codePoint in 0x202A..0x202E || codePoint in 0x2060..0x206F || codePoint == 0xFEFF

    /**
     * Case folding as case-insensitive file systems do it: upper-case then lower-case each code point, so U+017F
     * (long s) becomes `s`, U+0131 (dotless i) becomes `i` and U+212A (Kelvin) becomes `k`, like APFS, ext4 casefold
     * and NTFS's upcase table.
     */
    fun fold(segment: String): String = buildString {
        segment.codePoints().forEach { appendCodePoint(Character.toLowerCase(Character.toUpperCase(it))) }
    }

    private val WINDOWS_RESERVED =
        setOf("con", "prn", "aux", "nul") +
            (0..9).flatMap { listOf("com$it", "lpt$it") } +
            listOf(0xB9, 0xB2, 0xB3).flatMap { superscript -> listOf("com" + Char(superscript), "lpt" + Char(superscript)) }

    private const val WINDOWS_FORBIDDEN_CHARS = ":<>\"|?*"

    /**
     * The key two paths are compared by to find entries that would land on the same file: Unicode NFC (macOS treats
     * NFC and NFD spellings as one name), plus [fold] on a case-insensitive target.
     */
    fun sameFileKey(path: String, caseSensitive: Boolean): String {
        val nfc = Normalizer.normalize(path, Normalizer.Form.NFC)
        return if (caseSensitive) nfc else fold(nfc)
    }

    /**
     * True when [segment] names VCS metadata on some file system: compared after removing HFS+-ignorable code points,
     * stripping trailing dots and spaces (Windows drops them, so `.git.` is `.git`), NFC-normalising and [fold]ing.
     */
    fun isVcsMetadata(segment: String): Boolean {
        val visible = buildString { segment.codePoints().filter { !isIgnorable(it) }.forEach { appendCodePoint(it) } }
        val normalized = fold(Normalizer.normalize(visible.trimEnd('.', ' '), Normalizer.Form.NFC))
        return normalized in VCS_FOLDERS
    }

    /**
     * True when [segment] can't be created safely on Windows: characters Windows forbids (`:` would also open an NTFS
     * alternate data stream), a trailing dot or space (silently dropped, so it aliases another name), or a reserved
     * device name, with or without an extension (`con.txt` is the console; `COM¹` is a serial port).
     */
    fun isUnsafeOnWindows(segment: String): Boolean {
        if (segment.any { it in WINDOWS_FORBIDDEN_CHARS }) return true
        if (segment.endsWith('.') || segment.endsWith(' ')) return true
        return fold(segment.substringBefore('.').trimEnd(' ')) in WINDOWS_RESERVED
    }
}
