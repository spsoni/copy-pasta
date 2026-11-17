package info.suryasoni.copy_pasta.ide

import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import info.suryasoni.copy_pasta.format.FileEntry
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions

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

    fun `test write applies safe permissions on a real disk`() {
        if (SystemInfo.isWindows) return
        val dir = Files.createTempDirectory("copy-pasta-perms")
        try {
            val target = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(dir)!!
            Files.writeString(dir.resolve("legacy.sh"), "old")
            Files.setPosixFilePermissions(dir.resolve("legacy.sh"), PosixFilePermissions.fromString("rw-------"))

            PasteWriter.write(
                project,
                target,
                listOf(
                    FileEntry("key.pem", "k".toByteArray(), executable = false, mode = "600".toInt(8)),
                    FileEntry("run.sh", "r".toByteArray(), executable = true, mode = "777".toInt(8)),
                    FileEntry("legacy.sh", "new".toByteArray(), executable = true), // 2.0 text: flag only
                ),
            )

            fun perms(name: String) = PosixFilePermissions.toString(Files.getPosixFilePermissions(dir.resolve(name)))
            assertEquals("rw-------", perms("key.pem"))
            assertEquals("rwxr-xr-x", perms("run.sh")) // assumes the usual umask 022 or 002
            assertEquals("rwx------", perms("legacy.sh"))
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    fun `test overwrite never widens access, and a permissions-only change is not identical`() {
        if (SystemInfo.isWindows) return
        val dir = Files.createTempDirectory("copy-pasta-perms")
        try {
            val target = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(dir)!!
            for (name in listOf("private.txt", "script.sh")) {
                Files.writeString(dir.resolve(name), "same")
                Files.setPosixFilePermissions(dir.resolve(name), PosixFilePermissions.fromString("rw-------"))
            }
            target.refresh(false, true)
            val files =
                listOf(
                    FileEntry("private.txt", "same".toByteArray(), executable = false, mode = "644".toInt(8)),
                    FileEntry("script.sh", "same".toByteArray(), executable = true, mode = "755".toInt(8)),
                )

            val plan = PasteWriter.plan(target, files)
            assertEquals(listOf(PasteStatus.IDENTICAL, PasteStatus.OVERWRITE), plan.map { it.status })

            PasteWriter.write(project, target, files)
            fun perms(name: String) = PosixFilePermissions.toString(Files.getPosixFilePermissions(dir.resolve(name)))
            assertEquals("rw-------", perms("private.txt"))
            assertEquals("rwx------", perms("script.sh"))
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    fun `test symlinks in the target are never followed`() {
        if (SystemInfo.isWindows) return // creating symlinks needs extra rights on Windows
        val root = Files.createTempDirectory("copy-pasta-links")
        try {
            val outside = Files.createDirectories(root.resolve("outside"))
            Files.writeString(outside.resolve("victim.txt"), "original")
            val dir = Files.createDirectories(root.resolve("target"))
            Files.createSymbolicLink(dir.resolve("link"), outside) // a folder link pointing out of the target
            Files.createSymbolicLink(dir.resolve("filelink"), outside.resolve("victim.txt"))
            Files.createSymbolicLink(dir.resolve("broken"), root.resolve("missing"))
            Files.createDirectories(dir.resolve("real/inner"))
            Files.createSymbolicLink(dir.resolve("real/inner/deep"), outside)
            val target = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(dir)!!
            target.refresh(false, true)

            val files =
                listOf(
                    entry("link/planted.txt", "x"),
                    entry("filelink", "overwritten"),
                    entry("broken", "x"),
                    entry("real/inner/deep/planted.txt", "x"),
                    entry("real/ok.txt", "fine"),
                )
            val plan = PasteWriter.plan(target, files)
            assertEquals(
                listOf(PasteStatus.BLOCKED, PasteStatus.BLOCKED, PasteStatus.BLOCKED, PasteStatus.BLOCKED, PasteStatus.NEW),
                plan.map { it.status },
            )

            // write() re-checks: even if the tree changed after planning, it never writes through a link.
            assertThrows(java.io.IOException::class.java) { PasteWriter.write(project, target, listOf(entry("link/planted.txt", "x"))) }
            assertThrows(java.io.IOException::class.java) { PasteWriter.write(project, target, listOf(entry("filelink", "overwritten"))) }
            assertFalse(Files.exists(outside.resolve("planted.txt")))
            assertEquals("original", Files.readString(outside.resolve("victim.txt")))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    fun `test names Windows can't hold safely are blocked on Windows targets only`() {
        val target = myFixture.tempDirFixture.findOrCreateDir("target")
        val unsafe =
            listOf(
                "a/C:/x.txt", "notes.txt:stream", "con.txt", "dir/AUX", "lpt1.log", "COM0", "com" + Char(0xB9) + ".txt",
                "trail./x", "space /x", "q?.txt", "pipe|.txt",
            )
        val onWindows = PasteWriter.plan(target, unsafe.map { entry(it, "x") } + entry("console.txt", "fine"), windows = true)
        assertEquals(unsafe.map { PasteStatus.BLOCKED } + PasteStatus.NEW, onWindows.map { it.status })
        val elsewhere = PasteWriter.plan(target, listOf(entry("12:30.log", "x"), entry("con.txt", "x")), windows = false)
        assertEquals(listOf(PasteStatus.NEW, PasteStatus.NEW), elsewhere.map { it.status })
    }

    fun `test VCS metadata lookalikes are blocked`() {
        val target = myFixture.tempDirFixture.findOrCreateDir("target")
        val names = VCS_LOOKALIKES
        val plan = PasteWriter.plan(target, names.map { entry(it, "x") } + entry("p/.github/ok.yml", "fine"))
        assertEquals(names.map { PasteStatus.BLOCKED } + PasteStatus.NEW, plan.map { it.status })
    }

    fun `test NFC and NFD spellings of one name are one file`() {
        val target = myFixture.tempDirFixture.findOrCreateDir("target")
        val nfc = "caf" + Char(0xE9) + ".txt"
        val nfd = "cafe" + Char(0x301) + ".txt"
        val plan = PasteWriter.plan(target, listOf(entry(nfc, "a"), entry(nfd, "b")), caseSensitive = true)
        assertEquals(listOf(PasteStatus.NEW, PasteStatus.BLOCKED), plan.map { it.status })
    }

    private companion object {
        // Invisible and special characters as code points, so an edit can't silently change them.
        val VCS_LOOKALIKES =
            listOf(
                ".git./hooks/a",
                ".git /hooks/b",
                "GIT~1/hooks/c",
                ".g" + Char(0x200C) + "it/hooks/d", // zero-width non-joiner, ignored by HFS+
                "p/.GIT/config",
                "x/.svn./entries",
                "HG~1/hgrc", // Windows short name of .hg
                "Svn~1/entries",
                "bzr~1/branch.conf",
                "." + Char(0x17F) + "vn/entries", // long s folds to s on APFS and ext4 casefold
                "CV" + Char(0x17F) + "/Root",
            )
    }
}
