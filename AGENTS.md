# AGENTS.md — copy-pasta

An IntelliJ Platform plugin: **Copy** turns selected files and folders into one self-describing block of text (ZIP →
Base64, optionally passphrase-encrypted) to paste into any chat. **Paste** previews and recreates the files in another
IDE. Public repo `github.com/spsoni/copy-pasta`, plugin id `info.suryasoni.copy_pasta`. Free on the JetBrains Marketplace
(approved and listed as of 2026-11-17).

The repository is a **monorepo** (since 2026-11-17): the JetBrains plugin lives in `intellij/`; a VS Code extension and
macOS/Windows right-click menus (a Rust CLI) are planned beside it. They share no code, only the clipboard format. The
root README.md is the umbrella front door; **`intellij/README.md` is the source of the Marketplace / install-screen
description**. SECURITY.md has the threat model. Feature arcs follow Compass (`docs/compass/`). This file records **layout, conventions, decisions and lessons**. Add an entry whenever work here decides or
learns something.

## Tooling

- **JDK 21** (installed on dev01 as `openjdk-21-jdk-headless`; `librsvg2-bin` for rendering icon previews). Gradle comes
  from the wrapper.
- **macOS: `make setup-mac`** installs the `Brewfile` with `brew bundle --verbose` (openjdk@21, gh, actionlint,
  shellcheck), links the keg-only JDK into `~/Library/Java/JavaVirtualMachines` so `java_home`, Gradle and IntelliJ
  find it, and enables the hook. Add new host tools to the Brewfile only if they're worth their dependency tree (see the
  2026-11-17 setup-mac entry). Render icon previews with the built-in `sips`, not librsvg. Makefile recipes must work
  with both GNU and BSD tools: macOS `sed -i` needs a suffix, so use `sed -i.bak … && rm -f file.bak`.
- Prefer the **Makefile** at the repository root: `make help`. Its targets run Gradle in `intellij/`
  (`intellij/gradlew -p intellij`). `make check` = ktlint + unit and platform tests + Kover (what CI runs). To run
  Gradle directly, `cd intellij` first.
- **Run `make format` before committing.** The pre-commit hook (`.githooks/`, enabled by `make hooks`) blocks commits
  that fail ktlint or the tests. Bypass only in an emergency with `git commit --no-verify`.
- Builds are tuned to fit about 3 GB (see `gradle.properties` and the 2026-11-17 log entry). Don't raise heaps or workers
  without checking dev01's session memory limit. The Plugin Verifier runs in CI, not on dev01.
- dev01 is headless: `make run` needs a display, so verify UI changes on a desktop.

## Layout

```
README.md, LICENSE, SECURITY.md, AGENTS.md, Makefile, Brewfile, .githooks/, .github/   repository-wide
docs/compass/   Compass status board and stories (plans with a scope list and acceptance evidence).
spec/           FORMAT.md (the clipboard format), FEATURES.md (parity table), vectors/ (conformance cases).
vscode/         the VS Code extension (TypeScript): src/format/ is the format core (envelope, crypto, zip, crc32,
                fileMode, pathRules, plan), pure Node and tested on dev01 with `node --test`; test/ holds unit,
                conformance (spec vectors) and plan tests plus interop.ts. `make vscode-check`, `make interop`.
intellij/       the JetBrains plugin: build.gradle.kts, gradle/, gradlew, qodana.yml, CHANGELOG.md, README.md, and:
src/main/kotlin/info/suryasoni/copy_pasta/        (paths below are relative to intellij/)
  format/   IDE-free and unit-tested. Envelope (text wrapper), ZipCodec (JDK zip, path safety, size caps), Crypto.
  ide/      CopyCollector (VFS walk using VCS/IDE ignore rules), PasteWriter (plan + VFS write), Notifier,
            ClipboardWatcher (app service: clipboard → ClipboardStatus, published on a topic; IDE-free status logic).
  ui/       PastePreviewDialog, PassphraseDialog, PasteTextDialog (fallback: paste text / Open File…),
            ClipboardStatusWidget (status bar icon) (Kotlin UI DSL v2).
  actions/  CopyAction / CopyEncryptedAction (+ Save as File…), PasteAction / PasteFromEditMenuAction, PasteFlow
            (the decode → preview → write pipeline shared by the actions and the widget).
  CopyPastaBundle.kt + resources/messages/CopyPastaBundle.properties   All UI text, including action names.
docs/user-guide.md + docs/images/   End-user documentation (linked from intellij/README.md). Update it with any UI change.
src/main/resources/META-INF/   plugin.xml, pluginIcon.svg, pluginIcon_dark.svg (40×40, original artwork).
src/test/kotlin/   format/* are JUnit 4 unit tests; ide/* are light platform tests (BasePlatformTestCase).
```

