# Story 003 — paste-hardening (JetBrains 2.1.1)

> Written in plan mode as the plan; owner approval of the plan = approval of this story.

**Status:** in-progress
**Depends on:** 002

## Goal
Close the security gaps the independent spec review found in the shipped plugin (2.0 and 2.1.0), change the spec
and vectors to match, and release 2.1.1. The worst one: a paste follows symlinks that already exist in the
recipient's folder, so `link/x` with `link → /etc` writes `/etc/x`, and overwriting a symlinked file writes through
it. The copy side already skips symlinks; this is about the recipient's tree.

## Scope — touch ONLY these
- `intellij/.../ide/PasteWriter.kt`
  - **Symlinks:** BLOCKED if any existing component of the entry's path inside the target, or the file itself, is a
    symlink (`VFileProperty.SYMLINK`, including broken links). `write()` re-checks just before each entry and refuses
    rather than follows a link.
  - **VCS lookalikes:** compare segments after removing HFS+-ignorable code points (U+200B–200F, U+202A–202E,
    U+2060–206F, U+FEFF), stripping trailing `.`/space, lower-casing; also block `git~1`.
  - **Windows targets** (`windows` parameter on `plan`, default `SystemInfo.isWindows`): BLOCK segments containing
    `: < > " | ? *`, reserved device names (CON, PRN, AUX, NUL, COM1–9, LPT1–9, with or without extension), and
    trailing `.`/space.
- `intellij/.../format/ZipCodec.kt`: NUL and U+0001–001F in names → INVALID_ARCHIVE; `IOException` /
  `IllegalArgumentException` while reading → INVALID_ARCHIVE.
- `intellij/.../format/Crypto.kt`: payload under 52 bytes → INVALID_ARCHIVE before PBKDF2.
- `intellij/.../format/Envelope.kt`: ASCII-only case-insensitive markers; with END present, a non-ASCII character in
  the body → CORRUPTED before the length check.
- `intellij/.../ui/PassphraseDialog.kt` + bundle: reject passphrases with unpaired surrogates.
- Tests: `PasteWriterTest`, `ZipCodecTest`, `CryptoTest`, `EnvelopeTest`, `ConformancePlanTest` (+ `blockedOnWindows`).
- `VectorGenerator.kt` + `spec/vectors/` (+ README): new cases for each rule.
- `spec/FORMAT.md`, `SECURITY.md`, `intellij/CHANGELOG.md` (`### Security`), `AGENTS.md`, `docs/compass/**`.

## Acceptance check
- [ ] Each fix has a test that fails before the fix and passes after (recorded here).
- [ ] `make check` green; conformance tests cover the new vectors.
- [ ] Independent re-review of the paste path + spec finds no remaining bypass.
- [ ] PR #9 CI green (Qodana red only because the PR crosses the move), verifier Compatible.
- [ ] After merge: Qodana green on main; 2.1.1 released; Marketplace shows 2.1.1.

## Notes
- Symlinks can't be expressed in vectors (they live in the recipient's tree): the spec states the rule, the Kotlin
  platform test proves it, and each future implementation needs its own test.
- `:` and reserved names are blocked only on Windows targets, so Linux/macOS users can still receive `12:30.log`.
