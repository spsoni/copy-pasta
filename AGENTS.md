# AGENTS.md — copy-pasta

An IntelliJ Platform plugin: **Copy** turns selected files and folders into one self-describing block of text (ZIP →
Base64, optionally passphrase-encrypted) to paste into any chat. **Paste** previews and recreates the files in another
IDE. Public repo `github.com/spsoni/copy-pasta`, plugin id `info.suryasoni.copy_pasta`. Free on the JetBrains Marketplace
(first upload pending as of 2026-11-17).

README.md is the front door and the **source of the Marketplace / install-screen description**. SECURITY.md has the threat
model. This file records **layout, conventions, decisions and lessons**. Add an entry whenever work here decides or
learns something.

## Tooling

- **JDK 21** (installed on dev01 as `openjdk-21-jdk-headless`; `librsvg2-bin` for rendering icon previews). Gradle comes
  from the wrapper.
- Prefer the **Makefile**: `make help`. `make check` = ktlint + unit and platform tests + Kover (what CI runs).
- **Run `make format` before committing.** The pre-commit hook (`.githooks/`, enabled by `make hooks`) blocks commits
  that fail ktlint or the tests. Bypass only in an emergency with `git commit --no-verify`.
- Builds are tuned to fit about 3 GB (see `gradle.properties` and the 2026-11-17 log entry). Don't raise heaps or workers
  without checking dev01's session memory limit. The Plugin Verifier runs in CI, not on dev01.
- dev01 is headless: `make run` needs a display, so verify UI changes on a desktop.

## Layout

```
src/main/kotlin/info/suryasoni/copy_pasta/
  format/   IDE-free and unit-tested. Envelope (text wrapper), ZipCodec (JDK zip, path safety, size caps), Crypto.
  ide/      CopyCollector (VFS walk using VCS/IDE ignore rules), PasteWriter (plan + VFS write), Notifier.
  ui/       PastePreviewDialog, PassphraseDialog (Kotlin UI DSL v2).
  actions/  CopyAction / CopyEncryptedAction, PasteAction (Project view group + Edit menu entry).
  CopyPastaBundle.kt + resources/messages/CopyPastaBundle.properties   All UI text, including action names.
src/main/resources/META-INF/   plugin.xml, pluginIcon.svg, pluginIcon_dark.svg (40×40, original artwork).
src/test/kotlin/   format/* are JUnit 4 unit tests; ide/* are light platform tests (BasePlatformTestCase).
```

## Conventions / invariants

- **Store listing = README `<!-- Plugin description -->` block.** **Change notes = the CHANGELOG entry for
  `pluginVersion`**, or `[Unreleased]` if there is none. Edit those files, not plugin.xml. Vendor email still to be added.
- **The envelope is a compatibility contract** between installs: the BEGIN/END markers, `Format: 2; length: N`, a ZIP
  body, the exec-bit extra field `0x4350`, and the encrypted `CPv2` layout (magic | int32 PBKDF2 iterations | salt | iv |
  GCM). Readers must accept anything a previous 2.x
  wrote. A breaking change needs `Format: 3` (older plugins then say "update the plugin") and a CHANGELOG note.
