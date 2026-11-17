# AGENTS.md — copy-pasta

An IntelliJ Platform plugin that copies files and folders to the clipboard as Base64
text (tar.gz, optionally AES-GCM encrypted) and pastes them back into another IDE.
Public repo: `github.com/spsoni/copy-pasta`. Plugin id `info.suryasoni.copy_pasta`.

README.md covers usage and is also the **source of the Marketplace / install-screen
description**. This file records **layout, conventions, decisions and lessons**. Keep it
current: add an entry whenever work here makes a decision or learns something.

## Tooling

- **JDK 21** (installed on dev01 as `openjdk-21-jdk-headless`). Gradle comes from the wrapper.
- Prefer the **Makefile**: `make help`. `make check` = ktlint + unit tests + Kover (same as CI).
- **Run `make format` before committing.** The pre-commit hook (`.githooks/`, enabled by
  `make hooks`) blocks commits that fail ktlint or the unit tests. Bypass only in an
  emergency with `git commit --no-verify`.
- Builds are tuned to fit about 3 GB (see `gradle.properties` and the 2026-11-17 log entry). Don't
  raise heaps or workers without checking dev01's session memory limit.
- dev01 is headless, so `make run` (sandbox IDE) needs a display. Verify UI changes on a desktop.

## Layout

```
src/main/kotlin/info/suryasoni/copy_pasta/
  actions/CopyPastaUtils.kt          Pure logic: archive, extract, exclude, encrypt, Base64. No IDE APIs, so it is unit-testable.
  actions/CopyPasta*Action.kt        Project-view actions. Read the clipboard on the EDT, do the work in Task.Backgroundable.
  actions/CopyPastaNotifier.kt       Balloon notifications (group "Copy-Pasta", registered in plugin.xml).
  settings/CopyPastaSettingsState.kt Application-level persisted settings (CopyPastaSettings.xml).
  settings/CopyPastaSettingsConfigurable.kt  Settings | Tools | Copy-Pasta (Kotlin UI DSL v2).
src/main/resources/META-INF/plugin.xml       Extensions + actions. Name/version/description/change notes are injected by Gradle.
src/test/kotlin/...                          JUnit 4 tests for CopyPastaUtils.
```

## Conventions / invariants

- **Store listing = README `<!-- Plugin description -->` block** (converted to HTML by
  `build.gradle.kts`). **Change notes = the CHANGELOG entry for `pluginVersion`**, or
  `[Unreleased]` if that version isn't there yet. Edit those files, not plugin.xml.
- **Payload format is a compatibility contract** between installs. Plain = gzip tar.
  Encrypted = `CPv2` | salt16 | iv12 | GCM ciphertext. Any change needs a new magic
  (`CPv3`…), a major version bump and a CHANGELOG note.
- Keep IDE APIs out of `CopyPastaUtils`. Logic that can be tested without a running IDE belongs there.
- Never extract outside the target directory. Keep the traversal test green.
- The package name `copy_pasta` (underscore) is kept on purpose. It is the published
  plugin id's namespace, so renaming it would break persisted settings.
- **Compatibility: IDE 2024.3 (build 243) and newer, no `until-build`.** That is JetBrains'
  guidance for 243+, and `verifyPluginProjectConfiguration` flags an upper bound. The build
  compiles against the **lowest** supported platform (`platformVersion = 2024.3.7` in
  `gradle.properties`), so a newer-only API fails at compile time. `make verify` runs the
  Plugin Verifier against newer IDEs. To raise the floor, bump `pluginSinceBuild`,
  `platformVersion` and the Kotlin `apiVersion` together.
