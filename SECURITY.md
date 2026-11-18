# Security posture

Copy-Pasta moves files between IDEs as clipboard text. It has no server, makes no network calls, collects no telemetry
and stores no secrets. What it handles is **untrusted text pasted by the user** and **content the user may want to keep
private in transit**.

## Threat model

| Risk | Where | Control |
|---|---|---|
| Pasted archive writes outside the chosen folder (path traversal / "zip slip") | `ZipCodec.safePath` | Absolute paths, drive letters, `..` segments and control characters are rejected before anything is planned. Covered by unit tests and the shared vectors. |
| Paste follows a symlink already in the target folder (`link/x` with `link → /etc`, or a symlinked file overwritten through the link) | `PasteWriter.plan`, `PasteWriter.write` | An entry is blocked if any existing part of its path, or the file itself, is a symlink (broken ones too), a special file, or a Windows junction leading out of the target, checked on disk without following links. `write` checks again right before each file and refuses instead of following. Fixed in 2.1.1; earlier versions followed such links. |
| Pasted archive plants VCS hooks or config that run code (`.git/hooks`, `core.fsmonitor`) | `PasteWriter.plan`, `PathRules` | Entries inside `.git`, `.hg`, `.svn`, `.bzr` or `CVS` are blocked, case-insensitively, including names that are the same folder on Windows or macOS: trailing dots or spaces (`.git.`), the short names `GIT~1`, `HG~1`, `SVN~1` and `BZR~1`, characters HFS+ ignores (a zero-width non-joiner inside `.git`), and letters that case-fold to ASCII (`.ſvn`). |
| Names Windows treats specially (`C:` inside a path, `name:stream` alternate data streams, `CON`, `con.txt`, trailing dots) | `PathRules`, `PasteWriter.plan` | Blocked when pasting on Windows; allowed elsewhere, where they're ordinary names. |
| Entries that clash inside the archive (`x` and `x/y`, or `README.md` and `readme.md` on case-insensitive systems) overwrite each other | `PasteWriter.plan` | Such entries are blocked and listed as skipped in the preview. |
| Paste silently overwrites the user's files | `PastePreviewDialog`, `PasteWriter` | Nothing is written before the user confirms a preview listing every file as new, overwrite or identical, with executable files marked. Writes go through the VFS after a Local History label, so the whole paste can be reverted, including a paste that fails part-way. |
| Zip bomb (small text, huge expansion) | `ZipCodec.decode` | 200 MB total and 10,000 entries cap, checked while reading. Duplicate entries are rejected. |
| Links or special files planted by a crafted archive | `ZipCodec` / `PasteWriter` | Only regular file content is written. ZIP entries carry no symlink semantics in this format. |
| Copy follows a symlink out of the selection or loops | `CopyCollector` | Symlinks are not followed or copied. |
| Secrets or build output copied by accident | `CopyCollector` | VCS-ignored files, IDE-excluded folders and the IDE's global ignore list are skipped, unless the user explicitly selects them. |
| Clipboard contents read by chat services or other apps | `Crypto` | Optional AES-256-GCM with a PBKDF2-HMAC-SHA256 key (600k iterations, stored in the payload so it can be raised later; reads are capped at 5M), random 16-byte salt and 12-byte IV per copy. A wrong passphrase or tampered text fails authentication; nothing is extracted. The passphrase is never stored. Its char array and the dialog fields are cleared after use (best effort: the JVM may keep copies until garbage collection). |
| Huge selection freezes the IDE | Size caps, background tasks | 10 MB per file (skipped and reported) and 50 MB per copy (refused). Collection, encryption, Base64 decoding, decryption and unzipping run in `Task.Backgroundable`. Only a marker check happens on the UI thread. |

### VS Code extension

The extension (`vscode/`) applies the same controls. The format core is `vscode/src/format/`, held to the same shared
vectors and an interop test against the plugin. In addition:

- **No third-party runtime code.** The ZIP reader and writer are our own, on Node's `zlib` and `crypto`. CI fails if
  a runtime dependency appears. Dev dependencies are pinned, at least two weeks old, and installed without install
  scripts. The packager (`vsce`) isn't installed at all: it runs pinned via `npx` at packaging time.
- **Decoding runs in a worker thread** with a capped heap, so a huge or hostile paste can't freeze VS Code, and
  Cancel stops it.
- **The preview shows paths literally:** VS Code would render `$(name)` in a QuickPick label as an icon, so that
  sequence is defused.
- **Writes go through one `WorkspaceEdit`**, so the paste can be undone. VS Code's file service follows links, so
  each path is re-checked with `lstat` just before writing (the same residual race as below).
- **The clipboard is cleared only on the user's explicit request.** VS Code can't remove items from an OS clipboard
  history.

## Known limitations

- **Visible metadata.** The header line of an encrypted copy shows the file count and total size. File names and
  content are encrypted.
- **Passphrase strength.** There is no strength check. PBKDF2 slows down guessing, but a weak passphrase is still weak.
  Share the passphrase through a different channel than the text.
- **Permissions.** The sender's nine `rwx` bits are applied on paste (macOS and Linux), with four limits:
  - the owner always gets read and write;
  - group and others never get write;
  - group and others never get more access than the recipient's umask gives a new file, or than the existing file
    already gives when it's overwritten (read access allows execute, so a script still runs for whoever could read
    it). Under umask 077, a pasted file stays owner-only;
  - setuid, setgid and sticky are never stored or applied.

  So pasted text can make a file executable, but never world-writable or privileged. The preview flags executable
  files and any other permissions that aren't `rw-r--r--`. Review it before pasting text from someone you don't
  trust, as you would with any downloaded script.
- **Hard links and races.** A file in the target that is a hard link to a file elsewhere is overwritten in place,
  which changes the other name too (git can't create hard links, so a checked-out project normally has none). The
  re-check before each write narrows, but can't close, the window in which another local process swaps a folder for
  a symlink; that needs an attacker already running on the machine.
- **Clipboard history.** Copy-Pasta text (decrypted content never touches the clipboard) stays in the system clipboard
  and the IDE's clipboard history like anything else you copy. **Clear from Clipboard** in the status bar icon's menu
  removes it from both. Copy-Pasta never clears the clipboard on its own.

## Reporting

Please report vulnerabilities privately through GitHub security advisories on
[spsoni/copy-pasta](https://github.com/spsoni/copy-pasta/security/advisories/new), not in a public issue.
