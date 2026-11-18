package info.suryasoni.copy_pasta.format

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.file.Files
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Writes `spec/vectors/` (run with `make vectors`). A no-op in normal test runs.
 *
 * Expectations are written out by hand here, not computed by the code under test, so a vector records what the spec
 * says rather than what the plugin happens to do. Existing `.txt` files are kept (encrypted cases have a random salt
 * and IV, and ZIPs from [ZipCodec] carry a timestamp), so regenerating only adds new cases and rewrites the
 * manifest. `-PregenerateVectors=force` rewrites every file.
 */
class VectorGenerator {
    private val mode = System.getProperty("copyPasta.regenerateVectors").orEmpty()

    private class Gen(
        val name: String,
        val description: String,
        val text: () -> String,
        val passphrase: String? = null,
        val error: String? = null,
        val files: List<FileEntry>? = null,
        val encrypted: Boolean? = null,
        val blocked: List<String> = emptyList(),
        val blockedOnWindows: List<String> = emptyList(),
        val summary: String? = null,
        val maxMillis: Long? = null,
    )

    private fun octal(text: String) = text.toInt(8)

    private fun file(path: String, text: String, mode: String? = null, executable: Boolean = false) =
        FileEntry(path, text.toByteArray(), executable, mode?.let(::octal))

    private fun plain(files: List<FileEntry>, summary: String) = Envelope.encode(ZipCodec.encode(files), encrypted = false, summary)

    private fun plainPayload(payload: ByteArray, summary: String = "1 file, 1 KB") = Envelope.encode(payload, encrypted = false, summary)

    private fun encryptedPayload(payload: ByteArray, summary: String = "1 file, 1 KB") = Envelope.encode(payload, encrypted = true, summary)