- **Compiler: latest stable Kotlin (2.4.x), `apiVersion = 2.0`, JVM toolchain 21.** The IDE
  provides the Kotlin stdlib at runtime (it isn't bundled), and 2024.3 ships stdlib 2.0.21,
  so the compiler must not emit calls to newer stdlib APIs. Kotlin 2.4 marks API 2.0 as
  deprecated, which is why the warning is suppressed. When Kotlin drops API 2.0, the floor
  must move to an IDE whose bundled stdlib is supported. The language version stays at the
  compiler default (syntax needs no runtime support). Gradle 9.8.2 (wrapper, checksum pinned)
  and IntelliJ Platform Gradle Plugin 2.19.0.

## Release

1. Move the `[Unreleased]` notes under a version heading (or let `publishPlugin` run
   `patchChangelog`), set `pluginVersion` in `gradle.properties`.
2. Push to `main`: CI (`.github/workflows/build.yml`) builds, tests, runs Qodana and
   the plugin verifier, and creates a draft GitHub release.
3. Publishing the draft triggers `release.yml`, which signs and uploads to the
   Marketplace. It needs the `PUBLISH_TOKEN`, `CERTIFICATE_CHAIN`, `PRIVATE_KEY` and
   `PRIVATE_KEY_PASSWORD` repo secrets.

## Decisions & lessons log (newest first — keep this current)

- **2026-11-17 — Gradle memory tuning committed to the repo** (requested by the owner via
  the dev01 hub). The first builds thrashed dev01. The old Gradle daemon (512m), a separate
  Kotlin daemon (~690 MB, lingers for hours) and the IntelliJ test JVM (forked with
  `-Xmx2048m` by the test framework) all ran inside the session's memory cgroup. Pressure hit
  89%, load reached 64 on 4 cores, and the `test` task died with "Unable to connect to the
  child process". Fix, which also applies on CI and other machines: `gradle.properties` sets
  `kotlin.compiler.execution.strategy=in-process`, `-Xmx1536m` + SerialGC + capped
  metaspace/code cache, a 15-minute daemon idle timeout and `workers.max=2`.
  `build.gradle.kts` caps test JVMs at `maxHeapSize = "1g"`. With those settings
  `check buildPlugin` passes (7/7 tests) after `./gradlew --stop`, so neither the 2g daemon
  nor the 1536m test-heap fallback was needed. dev01 also has the same keys in
  `~/.gradle/gradle.properties`, which take precedence there.
- **2026-11-16 — review + cleanup, v2.0.0 (unreleased).** Removed the IntelliJ
  Platform Plugin Template leftovers (template-cleanup/verify workflows, UI-test
  workflow + `runIdeForUiTests`, README images) and the unregistered, dead
  `CopyPastaProjectActivity`. Fixed: zip-slip on paste, unencrypted paste always
  failing (the "is encrypted" heuristic flagged gzip's 0x1F byte), a broken key
  derivation (SHA-256 bytes → UTF-8 String gave invalid AES key lengths), exclude
  patterns that never matched (regex vs absolute path), clipboard cleared on failure,
  and the unregistered notification group. Crypto moved from AES/ECB to
  AES-256-GCM + PBKDF2. That breaks 1.x payloads, hence 2.0.0. Settings became
  application-level with an encrypt toggle. Lesson: the old code had no tests, and
  three of these bugs would have shown up in a single round-trip test.
- **2026-11-16 — adopted the ~/projects conventions**: AGENTS.md, Makefile, pre-commit
  hook, ktlint (`make format`), SECURITY.md, `.editorconfig`, and Dependabot set to
  weekly against `main` (it pointed at a nonexistent `next` branch).
- **2026-11-16 — toolchain + IDE range.** Kotlin 2.1.20→2.4.21, Gradle 8.13→9.8.2,
  IntelliJ Platform Gradle Plugin 2.5.0→2.19.0 (2.20.x was under two weeks old), Qodana
  2026.2. Supported IDEs went from 2023.1–2025.2 to 2024.3+, open-ended. Lessons:
  (1) Kotlin block comments nest, so a glob like `.git/` + `*` written inside KDoc
  swallowed the rest of the file. The only symptom was "unresolved reference" at the call
  sites. (2) `languageVersion` must not be pinned with `apiVersion`: `catch (_: E)` needs
  the newer language, and only the API level matters at runtime.
