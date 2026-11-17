# AGENTS.md — copy-pasta

An IntelliJ Platform plugin: **Copy** turns selected files and folders into one self-describing block of text (ZIP →
Base64, optionally passphrase-encrypted) to paste into any chat. **Paste** previews and recreates the files in another
IDE. Public repo `github.com/spsoni/copy-pasta`, plugin id `info.suryasoni.copy_pasta`. Free on the JetBrains Marketplace
(approved and listed as of 2026-11-17).

README.md is the front door and the **source of the Marketplace / install-screen description**. SECURITY.md has the threat
model. This file records **layout, conventions, decisions and lessons**. Add an entry whenever work here decides or
learns something.

## Tooling

- **JDK 21** (installed on dev01 as `openjdk-21-jdk-headless`; `librsvg2-bin` for rendering icon previews). Gradle comes
  from the wrapper.
- **macOS: `make setup-mac`** installs the `Brewfile` with `brew bundle --verbose` (openjdk@21, gh, actionlint,
  shellcheck), links the keg-only JDK into `~/Library/Java/JavaVirtualMachines` so `java_home`, Gradle and IntelliJ
  find it, and enables the hook. Add new host tools to the Brewfile only if they're worth their dependency tree (see the
  2026-11-17 setup-mac entry). Render icon previews with the built-in `sips`, not librsvg. Makefile recipes must work
  with both GNU and BSD tools: macOS `sed -i` needs a suffix, so use `sed -i.bak … && rm -f file.bak`.
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
  ide/      CopyCollector (VFS walk using VCS/IDE ignore rules), PasteWriter (plan + VFS write), Notifier,
            ClipboardWatcher (app service: clipboard → ClipboardStatus, published on a topic; IDE-free status logic).
  ui/       PastePreviewDialog, PassphraseDialog, PasteTextDialog (fallback: paste text / Open File…),
            ClipboardStatusWidget (status bar icon) (Kotlin UI DSL v2).
  actions/  CopyAction / CopyEncryptedAction (+ Save as File…), PasteAction / PasteFromEditMenuAction, PasteFlow
            (the decode → preview → write pipeline shared by the actions and the widget).
  CopyPastaBundle.kt + resources/messages/CopyPastaBundle.properties   All UI text, including action names.