## Conventions / invariants

- **Store listing = `intellij/README.md` `<!-- Plugin description -->` block.** **Change notes = the
  `intellij/CHANGELOG.md` entry for `pluginVersion`**, or `[Unreleased]` if there is none. Edit those files, not plugin.xml. Vendor email still to be added.
- **`spec/FORMAT.md` and `spec/vectors/` are the contract** between all products. Every implementation runs every
  vector in its CI (`ConformanceTest`, `ConformancePlanTest` here). A format change means a spec change plus new
  vectors (`VectorGenerator.kt`, `make vectors`) in the same PR. Update `spec/FEATURES.md` with any feature change.
- **VS Code extension: zero runtime dependencies** (owner's decision): no third-party code may parse untrusted
  clipboard text, so the ZIP reader/writer is our own on Node's `zlib` and `crypto`. CI fails if `dependencies` is
  non-empty. Dev dependencies are exact-pinned, at least two weeks old, installed with `ignore-scripts` (`.npmrc`).
  Target VS Code `^1.90.0`, which runs Node 20: compile against `@types/node` 20 so newer APIs (`zlib.crc32`) can't
  slip in. Tags: `vscode-vX.Y.Z`. `interop.yml` proves each product decodes the other's plain and encrypted text.
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

**VS Code extension:** `make vscode-release VERSION=X.Y.Z` (bumps `vscode/package.json`, dates
`vscode/CHANGELOG.md`, commits, tags `vscode-vX.Y.Z`), then `git push origin HEAD vscode-vX.Y.Z`.
`release-vscode.yml` checks tag/version/changelog, runs the tests and the integration tests inside VS Code, packages
the `.vsix`, creates a GitHub release (not marked latest, so the plugin's release stays "latest"), and publishes to
the VS Code Marketplace with **trusted publishing** (OIDC: no stored token) and to Open VSX (`OVSX_PAT`) when that
secret exists. The Marketplace policy for publisher `spsoni` names repository `copy-pasta`, workflow
`release-vscode.yml` and environment `vscode-marketplace`; renaming the workflow file or the environment breaks
publishing until the policy is updated. The environment accepts only `vscode-v*` tags, and only its job (which runs no
project code, just the pinned vsce on the built `.vsix`) gets `id-token: write`. Unlike JetBrains, the first upload can
be automated. `vscode.yml` runs integration tests on VS Code 1.90.0 (the floor) and stable under
`xvfb-run`; dev01 has no display, so they run only in CI.

**JetBrains plugin:**

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

`build.yml` (push to main and PRs) runs build, `check`, Qodana and the Plugin Verifier, only when `intellij/`, `spec/`
or the workflow changes. It no longer drafts releases. JetBrains plugin tags stay `vX.Y.Z`; other products will use
`vscode-v…` and `desktop-v…`, which release.yml's tag pattern doesn't match.

## Decisions & lessons log (newest first — keep this current)

- **2026-11-18 — VS Code extension (Compass stories 004–005).** Owner decisions: zero runtime dependencies (own ZIP
  code), publisher `spsoni`, tags `vscode-vX.Y.Z`. Each layer got an independent review that wasn't anchored on the
  author's view, and each found real bugs. Lessons:
  - **A second implementation is a security review of the first:** writing the TypeScript reader exposed the Kotlin
    reader's unbounded directory-entry inflation. A differential fuzz against a Java port then found reader
    divergences (now vectors) and a record flood, now a spec cap of 20,000 records in both.
  - **VS Code's undo of `WorkspaceEdit.createFile({ overwrite: true })` deletes the file** without restoring the
    old content. Delete then create in the same edit: the delete snapshots the original.
  - **VS Code notifications render `[text](command:…)` as clickable command links:** never put untrusted text
    (archive names, decoder messages) in `show*Message`.
  - **`node --test` globs need Node 21+:** CI's Node 20 found nothing to run. Let the shell expand the pattern.
  - **Packaging tools carry their own advisories** (vsce's globbing libraries): they live in `vscode/tools/` with
    their own lockfile, out of the extension's dependency tree and audit.
  - **Marketplace extension names and display names are unique across all publishers.** `copy-pasta` and
    "Copy-Pasta" were taken (`Equilib.copy-pasta`), and each was only reported at publish time: 0.1.0 and 0.1.1 are
    GitHub-only releases. The extension is `spsoni.copy-pasta-files`, "Copy-Pasta Files". Trusted publishing
    (OIDC) worked first time. Search the Marketplace (`vsce search`) before choosing names for a new product.
  - **Building an invisible character from a `\u` escape** can turn into the literal character in a generated
    source file. Write `String.fromCodePoint(0x…)` / `Char(0x…)` instead.

- **2026-11-17 — 2.1.1 paste hardening (Compass story 003, owner: "security is most important").** The independent
  review of the new spec against the code found gaps that had shipped in 2.0 and 2.1.0. Fixed, each with a test that
  failed first:
  - **Paste followed symlinks already in the target:** `link/x` with `link → /etc` wrote `/etc/x`, and a symlinked
    file was overwritten through its link. `PasteWriter.linkOnPath` checks every existing component on disk with
    `NOFOLLOW_LINKS` (broken links and Windows junctions count), in `plan` and again in `write`. The VFS alone isn't
    enough: it can miss broken links.
  - **`.git` lookalikes** (`.git.`, `.git `, `GIT~1`, HFS+-ignored code points) and **Windows-unsafe names** (`:`,
    reserved device names, trailing dot/space; Windows targets only, so `12:30.log` still works elsewhere) are in
    `format/PathRules`.
  - Control characters in names → INVALID_ARCHIVE; `EOFException`/`IllegalArgumentException` from `ZipInputStream`
    → INVALID_ARCHIVE; CPv2 under 52 bytes rejected before PBKDF2; markers ASCII-case-insensitive (Kotlin's
    `IGNORE_CASE` adds UNICODE_CASE, so `PAſTA` matched); non-ASCII in a complete body → CORRUPTED; unpaired
    surrogates refused in passphrases.

  Lesson: writing the spec for other languages was itself a security review. Describing behaviour precisely enough
  for a TypeScript or Rust implementer exposed what the Kotlin did implicitly. Lesson: Kotlin's
  `RegexOption.IGNORE_CASE` is Unicode-aware; use `Pattern.CASE_INSENSITIVE` for ASCII-only matching.

- **2026-11-17 — monorepo (owner-approved plan, Compass stories 001–002).** After 2.1.0 shipped (the first automatic
  Marketplace publish worked), the owner wants a VS Code extension and macOS/Windows right-click menus that all
  interoperate. Decisions:
  - **One repository, no shared code.** IntelliJ must be Kotlin, VS Code TypeScript, the desktop menus native; the
    format is a few hundred lines per language. Interop comes from a language-neutral spec plus golden test vectors
    that every implementation's CI must pass (story 002), not from a shared library.
  - **Desktop = one Rust CLI with thin OS hooks** (owner's choice): a single binary for macOS and Windows, scriptable,
    easy to test against the vectors.
  - **Tags:** IntelliJ keeps `vX.Y.Z` (owner's choice), so the release flow is unchanged; new products get prefixes.
  - **Layout:** the plugin moved into `intellij/` with `git mv` (history follows). The root README became an umbrella;
    the store description moved with the plugin's README, so `build.gradle.kts` didn't change. The built ZIP was
    compared with 2.1.0's: same files, same jar entries, identical `plugin.xml`.
  - CI is path-filtered. That's safe only because `main` has no branch protection; a required check that a path filter
    skips would block merging.

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
