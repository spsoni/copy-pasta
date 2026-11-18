# Feature parity

**Core** features are the format and its safety rules: every product MUST have them, identically, and the
[vectors](vectors/README.md) check them. **UX** features fit each host and may differ. Update this table in the same
change that adds or changes a feature in any product.

✅ done · 🚧 in progress · ⬜ planned · — not applicable

## Core (FORMAT.md, enforced by the vectors)

| Feature | JetBrains | VS Code | macOS | Windows |
|---|---|---|---|---|
| Envelope write and tolerant read (quotes, CRLF, collapsed newlines, re-wrap) | ✅ | ✅ | ⬜ | ⬜ |
| Truncation detected and reported (INCOMPLETE) | ✅ | ✅ | ⬜ | ⬜ |
| Linear-time marker search | ✅ | ✅ | ⬜ | ⬜ |
| Passphrase encryption (`CPv2`, PBKDF2 600k, AES-256-GCM) | ✅ | ✅ | ⬜ | ⬜ |
| Path safety, duplicate rejection, 10k-file and 200 MB limits | ✅ | ✅ | ⬜ | ⬜ |
| Permissions: write `0x4350`, apply `safe` + `applied` | ✅ | ✅ | ⬜ | ⬜ |
| Paste plan: NEW / OVERWRITE / IDENTICAL / BLOCKED; VCS lookalikes, symlinks, Windows names blocked | ✅ | ✅ | ⬜ | ⬜ |
| Preview before writing, files can be left out | ✅ | ✅ | ⬜ | ⬜ |
| Copy limits: 10 MB per file, 50 MB total | ✅ | ✅ | ⬜ | ⬜ |

## UX (host-specific)

| Feature | JetBrains | VS Code | macOS | Windows |
|---|---|---|---|---|
| Copy from the file tree / Finder / Explorer selection | ✅ Project view | ✅ Explorer | ⬜ Finder | ⬜ Explorer |
| Copy Encrypted… | ✅ | ✅ | ⬜ | ⬜ |
| Honour ignore rules | ✅ VCS + excluded | ✅ Git + `files.exclude` | ⬜ `.gitignore` | ⬜ `.gitignore` |
| Include unsaved editor content | ✅ | ✅ | — | — |
| Long-text warning + Save as File… | ✅ | ✅ | ⬜ | ⬜ |
| Paste… into a selected folder | ✅ | ✅ | ⬜ | ⬜ |
| Fallback: paste text or open a saved file | ✅ | ✅ | ⬜ | ⬜ |
| Undo the whole paste | ✅ Local History | ✅ Edit › Undo (WorkspaceEdit) | — | — |
| Clipboard indicator (status bar / menu bar) | ✅ | ✅ refreshed on focus | ⬜ | ⬜ |
| Clear Copy-Pasta text from the clipboard | ✅ | ✅ | ⬜ | ⬜ |
| Command line (`copy-pasta copy` / `paste`) | — | — | ⬜ | ⬜ |
