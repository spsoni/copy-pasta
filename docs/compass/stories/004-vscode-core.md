# Story 004 — vscode-core

> Written in plan mode as the plan; owner approval of the plan = approval of this story.

**Status:** done (PR #12)
**Depends on:** 002, 003

## Goal
A TypeScript implementation of `spec/FORMAT.md` v2 (envelope, Base64 rules, CPv2 crypto, streaming ZIP reader and
writer, permissions, paste plan with the symlink / VCS / Windows rules) that passes every vector, and a CI job that
proves JetBrains ⇄ VS Code interop both ways. Pure Node, no VS Code API, so it's testable on headless dev01. Owner
decisions: own ZIP code with **zero runtime dependencies** (no third-party code parses untrusted clipboard text);
publisher `spsoni`.

## Scope — touch ONLY these
- `vscode/package.json` (no `dependencies`; exact-pinned devDependencies `typescript` 6.0.3, `@types/node`
  20.19.43), `vscode/package-lock.json`, `vscode/tsconfig.json`, `vscode/.gitignore`, `vscode/.npmrc`.
- `vscode/src/format/`: `errors.ts`, `envelope.ts`, `crypto.ts`, `zip.ts`, `crc32.ts`, `fileMode.ts`,
  `pathRules.ts`, `plan.ts`, `index.ts`.
- `vscode/test/`: unit tests, `conformance.test.ts`, `plan.test.ts`, `interop.ts`.
- `spec/interop/fixture.json`; `intellij/src/test/kotlin/.../format/InteropTest.kt`; `intellij/build.gradle.kts`.
- `intellij/src/main/kotlin/.../format/ZipCodec.kt` + `VectorGenerator.kt` (added during the build): directory
  entries' data now counts against the 200 MB limit. The Kotlin reader inflated it without a limit (CPU only, nothing
  written), which the TypeScript reader exposed. New vector `zip-directory-entry-bomb`. After the review: the
  20,000-record cap (`ZipCodec.Limits.maxRecords`, `HardeningTest`), 4 more vectors, `intellij/CHANGELOG.md`
  `[Unreleased]`, and `compileTestKotlin` no longer cacheable (`build.gradle.kts`).
- `.github/workflows/vscode.yml`, `.github/workflows/interop.yml`, `.github/dependabot.yml`.
- `Makefile`, `.githooks/pre-commit`.
- `spec/FORMAT.md` (only for ambiguities the TypeScript work exposes, with a vector), `spec/FEATURES.md`,
  `AGENTS.md`, `docs/compass/**`.

## Acceptance check
- [x] `make vscode-check` green on dev01: 110 tests, 0 failures. That's all 53 vectors, the 13-row modes table, the
  `blocked`/`blockedOnWindows` expectations (Windows and non-Windows targets), symlink/FIFO tests and round trips.
  `make check` (Kotlin): 122 tests, 0 failures.
- [x] Mutation sanity: dropping `..` from `safePath` and the symlink check from `linkOnPath` gave 4 failing suites
  (vectors, zip, real-folder plan); restored, 110 pass. Invisible characters in sources: none (code points only).
- [ ] `interop.yml` green: VS Code text pastes in Kotlin and Kotlin text pastes in TypeScript, plain + encrypted.
  Locally, `make interop` reports "interop OK" both ways, and a deliberately damaged VS Code file makes the Kotlin
  decode step fail (exit 1). CI pending.
- [x] `npm ls --omit=dev` empty; `npm audit`: 0 vulnerabilities (3 dev packages).
- [x] Independent security review of `zip.ts`, `envelope.ts`, `plan.ts` against the spec. It ran 50,000 random
  envelopes and 6,000 mutated ZIPs differentially against a Java port: no path escape, link following, VCS bypass or
  uncategorised exception. Its findings are fixed:
  1. Divergences from `ZipInputStream`, now vectors that both products pass: an empty Stored entry's CRC isn't
     checked (`zip-stored-empty-bad-crc`); an unsigned descriptor needs 16 bytes left (`zip-descriptor-unsigned`,
     `-at-end`); a Stored entry's compressed size isn't a Zip64 marker (`zip-stored-compressed-size-marker`).
  2. A one-line 50M-character body took 5 s and 1.85 GB in TypeScript: whitespace is now removed with one regex
     (10M characters decode in well under 3 s, tested), checked against `isBodyWhitespace` for every BMP character.
  3. A flood of directory entries kept both readers busy: a new spec cap of 20,000 local records, in both readers
     with unit tests. Decoding moves to a worker thread in 005.
  4. Unreadable files in the plan count as BLOCKED instead of throwing. DOS dates are clamped.

  Not changed: the platform guess of case sensitivity (Linux casefold/vfat targets). The VCS check always folds,
  so there's no escape; documented as a limitation.
- [x] The build cache served stale Kotlin test classes again (`NoSuchMethodError` after `Limits` gained a
  parameter): `compileTestKotlin` is no longer cacheable. `make check`: 127 tests, 0 failures;
  `make vscode-check`: 120 tests, 0 failures (57 vectors); `make interop` OK.

## Notes
- VS Code 1.90 runs Node 20: no `zlib.crc32`, so `crc32.ts` has its own table. `@types/node` 20 keeps the compiler
  honest about that.
- Inflate with `zlib.inflateRawSync(data, { info: true, maxOutputLength })`: `engine.bytesWritten` is the number of
  compressed bytes consumed, which a streaming reader needs to find the data descriptor after a bit-3 entry.
