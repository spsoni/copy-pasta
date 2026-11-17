# Story 001 — monorepo-move

> Written in plan mode as the plan; owner approval of the plan = approval of this story.

**Status:** verifying (local evidence done; CI on the PR pending)
**Depends on:** none

## Goal
Move the JetBrains plugin into `intellij/` with no behaviour change, so `spec/`, `vscode/` and `desktop/` can sit beside
it. The plugin ZIP, Marketplace description and change notes must be identical to 2.1.0's. IntelliJ keeps `vX.Y.Z`
tags; other products will use `vscode-v…` / `desktop-v…`.

## Scope — touch ONLY these
- `git mv` into `intellij/`: `src/`, `build.gradle.kts`, `settings.gradle.kts`, `gradle.properties`, `gradle/`,
  `gradlew`, `gradlew.bat`, `qodana.yml`, `CHANGELOG.md`, `README.md` → `intellij/README.md`, `docs/user-guide.md` +
  `docs/images/` → `intellij/docs/`. Fix relative links outside the plugin-description block.
- `README.md` (root, new): umbrella front door with a products table.
- `Makefile`: Gradle runs as `intellij/gradlew -p intellij`; `release` edits `intellij/gradle.properties`.
- `.githooks/pre-commit`: triggers on `intellij/` sources and `spec/`, runs Gradle in `intellij/`.
- `.github/workflows/build.yml`: path filter, `working-directory: intellij`, artifact paths, Qodana `--project-dir`.
- `.github/workflows/release.yml`: `working-directory: intellij`; tag pattern unchanged.
- `.github/dependabot.yml`: gradle directory `/intellij`.
- `AGENTS.md`, `CLAUDE.md`, `docs/compass/**`.

## Acceptance check
- [x] `make check` green from the repo root — 54 tests, 0 failures, 0 errors.
- [x] `make build` → ZIP compared with a 2.1.0 build made from the release commit: same file list, same jar entries
  and sizes, `META-INF/plugin.xml` (description + change notes) byte-identical.
- [x] History follows the move: `git log --follow -- intellij/src/main/kotlin/info/suryasoni/copy_pasta/format/ZipCodec.kt`
  (checked after commit).
- [x] Release workflow's tag/version/changelog step run locally from `intellij/`: `version=2.1.0`, notes extracted.
  `actionlint` and `shellcheck .githooks/pre-commit` clean.
- [ ] PR CI green (Build, Test, Inspect code, Verify plugin) and the verifier still "Compatible" with no deprecated usages.
  - Build, Test and Verify plugin passed on 6ac123b.
  - Inspect code (Qodana) failed: in PR mode it also analyses the base commit (`d03671a`, before the move), which has no
    `intellij/`. That's expected only for the PR that crosses the move. Confirm Qodana passes on `main` after merging.

## Notes
- `main` has no branch protection, so path-filtered checks can't block merges. Revisit if protection is added.
- Marketplace page: https://plugins.jetbrains.com/plugin/36213-copy-pasta (the xmlId URL 404s).
