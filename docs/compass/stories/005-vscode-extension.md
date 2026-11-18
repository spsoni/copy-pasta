# Story 005 — vscode-extension

> Written in plan mode as the plan; owner approval of the plan = approval of this story. Details filled in from the
> approved outline once 004's core existed.

**Status:** verifying (CI integration tests, owner desktop check)
**Depends on:** 004

## Goal
A VS Code extension (`spsoni.copy-pasta`) with the JetBrains plugin's core features on top of the 004 format core,
packaged as a `.vsix` and released by tag `vscode-vX.Y.Z` to the VS Code Marketplace and Open VSX. Text copied in
either product pastes in the other. Same security posture: no network, no telemetry, no runtime dependencies.

## Scope — touch ONLY these
- `vscode/src/extension.ts` (activation, commands, exported test API) and `vscode/src/ui/`:
  - `copy.ts`: Explorer selection → files, walking with `workspace.fs` and skipping symlinks. `files.exclude` via
    `findFiles`, Git ignores via the built-in Git extension's `checkIgnore`, unless the user selected the item
    itself. Unsaved editor content; 10 MB per file / 50 MB total; paths from the selection's common ancestor; nested
    selections once. Then ZIP → optional passphrase → envelope → clipboard. Long-text warning with **Save as File…**;
  - `paste.ts`: target folder (selection, or workspace root) → clipboard → passphrase → decode with progress →
    plan → preview QuickPick (IDENTICAL unticked; BLOCKED never offered, listed in the output channel) → re-check
    each entry → `WorkspaceEdit` (undoable) → permissions with `fs.chmod` (POSIX, `file:` targets). Fallback
    **Paste from File…**;
  - `statusBar.ts`: indicator refreshed on window focus and after our own copy/paste (no polling); click menu
    (Paste into Workspace Root…, Paste into Folder…, Save as File…, Clear from Clipboard);
  - `messages.ts`: user-facing text for each error category.
- `vscode/package.json`: contributions (commands, Explorer submenu, palette), `main`, activation, icon; dev
  dependencies `@types/vscode` 1.90.0, `@vscode/test-electron`, `@vscode/vsce` (exact, at least two weeks old).
  `vscode/.vscodeignore`, `vscode/images/icon.png` (128 px, from the plugin artwork), `vscode/README.md`
  (Marketplace page), `vscode/CHANGELOG.md`, `vscode/LICENSE`.
- `vscode/test/integration/`: runs inside VS Code via `@vscode/test-electron` (CI under `xvfb-run`). It copies a
  fixture folder, pastes into another folder through the exported API, and checks files, modes, blocked entries and
  undo.
- `.github/workflows/vscode.yml` (integration job), `.github/workflows/release-vscode.yml` (tag = version check,
  check, `vsce package`, GitHub release with the `.vsix`, publish with `VSCE_PAT` / `OVSX_PAT` when set).
- Host-neutral summary line in both writers: `Envelope.kt`, `envelope.ts` (+ their tests if they assert the wording).
- Root `README.md` products table, `spec/FEATURES.md`, `SECURITY.md` (VS Code row), `AGENTS.md`, `Makefile`,
  `docs/compass/**`.

## Acceptance check
- [ ] `make vscode-check` green (unit + vectors): 122 tests, 0 failures locally. The integration tests (7, inside
  VS Code 1.90.0 and stable) run only in CI.
- [x] `make vscode-package`: a 44 KB `.vsix` with 22 files (compiled JS, icon, README, changelog, license). No
  `node_modules`, tests, sources, source maps or `tools/`; CI fails the job if any appear.
- [ ] Interop still green; text copied by the extension pastes in the plugin (CI) and vice versa.
- [x] Independent review of the extension layer, checked against the VS Code 1.90 sources. Fixed:
  - **Undo of an overwrite deleted the file:** VS Code's undo of an overwriting `createFile` is a delete. Existing
    files are now deleted, which snapshots them for undo, and created again in the same edit.
  - **Unsaved editor changes were silently saved, then lost:** such files are now skipped, with a reason.
  - **Archive names reached notifications, where `[x](command:…)` becomes a clickable command link:** no
    archive-derived text goes into notifications any more; details go to the output channel.
  - **Copy counted `node_modules` and `.git` before filtering:** the walk now prunes level by level (one
    `git check-ignore` per level) and enforces the 10,000-file limit on the files kept, across all roots.
  - **`.git` contents could be copied:** VCS metadata is now never copied.
  - **Git ignore rules could silently not apply:** the copy now waits for the Git API and asks first ("Copy
    Anyway") when a work tree has no open repository or Git fails.
  - **Paths every reader rejects (drive roots, separate drives):** each path goes through `safePath` at copy time,
    and roots without a shared folder use their own names.
  - **CI tooling on Node 22.**
  - **Release secrets:** given only to their publish step; no persisted credentials; tools pinned by
    `vscode/tools`' lockfile; idempotent re-runs.
  - **Smaller fixes:**
    - re-plan before writing (abort if the folder changed);
    - no `chmod` through links;
    - bidi/invisible characters escaped in the preview;
    - skipped entries listed in the preview;
    - `onPasted` only after a write;
    - encoding in the worker;
    - commands report their own failures;
    - status bar refreshed when its menu opens;
    - BSD-safe release recipe.
- [ ] Owner checks the UI on a desktop (dev01 is headless) before the first `vscode-v0.1.0` tag.

## Notes
- No glob library (zero runtime deps): `files.exclude` comes from `workspace.findFiles`, which applies it, and the
  structure from our own walk, so symlinks are never followed even though search follows them by default.
- Writes go through VS Code's file service, which follows links. The plan and the per-entry re-check refuse
  links first; the residual race against a concurrent local process is as documented in SECURITY.md.