docs/user-guide.md + docs/images/   End-user documentation (linked from README). Update it with any UI change.
src/main/resources/META-INF/   plugin.xml, pluginIcon.svg, pluginIcon_dark.svg (40×40, original artwork).
src/test/kotlin/   format/* are JUnit 4 unit tests; ide/* are light platform tests (BasePlatformTestCase).
```

## Conventions / invariants

- **Store listing = README `<!-- Plugin description -->` block.** **Change notes = the CHANGELOG entry for
  `pluginVersion`**, or `[Unreleased]` if there is none. Edit those files, not plugin.xml. Vendor email still to be added.
- **The envelope is a compatibility contract** between installs: the BEGIN/END markers, `Format: 2; length: N`, a ZIP
  body, the permissions extra field `0x4350`, and the encrypted `CPv2` layout (magic | int32 PBKDF2 iterations | salt |
  iv | GCM). The `0x4350` data layout:
  - byte 0 is the executable flag. 2.0 wrote only this byte, only for executable files, and reads only it.
  - bytes 1–2 are the nine permission bits, little-endian (2.1 and later).

  Extend it only by appending bytes. Readers must accept anything a previous 2.x wrote. A breaking change needs
  `Format: 3` (older plugins then say "update the plugin") and a CHANGELOG note.
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
   - creates the GitHub release with the changelog entry as notes and the ZIP attached (pre-release for `-` versions);
   - runs `publishPlugin` last, if `PUBLISH_TOKEN` is set too (a `-beta.N` suffix publishes to that channel). A rejected
     upload fails the job but leaves the GitHub release and ZIP in place.
3. The first Marketplace upload (2.0.1) was manual, as JetBrains requires, and was approved on 2026-11-17. From 2.1.0
   on, tags publish automatically. New versions still go through JetBrains moderation before they appear.

`build.yml` (push to main and PRs) runs build, `check`, Qodana and the Plugin Verifier. It no longer drafts releases.

## Decisions & lessons log (newest first — keep this current)

- **2026-11-17 — v2.1 paste UX (owner-approved).** The recipient forgetting to copy the message was the main
  stumbling block. Decisions:
  - **Paste stays enabled.** A disabled item gives no reason. Text copied in another app only reaches the IDE when its
    window is focused, so the enabled state would often be wrong. And update() runs on every menu open, too often to
    scan a clipboard of up to ~70 MB.
  - **Fallback dialog instead of an error.** It accepts pasted text or a file (which closes the long-message gap with
    **Save as File…**), and validates texts up to 2M characters inline.
  - **Status bar widget:** one pulse on arrival, dimmed after paste, fade out on other content.
  - **Never clear or change the user's clipboard on our own.** "Pasted" is a fingerprint (length + hash), not a stored
    copy. The widget's click menu has an explicit **Clear from Clipboard** (owner's request). It removes only
    Copy-Pasta entries, from the clipboard and from the IDE's history, via `CopyPasteManagerEx.removeContent`. That
    method is public and not `@Internal`, unlike `removeIf`.
  - **Full `rwx` permissions travel (owner's request).** The nine bits go in bytes 1–2 of the `0x4350` field, so 2.0
    readers still get the exec flag and the format stays 2. On paste, `FileMode.safe` = `(mode | 0600) & 0755`:
    - the owner can always edit, and a later paste can overwrite (read-only intent is deliberately lost);
    - never group- or world-writable;
    - setuid, setgid and sticky are never stored.

    `FileMode.applied(sender, current)` then caps group/other at what the file has right after creation (the
    recipient's umask) or already has (overwrite); group/other read allows execute, so 755 survives umask 022. The
    independent review caught that without this cap a 2.1 sender's 644 made every file world-readable for a umask-077
    recipient. Same content with a different resulting mode plans as OVERWRITE, not IDENTICAL. The preview labels
    anything but 644. Text from 2.0 (exec flag only) adds owner-execute to the current bits, as 2.0
    did. A failure to set permissions is logged, not fatal: the content is already written. The fixture's temp FS has
    no NIO path, so `PasteWriterTest` writes to a real temp directory through `LocalFileSystem` to check the applied
    modes.
  - **Saved files are named `copy-pasta-yyyy-MM-dd-HHmmss.txt` in local time** (`SaveAsFile`). ISO order sorts
    chronologically. The user's regional format isn't used because it often contains `/` or `:`, which Windows or
    macOS reject in file names.
  - **The widget opens a menu instead of pasting on click:** Paste into Project Root… / Paste into Folder… /
    Save as File… / Clear.
    The folder chooser fixes the "project root only" limitation, and a stray click can't paste the sender's own copy
    back.
  - **No polling.** ClipboardWatcher refreshes on IDE clipboard changes and on window activation. Menu text and the
    widget read its cached status.
  - **The widget can't follow the Project view selection.** `AbstractProjectViewPane.getSelectedDirectories` and
    friends are deprecated in 2025.3, hence the Project Root / Folder… choice in its menu.
  - **Icon:** a coloured 16 px icon from the plugin artwork (`resources/icons/clipboardReady*.svg`), shown grey once
    the text is pasted. The first version used the grey `AllIcons.Actions.MenuPaste`, and its pulse to a lighter grey
    was invisible (owner's test).

  The sender's own copy is labelled `Copied` (steady icon, no pulse). dev01 is headless, so the widget and dialog look
  need checking on a desktop.

  An independent review found 7 issues, all fixed:
  1. **ReDoS:** `-{3,}\s*BEGIN` was quadratic on long dash runs (64k dashes took 17 s, on the EDT). This was latent in
     2.0's Paste and made automatic by the watcher. It now uses `(?<!-)-{3,}+\s*+…`, with a timed test.
  2. DialogWrapper re-runs `doValidate` every 300 ms after a failed OK. Validation is now cached per edit, and the
     full decode runs once in `doOKAction`.
  3. Opened files never go into the JTextArea. They're read via NIO under a progress dialog, which avoids VFS
     content caching.
  4. The sender's own copy showed as "ready to paste".
  5. The Save as File notification held up to ~70 MB. It now uses a SoftReference plus a clipboard fingerprint fallback.
  6. Clicking the widget did nothing silently when there was no project root.
  7. A truncated clipboard was a dead end. The error notification now opens the dialog.

  Lesson (again): get an independent review for anything that runs on untrusted input automatically.

  Lesson: after `FileEntry` gained a constructor parameter, the tests failed with `NoSuchMethodError` even after
  `./gradlew clean`. The local Gradle build cache had restored test classes compiled against the old constructor. Fix:
  `./gradlew compileTestKotlin --rerun --no-build-cache`. A `NoSuchMethodError` in tests means stale classes, not a
  code bug.

  Lesson: the verifier flagged a deprecated `StatusBarWidget.getPresentation(PlatformType)` override that we never
  wrote. Kotlin's default `jvmDefault = enable` generated a bridge for it. `build.gradle.kts` now sets
  `jvmDefault = NO_COMPATIBILITY`, so no bridges are generated, and the warning is gone.

- **2026-11-17 — faster CI on GitHub-hosted runners; llm01 rejected.** The llm01 self-hosted runner belongs to the
  `spsoni-apps` org, and this is a public personal repo. A self-hosted runner on a public repo lets fork PRs run code
  on the LAN, and personal repos can't restrict runners to specific workflows. Timing showed where the time went:
  - the `needs: build` gate added 2 min;
  - the verifier downloaded 4 IDEs (5 min), then spent 1m40s saving ~3 GB of Gradle cache;
  - free-disk-space took 2 min;
  - the `~/.pluginVerifier/ides` cache was empty (IntelliJ Platform Gradle Plugin 2.x resolves IDEs as Gradle
    dependencies).

  Changes:
  - jobs run in parallel;
  - pull requests verify with `-PverifyIdes=quick` (`current()` + `latest()`), main and releases with `recommended()`;
  - the verifier job's Gradle cache is read-only;
  - disk cleanup removes only Android and .NET;
  - the dead cache step is gone.

- **2026-11-17 — user docs live in `docs/`, not the GitHub wiki.** A wiki is a separate repo: it isn't versioned with
  the code, isn't reviewed in PRs, and drifts from the UI. The guide's screenshots are the owner's (macOS, light
  theme). Marketplace gallery screenshots are uploaded separately on the plugin page and should be larger captures; the
  guide's crops (about 430–660 px wide) are too small for the gallery.

- **2026-11-17 — relicensed Apache 2.0 → MIT** (owner's request; the owner is the sole author of every commit, so no
  contributor sign-off was needed). The store description gained a "Why you'll want it" section with use cases.
  Marketplace publishing is already automated in `release.yml`: it needs the first manual upload plus the
  `PUBLISH_TOKEN` secret. All four secrets are now set. The owner chose to upload 2.0.1 by hand as the first Marketplace
  version. `release.yml` now creates the GitHub release before publishing, so a rejected publish (expected for 2.0.1)
  still leaves the signed ZIP to upload.

- **2026-11-17 — `make setup-mac` looked hung.** It was compiling from source. Homebrew installed outside its default
  prefix (here `~/homebrew`) can't pour bottles that hard-code `/opt/homebrew/Cellar`. openjdk@21 needs glib, gettext,
  fontconfig and libx11 (through harfbuzz and cairo), and building those needs python and openssl from source too.
  librsvg added gdk-pixbuf, gobject-introspection and libthai. `brew bundle` buffers install output until a batch
  finishes, so nothing showed for many minutes. Fixes:
  - `--verbose` streams progress;
  - the recipe warns when the prefix isn't the default;
  - librsvg was dropped (`sips` renders the SVG icons);
  - gradle was dropped: it pulled in a second JDK (openjdk 25) only for wrapper upgrades, which
    `./gradlew wrapper --gradle-version X` handles.

  The owner is moving Homebrew to `/opt/homebrew` instead of switching to a JDK cask (temurin@21 is a `.pkg` and needs
  sudo).

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
