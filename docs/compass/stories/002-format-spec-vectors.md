# Story 002 — format-spec + conformance vectors

> Written in plan mode as the plan; owner approval of the plan = approval of this story.

**Status:** verifying (PR CI)
**Depends on:** 001

## Goal
Write the clipboard format down as a language-neutral spec that a VS Code (TypeScript) or desktop (Rust) implementer
can follow without reading Kotlin, and freeze it in golden vectors that the JetBrains plugin is tested against now and
every later implementation in its own CI. No format change: still `Format: 2`.

## Scope — touch ONLY these
- `spec/FORMAT.md`: envelope (markers and how readers find them, tolerance for `> ` quotes, CRLF and collapsed
  newlines, the summary line `Copy-Pasta: <summary>. Paste it…` and its sanitising, `Format: N; length: L`, Base64 at
  76 columns); ZIP body (relative `/` paths, `0x4350` field: byte 0 exec, bytes 1–2 mode little-endian, append-only);
  `CPv2` crypto layout and PBKDF2 bounds; reader MUST rules (path safety, duplicate and case clashes, size caps of
  10k entries / 200 MB on decode and 10 MB / 50 MB on copy, iteration cap 5M, VCS-metadata blocking, `FileMode.safe`
  and `FileMode.applied`, ReDoS-safe marker search); error categories (NOT_FOUND, INCOMPLETE, CORRUPTED,
  NEWER_VERSION, WRONG_PASSPHRASE, INVALID_ARCHIVE); versioning (when `Format: 3`).
- `spec/FEATURES.md`: parity matrix (core vs UX) × JetBrains / VS Code / macOS / Windows; JetBrains filled in.
- `spec/vectors/`: `manifest.json` plus one `.txt` per case; expected trees inline (path, Base64 content, mode, exec).
  Cases: plain, encrypted (passphrase in the manifest), mixed modes, 2.0 exec-only field, `> ` quoted, CRLF, collapsed
  newlines, chat text around the envelope, truncated, newer format 3, wrong passphrase, zip-slip `../`, absolute path,
  duplicate entry, case clash, `.git/hooks` (decodes; plan blocks it), zip bomb (deflated zeros over the cap), PBKDF2
  iterations over the cap, 64k-dash line (time budget). `spec/vectors/modes.json`: `(sender, current) → safe, applied`.
- `intellij/src/test/kotlin/.../format/ConformanceTest.kt`: every vector through `Envelope.decode` → `Crypto.decrypt`
  → `ZipCodec.decode` (+ `PasteWriter.plan` for blocking cases, `FileMode` for modes.json); exceptions mapped to the
  spec's error categories.
- `intellij/src/test/kotlin/.../format/VectorGenerator.kt`: regenerates vectors only with `-PregenerateVectors`
  (malicious ZIPs via raw `ZipOutputStream`); never in normal runs.
- `intellij/build.gradle.kts`: the vectors directory as a test system property and declared input.
- `intellij/src/test/kotlin/.../ide/ConformancePlanTest.kt`: the `blocked` expectations through `PasteWriter.plan`
  (needs the platform test fixture); `.../format/Vectors.kt`: shared manifest model (Gson from the platform, tests only).
- `Makefile`: `make vectors` runs the generator (added during the build: the story missed how to run it).
- `.gitattributes` (new): `spec/vectors/** -text`, so git never rewrites line endings in the vectors (the `crlf` case
  would silently become LF on a Windows checkout). Added during the build.
- `intellij/README.md` format section → short summary linking `spec/FORMAT.md`; `AGENTS.md`: the spec and vectors
  are the contract; a format change = spec PR + new vectors. Root `README.md` layout list gains `spec/`.
- `docs/compass/**`.

## Acceptance check
- [x] `make check` green with ConformanceTest running every vector: 101 tests, 0 failures (44 vector cases, the
  modes table, the plan check).
- [x] Mutation sanity: dropping the `..` check from `ZipCodec.safePath` failed exactly `zip-slip-parent` and
  `zip-slip-nested`; restored.
- [x] Regenerating vectors: sha256 of every file identical before and after `make vectors`.
- [x] Independent review of FORMAT.md against the Kotlin code: no contradictions. Its clarifications of current
  behaviour (character classes, body whitespace, Base64 strictness, marker extent, streaming ZIP reading, UTF-8
  names, Stored/descriptor rules, zero files) are in the spec, with 8 new vectors that pass against today's code.
  Its security findings change behaviour, so they're story 003.
- [ ] PR CI green.

## Notes
- The envelope's human line still says "plugin for JetBrains IDEs"; it changes in story 008 once a second product
  ships. Readers rely only on `Copy-Pasta: … . Paste it`.