- **Use the IDE instead of re-implementing it**: VFS for reads and writes, ChangeListManager/ProjectFileIndex/
  FileTypeRegistry for ignore rules, Local History for undo. No third-party libraries. In particular, don't use the
  commons-compress copy inside the IDE (it's an internal repackaging) or `com.intellij.util.io.Compressor`
  (`@ApiStatus.Obsolete`).
- **Use `fileSystem.getNioPath(file)`, never `toNioPath()`.** Non-disk file systems (including the test `temp://` FS)
  claim to be local but can't map to a Path.
- **No settings page.** Behaviour should come from the IDE's own configuration. Add a setting only when there's no
  IDE-native source for it.
- The package name `copy_pasta` (underscore) stays: it's the published plugin id's namespace.
- **Compatibility: IDE 2025.3 (build 253) and newer, no `until-build`.** Compile against the lowest supported platform
  (`platformVersion = 2025.3.6`, unified `intellijIdea(...)`). Kotlin `apiVersion = 2.2` matches 2025.3's bundled stdlib
  2.2.20 (the stdlib isn't bundled into the plugin). JVM toolchain 21: 2026.2+ run Java 25, which runs Java 21 bytecode.
  To raise the floor, bump `pluginSinceBuild`, `platformVersion` and `apiVersion` together.

## Release (tag-based)

1. `make release VERSION=X.Y.Z` on a clean tree. It sets `pluginVersion`, runs `patchChangelog` (moves `[Unreleased]`
   under the version with today's date), commits and creates an annotated tag `vX.Y.Z`.
2. `git push origin HEAD vX.Y.Z`. The tag triggers `.github/workflows/release.yml`, which:
   - fails if the tag ≠ `pluginVersion` or CHANGELOG has no entry for it;
   - runs `check buildPlugin verifyPlugin`;
   - runs `signPlugin` if `CERTIFICATE_CHAIN` and `PRIVATE_KEY` (+ `PRIVATE_KEY_PASSWORD`) are set;
   - runs `publishPlugin` if `PUBLISH_TOKEN` is set too (a `-beta.N` suffix publishes to that channel);
   - creates the GitHub release with the changelog entry as notes and the ZIP attached (pre-release for `-` versions).
3. The **first Marketplace upload is manual** (JetBrains requires it): upload the signed ZIP from the GitHub release on
   plugins.jetbrains.com, then add the `PUBLISH_TOKEN` secret so later tags publish automatically.

`build.yml` (push to main and PRs) runs build, `check`, Qodana and the Plugin Verifier. It no longer drafts releases.

## Decisions & lessons log (newest first — keep this current)

- **2026-11-17 — 2.0 redesign (owner-approved plan).** Critical review: the 1.x and 2.0-cleanup design was strong crypto
  around a shared default key, hand-rolled excludes, `java.io` writes that bypassed the VFS, a blind overwriting paste and
  an opaque Base64 blob. Decisions:
  - self-describing envelope that is robust to chat mangling and detects truncation;
  - JDK ZIP instead of the IDE-internal commons-compress;
  - VCS/IDE ignore rules instead of patterns;
  - preview dialog plus a VFS write behind a Local History label;
  - passphrase-only encryption, never stored;
  - Settings page removed;
  - IDE floor 2025.3 (this removes the deprecated Kotlin API 2.0 setting);
  - UI text in a message bundle;
  - searchable options off (no settings to index);
  - Codecov dropped;
  - tag-based releases replace the template's draft-release flow;
  - original 40×40 icon plus a dark variant.

  Markdown/AI-chat format deferred to v2.1. Lessons:
  1. `TempFileSystem` reports `isInLocalFileSystem` but `toNioPath()` throws, hence `getNioPath`.
  2. Exclusion is inherited by descendants, so honouring an explicitly selected excluded folder means skipping the
     inherited checks below it, not just at the root.
  3. 2.20.x of the IntelliJ Platform Gradle Plugin was under two weeks old, so we stayed on 2.19.0.
  4. The CI verifier flagged `ReadAction.compute/run` (deprecated from 2026.1) and `FileEditorManager.openFile(file,
     focus)` (from 2026.3). Background code now uses `ReadAction.nonBlocking(Callable).executeSynchronously()`, which
     exists in 2025.3 and is cancellable/restartable, so the block must be side-effect free. `computeBlocking` isn't in
     2025.3 and is EDT-only anyway. Files open with `OpenFileDescriptor.navigate`. Tests call the collector from a
     pooled thread via `PlatformTestUtil.waitForFuture`. **Check the verifier's deprecated-usages report on every PR.**
  5. An independent `/code-review high` before merging found 10 real issues. Fixed:
     - an unhandled write failure on the EDT;
     - file-vs-folder and case-only clashes inside one archive;
     - same-named roots merging, and nested selections copied twice;
     - `toInt` overflow on garbled headers;
     - decoding on the EDT;
     - `.git/hooks` planting and the executable bit hidden in the preview;
     - collapsed newlines losing the body;
     - PBKDF2 raised to 600k, with the count stored in the payload.
     Lesson: a review that isn't anchored on the author's view is worth it for anything that parses untrusted input.
- **2026-11-17 — Gradle memory tuning committed to the repo** (requested by the owner via the dev01 hub). The first
  builds thrashed dev01: the old Gradle daemon (512m), a separate Kotlin daemon (~690 MB, lingers for hours) and the
  IntelliJ test JVM (forked with `-Xmx2048m`) all ran in the session's memory cgroup. Pressure hit 89%, load reached 64
  on 4 cores, and `test` died with "Unable to connect to the child process". Fix, which also applies on CI:
  `gradle.properties` sets in-process Kotlin compilation, `-Xmx1536m` + SerialGC + capped metaspace/code cache, a
  15-minute daemon idle timeout and `workers.max=2`. `build.gradle.kts` caps test JVMs at 1g. Neither fallback (a 2g
  daemon, a 1536m test heap) was needed. dev01 also has these keys in `~/.gradle/gradle.properties`.
- **2026-11-16 — review + cleanup** (superseded by the redesign above). Removed the IntelliJ Platform Plugin Template
  leftovers and a dead startup activity, and fixed zip-slip, the always-failing unencrypted paste, a broken AES key
  derivation and never-matching excludes. Lesson: the old code had no tests, and three of these bugs would have shown up
  in a single round-trip test.
- **2026-11-16 — adopted the ~/projects conventions**: AGENTS.md, Makefile, pre-commit hook, ktlint, SECURITY.md,
  `.editorconfig`, weekly Dependabot against `main`.
- **2026-11-16 — toolchain.** Kotlin 2.1.20→2.4.21, Gradle 8.13→9.8.2, IntelliJ Platform Gradle Plugin 2.5.0→2.19.0,
  Qodana 2026.2. Lessons:
  1. Kotlin block comments nest, so a glob like `.git/` + `*` inside KDoc swallowed the rest of the file. The only
     symptom was "unresolved reference" at the call sites.
  2. Don't pin `languageVersion` along with `apiVersion`: syntax features like `catch (_: E)` need the newer language,
     and only the API level matters at runtime.