    /** A ZIP written directly, for archives [ZipCodec.encode] would never produce. Fixed timestamps keep it stable. */
    private fun rawZip(vararg entries: Triple<String, ByteArray, ByteArray?>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.setLevel(Deflater.BEST_COMPRESSION)
            for ((name, bytes, extra) in entries) {
                val entry = ZipEntry(name)
                entry.time = FIXED_TIME
                extra?.let { entry.extra = it }
                zip.putNextEntry(entry)
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun entry(name: String, text: String = "x", extra: ByteArray? = null) = Triple(name, text.toByteArray(), extra)

    /** An envelope around a Base64 body given as is, for bodies [Envelope.encode] would never write. */
    private fun envelopeOf(body: String) = buildString {
        append("-----BEGIN COPY-PASTA-----\n")
        append("Copy-Pasta: 1 file, 1 byte. Paste it with the Copy-Pasta plugin for JetBrains IDEs: https://github.com/spsoni/copy-pasta\n")
        append("Format: 2; length: ").append(body.length).append('\n')
        body.chunked(76).forEach { append(it).append('\n') }
        append("-----END COPY-PASTA-----\n")
    }

    private fun base64(bytes: ByteArray) = java.util.Base64.getEncoder().encodeToString(bytes)

    /** A one-file ZIP whose Base64 ends in `==`, so padding and trailing bits can be exercised. */
    private val paddedZip: Pair<ByteArray, FileEntry> by lazy {
        (1..10)
            .map { n -> "p".repeat(n) }
            .map { text -> rawZip(entry("pad.txt", text)) to FileEntry("pad.txt", text.toByteArray()) }
            .first { (zip, _) -> base64(zip).endsWith("==") }
    }

    /** Little-endian helpers for hand-built ZIP records. */
    private fun le16(value: Int) = byteArrayOf(value.toByte(), (value shr 8).toByte())

    private fun le32(value: Long) = le16((value and 0xFFFF).toInt()) + le16(((value shr 16) and 0xFFFF).toInt())

    /** A Stored entry that also sets general-purpose bit 3 (sizes and CRC in a trailing data descriptor). */
    private fun storedWithDataDescriptor(): ByteArray {
        val name = "s.txt".toByteArray()
        val data = "stored".toByteArray()
        val crc = java.util.zip.CRC32().apply { update(data) }.value
        val local =
            byteArrayOf(0x50, 0x4B, 0x03, 0x04) + le16(20) + le16(0x0008) + le16(0) + le16(0) + le16(0x5C21) +
                le32(0) + le32(0) + le32(0) + le16(name.size) + le16(0) + name + data
        val descriptor = byteArrayOf(0x50, 0x4B, 0x07, 0x08) + le32(crc) + le32(data.size.toLong()) + le32(data.size.toLong())
        return local + descriptor
    }

    /** A hand-built local header followed by [name]; data and descriptor are appended by the caller. */
    private fun localHeader(flags: Int, method: Int, crc: Long, compressedSize: Long, size: Long, name: String): ByteArray {
        val nameBytes = name.toByteArray()
        return byteArrayOf(0x50, 0x4B, 0x03, 0x04) + le16(20) + le16(flags) + le16(method) + le16(0) + le16(0x5C21) +
            le32(crc) + le32(compressedSize) + le32(size) + le16(nameBytes.size) + le16(0) + nameBytes
    }

    private fun crc(data: ByteArray) = java.util.zip.CRC32().apply { update(data) }.value

    private fun rawDeflate(data: ByteArray): ByteArray {
        val deflater = Deflater(Deflater.BEST_COMPRESSION, true)
        deflater.setInput(data)
        deflater.finish()
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        while (!deflater.finished()) out.write(buffer, 0, deflater.deflate(buffer))
        deflater.end()
        return out.toByteArray()
    }

    /** A Deflated bit-3 entry whose data descriptor has no `PK\x07\x08` signature (12 bytes: CRC and sizes). */
    private fun unsignedDescriptorEntry(name: String, text: String): ByteArray {
        val data = text.toByteArray()
        val deflated = rawDeflate(data)
        return localHeader(0x0008, 8, 0, 0, 0, name) + deflated + le32(crc(data)) + le32(deflated.size.toLong()) + le32(data.size.toLong())
    }

    private fun indexOf(haystack: ByteArray, needle: ByteArray, from: Int = 0): Int =
        (from..haystack.size - needle.size).first { i -> needle.indices.all { haystack[i + it] == needle[it] } }

    private val binary = ByteArray(256) { it.toByte() }

    /** A realistic tree: text, a script, a private key, an empty file, binary content and a non-ASCII path. */
    private val project =
        listOf(
            file("demo/README.md", "# Demo\n", "644"),
            file("demo/src/main.kt", "fun main() = println(\"hi\")\n", "644"),
            file("demo/run.sh", "#!/bin/sh\necho hi\n", "755", executable = true),
            file("demo/secret.pem", "private key material\n", "600"),
            file("demo/empty.txt", "", "644"),
            FileEntry("demo/bin.dat", binary, false, octal("644")),
            file("demo/unicodé/naïve.txt", "Grüße, 世界\n", "644"),
        )

    private val hello =
        listOf(
            file("hello/hello.txt", "Hello, world!\n", "644"),
            file("hello/run.sh", "#!/bin/sh\necho hello\n", "755", executable = true),
        )

    private val helloText by lazy { plain(hello, "2 files, 40 bytes") }

    /** Invisible and special characters as code points, so an edit can't silently change them. */
    private val vcsLookalikes =
        listOf(
            ".git./hooks/a",
            ".git /hooks/b",
            "GIT~1/hooks/c",
            ".g" + Char(0x200C) + "it/hooks/d",
            "HG~1/hgrc",
            "svn~1/entries",
            "bzr~1/branch.conf",
            "." + Char(0x17F) + "vn/entries",
        ).map { file(it, "x") }

    private val windowsUnsafe =
        listOf("a/C:/x.txt", "notes.txt:stream", "con.txt", "COM0", "com" + Char(0xB9) + ".log", "trail./x").map { file(it, "x") }

    private val cases: List<Gen> by lazy {
        listOf(
            Gen("plain-project", "A plain envelope with text, binary, empty, executable, private and non-ASCII files.", {
                plain(project, "7 files, 1 KB")
            }, files = project, encrypted = false, summary = "7 files, 1 KB"),
            Gen(
                "encrypted-project",
                "The same tree, encrypted with 600,000 PBKDF2 iterations (the current default).",
                {
                    encryptedPayload(
                        Crypto.encrypt(ZipCodec.encode(project), "correct horse battery staple".toCharArray()),
                        "7 files, 1 KB",
                    )
                },
                passphrase = "correct horse battery staple",
                files = project,
                encrypted = true,
                summary = "7 files, 1 KB",
            ),
            Gen(
                "encrypted-one-iteration",
                "Readers take the iteration count from the payload (any value from 1 to 5,000,000), never a constant.",
                { encryptedPayload(Crypto.encrypt(ZipCodec.encode(hello), "pw".toCharArray(), iterations = 1)) },
                passphrase = "pw",
                files = hello,
                encrypted = true,
            ),
            Gen(
                "encrypted-wrong-passphrase",
                "GCM authentication fails, so the error is a wrong passphrase, never garbage output.",
                { encryptedPayload(Crypto.encrypt(ZipCodec.encode(hello), "right".toCharArray(), iterations = 1000)) },
                passphrase = "wrong",
                error = "WRONG_PASSPHRASE",
            ),
            Gen(
                "encrypted-iterations-over-cap",
                "An iteration count above 5,000,000 is rejected before any key derivation runs.",
                {
                    val header = ByteBuffer.allocate(8).put("CPv2".toByteArray()).putInt(5_000_001).array()
                    encryptedPayload(header + ByteArray(16) + ByteArray(12) + ByteArray(32))
                },
                passphrase = "x",
                error = "INVALID_ARCHIVE",
            ),
            Gen(
                "encrypted-label-plain-payload",
                "An ENCRYPTED label whose body doesn't start with CPv2.",
                { encryptedPayload(ZipCodec.encode(hello)) },
                passphrase = "x",
                error = "INVALID_ARCHIVE",
            ),
            Gen(
                "legacy-2.0-exec-flag",
                "Copy-Pasta 2.0 wrote a one-byte 0x4350 field, only for executable files: executable, mode unknown.",
                {
                    plain(
                        listOf(FileEntry("tool.sh", "#!/bin/sh\n".toByteArray(), true), FileEntry("notes.txt", "n\n".toByteArray())),
                        "2 files, 13 bytes",
                    )
                },
                files = listOf(FileEntry("tool.sh", "#!/bin/sh\n".toByteArray(), true), FileEntry("notes.txt", "n\n".toByteArray())),
            ),
            Gen(
                "extra-field-forward-compat",
                "Another extra field before 0x4350, and a 0x4350 field longer than 3 bytes (future appended data).",
                {
                    // Info-ZIP Unix uid/gid field (0x7875): version 1, 4-byte uid 1000, 4-byte gid 1000. (The JDK drops
                    // an extended-timestamp field from extras it writes, so that one can't be used here.)
                    val id1000 = byteArrayOf(0x04, 0xE8.toByte(), 0x03, 0x00, 0x00) // size 4, then 1000 little-endian
                    val unixOwner = byteArrayOf(0x75, 0x78, 0x0B, 0x00, 0x01) + id1000 + id1000
                    val permissions = byteArrayOf(0x50, 0x43, 0x05, 0x00, 0x01, 0xED.toByte(), 0x01, 0xAA.toByte(), 0xBB.toByte())
                    plainPayload(rawZip(entry("future.sh", "#!/bin/sh\n", unixOwner + permissions)))
                },
                files = listOf(file("future.sh", "#!/bin/sh\n", "755", executable = true)),
            ),
            Gen("quoted-reply", "Every line quoted with \"> \", as email and chat replies do.", {
                helloText.lines().joinToString("\n") { "> $it" }
            }, files = hello),
            Gen("crlf", "Windows line endings.", { helloText.replace("\n", "\r\n") }, files = hello),
            Gen("collapsed-newlines", "A chat app turned every newline into a space.", { helloText.replace("\n", " ") }, files = hello),
            Gen("chat-wrapped", "Chat text before and after the envelope.", {
                "Hi! Here are the files you asked for:\n\n${helloText}\nLet me know if it works.\n"
            }, files = hello),
            Gen("lowercase-markers", "Markers are case-insensitive.", {
                helloText.replace("BEGIN COPY-PASTA", "begin copy-pasta").replace("END COPY-PASTA", "end copy-pasta")
            }, files = hello),
            Gen(
                "rewrapped-body",
                "The Base64 body re-wrapped at 60 columns with trailing spaces and indentation.",
                {
                    val lines = helloText.lines()
                    val formatIndex = lines.indexOfFirst { it.startsWith("Format:") }
                    val endIndex = lines.indexOfFirst { it.startsWith("-----END") }
                    val body = lines.subList(formatIndex + 1, endIndex).joinToString("")
                    (
                        lines.subList(0, formatIndex + 1) + body.chunked(60).map {
                            "  $it   "
                        } + lines.subList(endIndex, lines.size)
                        ).joinToString("\n")
                },
                files = hello,
            ),
            Gen("format-line-missing", "The Format line was lost: readers keep only lines that can be Base64.", {
                helloText.lines().filterNot { it.startsWith("Format:") }.joinToString("\n")
            }, files = hello),
            Gen("two-envelopes", "Two envelopes in one text: the first one wins.", {
                helloText + "\n" +
                    plain(listOf(file("other.txt", "other\n", "644")), "1 file, 6 bytes")
            }, files = hello),
            Gen("truncated-no-end", "Cut off in the middle: no END marker.", {
                helloText.substring(0, helloText.length / 2)
            }, error = "INCOMPLETE"),
            Gen(
                "truncated-with-end",
                "Body lines lost but the END marker survived: shorter than the declared length.",
                {
                    val lines = helloText.lines()
                    val formatIndex = lines.indexOfFirst { it.startsWith("Format:") }
                    (lines.take(formatIndex + 2) + lines.drop(formatIndex + 3)).joinToString("\n")
                },
                error = "INCOMPLETE",
            ),
            Gen("body-longer-than-length", "More Base64 than the declared length.", {
                helloText.replace("-----END", "AAAA\n-----END")
            }, error = "CORRUPTED"),
            Gen(
                "corrupted-base64",
                "A character that isn't Base64, at the declared length.",
                {
                    val lines = helloText.lines().toMutableList()
                    val index = lines.indexOfFirst { it.startsWith("Format:") } + 1
                    lines[index] = "*" + lines[index].drop(1)
                    lines.joinToString("\n")
                },
                error = "CORRUPTED",
            ),
            Gen("newer-format", "Format 3 is from a newer version: readers ask the user to update.", {
                helloText.replace("Format: 2;", "Format: 3;")
            }, error = "NEWER_VERSION"),
            Gen("not-found", "Ordinary chat text.", { "Could you send me the files again?\n" }, error = "NOT_FOUND"),
            Gen("dash-flood", "64k dashes (a log line or table): marker search must stay linear.", {
                "-".repeat(65_536) + "\n"
            }, error = "NOT_FOUND", maxMillis = 2000),
            Gen("not-a-zip", "A plain envelope whose body isn't a ZIP.", {
                plainPayload("hello".toByteArray())
            }, error = "INVALID_ARCHIVE"),
            Gen("zip-slip-parent", "An entry that escapes the target folder.", {
                plainPayload(rawZip(entry("../evil.txt")))
            }, error = "INVALID_ARCHIVE"),
            Gen("zip-slip-nested", "An entry that escapes through a nested ..", {
                plainPayload(rawZip(entry("a/../../evil.txt")))
            }, error = "INVALID_ARCHIVE"),
            Gen("absolute-path", "An absolute Unix path.", { plainPayload(rawZip(entry("/etc/evil"))) }, error = "INVALID_ARCHIVE"),
            Gen("windows-drive-path", "A Windows drive path.", { plainPayload(rawZip(entry("C:/evil.txt"))) }, error = "INVALID_ARCHIVE"),
            Gen(
                "paths-normalized",
                "Backslashes become /, and empty and . segments are dropped.",
                { plainPayload(rawZip(entry("dir\\file.txt", "a"), entry("./dir2//x.txt", "b"))) },
                files = listOf(FileEntry("dir/file.txt", "a".toByteArray()), FileEntry("dir2/x.txt", "b".toByteArray())),
            ),
            Gen("duplicate-after-normalization", "Two entries that name the same file.", {
                plainPayload(rawZip(entry("a.txt"), entry("./a.txt")))
            }, error = "INVALID_ARCHIVE"),
            Gen(
                "directory-entries-ignored",
                "Directory entries carry no content and are skipped.",
                { plainPayload(rawZip(entry("d/", ""), entry("d/f.txt", "f"))) },
                files = listOf(FileEntry("d/f.txt", "f".toByteArray())),
            ),
            Gen(
                "zip-bomb-size",
                "One entry that inflates past the 200 MB decode limit (about 200 KB compressed).",
                {
                    val out = ByteArrayOutputStream()
                    ZipOutputStream(out).use { zip ->
                        zip.setLevel(Deflater.BEST_COMPRESSION)
                        zip.putNextEntry(ZipEntry("zeros.bin").apply { time = FIXED_TIME })
                        val chunk = ByteArray(1 shl 20)
                        repeat(200) { zip.write(chunk) }
                        zip.write(ByteArray(1))
                        zip.closeEntry()
                    }
                    plainPayload(out.toByteArray())
                },
                error = "INVALID_ARCHIVE",
            ),
            Gen(
                "vcs-metadata-blocked",
                "Decodes, but a paste must never write into VCS metadata such as .git/hooks.",
                {
                    plain(
                        listOf(file("p/.git/hooks/pre-commit", "#!/bin/sh\n", "755", true), file("p/ok.txt", "ok\n", "644")),
                        "2 files, 13 bytes",
                    )
                },
                files = listOf(file("p/.git/hooks/pre-commit", "#!/bin/sh\n", "755", true), file("p/ok.txt", "ok\n", "644")),
                blocked = listOf("p/.git/hooks/pre-commit"),
            ),
            Gen(
                "case-clash-blocked",
                "Decodes, but on a case-insensitive target the second of two case-variant names is blocked.",
                { plain(listOf(file("p/Readme.md", "a"), file("p/README.md", "b")), "2 files, 2 bytes") },
                files = listOf(file("p/Readme.md", "a"), file("p/README.md", "b")),
                blocked = listOf("p/README.md"),
            ),
            Gen(
                "file-folder-clash-blocked",
                "Decodes, but a file can't sit where another entry needs a folder.",
                { plain(listOf(file("p/x", "file"), file("p/x/y.txt", "inner")), "2 files, 9 bytes") },
                files = listOf(file("p/x", "file"), file("p/x/y.txt", "inner")),
                blocked = listOf("p/x"),
            ),
            Gen(
                "nbsp-before-begin",
                "A no-break space (U+00A0) between the dashes and BEGIN: \\s means ASCII whitespace only, so no marker.",
                { helloText.replace("-----BEGIN", "----- BEGIN") },
                error = "NOT_FOUND",
            ),
            Gen(
                "base64-unpadded",
                "Base64 without its = padding still decodes; the declared length counts the characters actually sent.",
                { envelopeOf(base64(paddedZip.first).trimEnd('=')) },
                files = listOf(paddedZip.second),
            ),
            Gen(
                "base64-nonzero-trailing-bits",
                "Unused low bits in the last Base64 character are set (like QR== for QQ==): they are ignored.",
                {
                    val body = base64(paddedZip.first)
                    val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
                    val last = body.length - 3 // the character before "=="
                    envelopeOf(body.substring(0, last) + alphabet[alphabet.indexOf(body[last]) or 1] + "==")
                },
                files = listOf(paddedZip.second),
            ),
            Gen(
                "begin-end-adjacent",
                "BEGIN's trailing dash run swallows END's leading dashes, so there is no END marker.",
                { "-----BEGIN COPY-PASTA----------END COPY-PASTA-----\n" },
                error = "INCOMPLETE",
            ),
            Gen(
                "zip-stored-with-data-descriptor",
                "A Stored entry can't use a data descriptor (general-purpose bit 3).",
                { plainPayload(storedWithDataDescriptor()) },
                error = "INVALID_ARCHIVE",
            ),
            Gen(
                "zip-bad-crc",
                "The CRC-32 in the data descriptor doesn't match the content.",
                {
                    val zip = rawZip(entry("c.txt", "checked"))
                    val descriptor = indexOf(zip, byteArrayOf(0x50, 0x4B, 0x07, 0x08))
                    zip[descriptor + 4] = (zip[descriptor + 4].toInt() xor 0xFF).toByte()
                    plainPayload(zip)
                },
                error = "INVALID_ARCHIVE",
            ),
            Gen(
                "zip-magic-only",
                "Just the local-header signature: a streaming reader sees no entries. Zero files is not an error.",
                { plainPayload(byteArrayOf(0x50, 0x4B, 0x03, 0x04)) },
                files = emptyList(),
            ),
            Gen(
                "zip-no-central-directory",
                "The central directory is cut off and replaced by junk: readers stream local headers and stop there.",
                {
                    val zip = rawZip(entry("kept.txt", "kept"))
                    val central = indexOf(zip, byteArrayOf(0x50, 0x4B, 0x01, 0x02))
                    plainPayload(zip.copyOfRange(0, central) + "this is not a zip record, just trailing junk".toByteArray())
                },
                files = listOf(FileEntry("kept.txt", "kept".toByteArray())),
            ),
            // Story 003 (2.1.1): rules added after the independent review.
            Gen(
                "label-unicode-case",
                "Markers are case-insensitive for ASCII only: \"PAſTA\" (long s, U+017F) is not \"PASTA\".",
                { helloText.replace("COPY-PASTA", "COPY-PAſTA") },
                error = "NOT_FOUND",
            ),
            Gen(
                "body-non-ascii",
                "A non-ASCII character in a complete body is damage, whatever the length says.",
                { "-----BEGIN COPY-PASTA-----\nFormat: 2; length: 10\nAAAAAAA😀\n-----END COPY-PASTA-----\n" },
                error = "CORRUPTED",
            ),
            Gen(
                "encrypted-too-short-for-tag",
                "A CPv2 payload with no room for the 16-byte GCM tag is rejected before key derivation (5M iterations).",
                {
                    val header = ByteBuffer.allocate(8).put("CPv2".toByteArray()).putInt(5_000_000).array()
                    encryptedPayload(header + ByteArray(16) + ByteArray(12) + ByteArray(15))
                },
                passphrase = "x",
                error = "INVALID_ARCHIVE",
                maxMillis = 2000,
            ),
            Gen(
                "zip-name-control-character",
                "A control character (U+0001) in an entry name.",
                { plainPayload(rawZip(entry("a\u0001b.txt"))) },
                error = "INVALID_ARCHIVE",
            ),
            Gen(
                "zip-name-invalid-utf8",
                "An entry name that isn't valid UTF-8 (a lead byte followed by ASCII).",
                {
                    val zip = rawZip(entry("aaaa.txt"))
                    zip[indexOf(zip, "aaaa".toByteArray())] = 0xC3.toByte() // the local header's copy of the name
                    plainPayload(zip)
                },
                error = "INVALID_ARCHIVE",
            ),
            Gen(
                "zip-truncated-deflate",
                "Deflated data cut off in the middle of an entry.",
                {
                    val zip = rawZip(Triple("big.bin", ByteArray(100_000) { (it * 31).toByte() }, null))
                    plainPayload(zip.copyOfRange(0, zip.size / 2))
                },
                error = "INVALID_ARCHIVE",
            ),
            Gen(
                "vcs-lookalikes-blocked",
                "Names that are VCS metadata on Windows or macOS: trailing dot or space, 8.3 short names, an HFS+-ignored " +
                    "character (U+200C), a letter that case-folds to ASCII (U+017F long s).",
                { plain(vcsLookalikes + file("ok.txt", "x"), "9 files, 9 bytes") },
                files = vcsLookalikes + file("ok.txt", "x"),
                blocked = vcsLookalikes.map { it.path },
            ),
            Gen(
                "windows-unsafe-names",
                "Decodes, but on Windows a colon (drive or alternate data stream), a device name (also COM0 and the " +
                    "superscript COM¹) or a trailing dot is blocked.",
                { plain(windowsUnsafe + file("ok.txt", "x"), "7 files, 7 bytes") },
                files = windowsUnsafe + file("ok.txt", "x"),
                blockedOnWindows = windowsUnsafe.map { it.path },
            ),
            // Story 004: found by the independent review of the TypeScript reader against ZipInputStream.
            Gen(
                "zip-stored-empty-bad-crc",
                "An empty Stored entry with a non-zero CRC: the reference never checks an empty Stored entry's CRC.",
                { plainPayload(localHeader(0, 0, 0x12345678, 0, 0, "a.txt")) },
                files = listOf(FileEntry("a.txt", ByteArray(0))),
            ),
            Gen(
                "zip-stored-compressed-size-marker",
                "A Stored entry whose unused compressed-size field is 0xFFFFFFFF, without Zip64: not Zip64, decodes.",
                { plainPayload(localHeader(0, 0, crc("stored".toByteArray()), 0xFFFFFFFFL, 6, "s.txt") + "stored".toByteArray()) },
                files = listOf(FileEntry("s.txt", "stored".toByteArray())),
            ),
            Gen(
                "zip-descriptor-unsigned",
                "A data descriptor without its optional signature, followed by another entry.",
                {
                    plainPayload(
                        unsignedDescriptorEntry("u.txt", "unsigned") + localHeader(0, 0, crc("next".toByteArray()), 4, 4, "n.txt") +
                            "next".toByteArray(),
                    )
                },
                files = listOf(FileEntry("u.txt", "unsigned".toByteArray()), FileEntry("n.txt", "next".toByteArray())),
            ),
            Gen(
                "zip-descriptor-unsigned-at-end",
                "An unsigned data descriptor with fewer than 16 bytes left: the reference reads 16 bytes, so it's damage.",
                { plainPayload(unsignedDescriptorEntry("u.txt", "unsigned")) },
                error = "INVALID_ARCHIVE",
            ),
            // Story 004: found while writing the TypeScript reader.
            Gen(
                "zip-directory-entry-bomb",
                "A directory entry carrying data that inflates past the 200 MB limit: directory data counts too.",
                {
                    val out = ByteArrayOutputStream()
                    ZipOutputStream(out).use { zip ->
                        zip.setLevel(Deflater.BEST_COMPRESSION)
                        zip.putNextEntry(ZipEntry("d/").apply { time = FIXED_TIME })
                        val chunk = ByteArray(1 shl 20)
                        repeat(200) { zip.write(chunk) }
                        zip.write(ByteArray(1))
                        zip.closeEntry()
                    }
                    plainPayload(out.toByteArray())
                },
                error = "INVALID_ARCHIVE",
            ),
            Gen(
                "summary-sanitized",
                "The summary line is untrusted: only letters, digits, spaces and ,.- survive, capped at 60 characters.",
                { plain(hello, "<b>2 files</b> & <script>alert(1)</script>") },
                files = hello,
                summary = "b2 filesb  scriptalert1script",
            ),
        )
    }

    /** `FileMode.safe` and `FileMode.applied`, as a table every implementation checks. */
    private val modeRules =
        listOf(
            // sender, current (the new file under the recipient's umask, or the file being overwritten), safe, applied
            listOf("0644", "0644", "0644", "0644"),
            listOf("0755", "0644", "0755", "0755"),
            listOf("0600", "0644", "0600", "0600"),
            listOf("0777", "0644", "0755", "0755"),
            listOf("0777", "0664", "0755", "0755"),
            listOf("0664", "0644", "0644", "0644"),
            listOf("0444", "0644", "0644", "0644"),
            listOf("0000", "0644", "0600", "0600"),
            listOf("4777", "0644", "0755", "0755"),
            listOf("0644", "0600", "0644", "0600"),
            listOf("0755", "0600", "0755", "0700"),
            listOf("0644", "0640", "0644", "0640"),
            listOf("0750", "0644", "0750", "0750"),
        )

    @Test
    fun generate() {
        if (mode.isEmpty()) return
        val force = mode == "force"
        Files.createDirectories(Vectors.dir)
        val list = JsonArray()
        for (case in cases) {
            val fileName = "${case.name}.txt"
            val path = Vectors.dir.resolve(fileName)
            if (force || !Files.exists(path)) Files.writeString(path, case.text())
            list.add(
                JsonObject().apply {
                    addProperty("name", case.name)
                    addProperty("description", case.description)
                    addProperty("file", fileName)
                    addProperty("passphrase", case.passphrase)
                    case.maxMillis?.let { addProperty("maxMillis", it) }
                    add(
                        "expect",
                        JsonObject().apply {
                            if (case.error != null) {
                                addProperty("error", case.error)
                            } else {
                                add("files", JsonArray().apply { case.files!!.forEach { add(Vectors.jsonFile(it)) } })
                                case.encrypted?.let { addProperty("encrypted", it) }
                                if (case.blocked.isNotEmpty()) add("blocked", Vectors.jsonArray(case.blocked))
                                if (case.blockedOnWindows.isNotEmpty()) add("blockedOnWindows", Vectors.jsonArray(case.blockedOnWindows))
                                case.summary?.let { addProperty("summary", it) }
                            }
                        },
                    )
                },
            )
        }
        Vectors.writeJson(
            Vectors.manifest,
            JsonObject().apply {
                addProperty("format", 2)
                addProperty("spec", "../FORMAT.md")
                add("cases", list)
            },
        )
        Vectors.writeJson(
            Vectors.modes,
            JsonObject().apply {
                addProperty("spec", "../FORMAT.md#permissions")
                add(
                    "rules",
                    JsonArray().apply {
                        for ((sender, current, safe, applied) in modeRules) {
                            add(
                                JsonObject().apply {
                                    addProperty("sender", sender)
                                    addProperty("current", current)
                                    addProperty("safe", safe)
                                    addProperty("applied", applied)
                                },
                            )
                        }
                    },
                )
            },
        )
    }

    private companion object {
        const val FIXED_TIME = 1_767_225_600_000L // 2026-01-01T00:00:00Z
    }
}
