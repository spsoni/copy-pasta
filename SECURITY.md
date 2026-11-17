# Security posture

Copy-Pasta moves files between IDEs as clipboard text. It has no server, makes no network calls, collects no telemetry
and stores no secrets. What it handles is **untrusted text pasted by the user** and **content the user may want to keep
private in transit**.

## Threat model

| Risk | Where | Control |
|---|---|---|
| Pasted archive writes outside the chosen folder (path traversal / "zip slip") | `ZipCodec.safePath` | Absolute paths, drive letters and `..` segments are rejected before anything is planned. Covered by unit tests. |
| Pasted archive plants VCS hooks or config that run code (`.git/hooks`, `core.fsmonitor`) | `PasteWriter.plan` | Entries inside `.git`, `.hg`, `.svn`, `.bzr` or `CVS` are blocked, case-insensitively. |
| Entries that clash inside the archive (`x` and `x/y`, or `README.md` and `readme.md` on case-insensitive systems) overwrite each other | `PasteWriter.plan` | Such entries are blocked and listed as skipped in the preview. |
| Paste silently overwrites the user's files | `PastePreviewDialog`, `PasteWriter` | Nothing is written before the user confirms a preview listing every file as new, overwrite or identical, with executable files marked. Writes go through the VFS after a Local History label, so the whole paste can be reverted, including a paste that fails part-way. |
| Zip bomb (small text, huge expansion) | `ZipCodec.decode` | 200 MB total and 10,000 entries cap, checked while reading. Duplicate entries are rejected. |
| Links or special files planted by a crafted archive | `ZipCodec` / `PasteWriter` | Only regular file content is written. ZIP entries carry no symlink semantics in this format. |
| Copy follows a symlink out of the selection or loops | `CopyCollector` | Symlinks are not followed or copied. |
| Secrets or build output copied by accident | `CopyCollector` | VCS-ignored files, IDE-excluded folders and the IDE's global ignore list are skipped, unless the user explicitly selects them. |
| Clipboard contents read by chat services or other apps | `Crypto` | Optional AES-256-GCM with a PBKDF2-HMAC-SHA256 key (600k iterations, stored in the payload so it can be raised later; reads are capped at 5M), random 16-byte salt and 12-byte IV per copy. A wrong passphrase or tampered text fails authentication; nothing is extracted. The passphrase is never stored. Its char array and the dialog fields are cleared after use (best effort: the JVM may keep copies until garbage collection). |
| Huge selection freezes the IDE | Size caps, background tasks | 10 MB per file (skipped and reported) and 50 MB per copy (refused). Collection, encryption, Base64 decoding, decryption and unzipping run in `Task.Backgroundable`. Only a marker check happens on the UI thread. |

## Known limitations

- **Visible metadata.** The header line of an encrypted copy shows the file count and total size. File names and
  content are encrypted.
- **Passphrase strength.** There is no strength check. PBKDF2 slows down guessing, but a weak passphrase is still weak.
  Share the passphrase through a different channel than the text.
- **Executable bit.** Files marked executable by the sender are made executable on paste, for the file's owner only
  (macOS and Linux). No other mode bits travel: no setuid/setgid, group or other permissions. The preview marks
  executable files. Review the preview before pasting text from someone you don't trust, as you would with any
  downloaded script.
- **Clipboard history.** Copy-Pasta text (decrypted content never touches the clipboard) stays in the system clipboard
  and the IDE's clipboard history like anything else you copy. **Clear from Clipboard** in the status bar icon's menu
  removes it from both. Copy-Pasta never clears the clipboard on its own.

## Reporting

Please report vulnerabilities privately through GitHub security advisories on
[spsoni/copy-pasta](https://github.com/spsoni/copy-pasta/security/advisories/new), not in a public issue.
