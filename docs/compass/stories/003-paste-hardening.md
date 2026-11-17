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
- [x] Each fix has a test that fails before the fix and passes after. Before (2.1.0 code, `HardeningTest` +
  `PasteWriterTest`): 9 failures, among them the symlink plan `[NEW, OVERWRITE, OVERWRITE, NEW, NEW]` where
  `[BLOCKED ×4, NEW]` was expected (`link/planted.txt` would have been written outside the target), VCS lookalikes
  `NEW`, `EOFException` / `IllegalArgumentException` escaping, short CPv2 → `WrongPassphraseException` after 5M
  PBKDF2 iterations, control characters accepted, emoji body → INCOMPLETE. After: all 16 pass, plus the Windows-names
  test.
- [x] `make check` green: 119 tests, 0 failures; 52 vector cases (8 new for this story) in ConformanceTest, the plan
  check covers `blocked` and `blockedOnWindows` on both a Windows and a non-Windows target.
- [x] README badges and install link added outside the plugin-description block: the built `plugin.xml`
  description is byte-identical to before.
- [x] Independent re-review of the paste path + spec: no way to escape the target or follow an on-disk symlink.
  Its findings, all fixed in scope:
  - Windows short names `HG~1`/`SVN~1`/`BZR~1`;
  - every Windows reparse point blocked (OneDrive placeholders): now only those whose real path leaves the target;
  - Unicode case folding (`.ſvn`);
  - `COM0`/`LPT0`/`COM¹²³`;
  - NFC/NFD duplicates;
  - `InvalidPathException` → blocked;
  - invisible characters written as literals → code points.

  Removing the short names and folding fails `PasteWriterTest` + `ConformancePlanTest` (2 failures); restored.
  `make check`: 120 tests, 0 failures.
  Not fixed (documented in SECURITY.md): hard links, the residual plan→write race against a concurrent local
  process, and the per-directory case sensitivity of mixed mounts.
- [ ] PR #9 CI green (Qodana red only because the PR crosses the move), verifier Compatible.
- [ ] After merge: Qodana green on main; 2.1.1 released; Marketplace shows 2.1.1.

## Notes
- Symlinks can't be expressed in vectors (they live in the recipient's tree): the spec states the rule, the Kotlin
  platform test proves it, and each future implementation needs its own test.
- `:` and reserved names are blocked only on Windows targets, so Linux/macOS users can still receive `12:30.log`.
