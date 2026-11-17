# Security posture

Copy-Pasta moves files between IDEs as clipboard text. It has no server, makes no network calls, collects no telemetry
and stores no secrets. What it handles is **untrusted text pasted by the user** and **content the user may want to keep
private in transit**.

## Threat model

| Risk | Where | Control |
|---|---|---|
| Pasted archive writes outside the chosen folder (path traversal / "zip slip") | `ZipCodec.safePath` | Absolute paths, drive letters and `..` segments are rejected before anything is planned. Covered by unit tests. |
| Paste silently overwrites the user's files | `PastePreviewDialog`, `PasteWriter` | Nothing is written before the user confirms a preview listing every file as new, overwrite or identical. Writes go through the VFS after a Local History label, so the whole paste can be reverted. |
| Zip bomb (small text, huge expansion) | `ZipCodec.decode` | 200 MB total and 10,000 entries cap, checked while reading. Duplicate entries are rejected. |
| Links or special files planted by a crafted archive | `ZipCodec` / `PasteWriter` | Only regular file content is written. ZIP entries carry no symlink semantics in this format. |
| Copy follows a symlink out of the selection or loops | `CopyCollector` | Symlinks are not followed or copied. |
| Secrets or build output copied by accident | `CopyCollector` | VCS-ignored files, IDE-excluded folders and the IDE's global ignore list are skipped, unless the user explicitly selects them. |
| Clipboard contents read by chat services or other apps | `Crypto` | Optional AES-256-GCM with a PBKDF2-HMAC-SHA256 key (100k iterations), random 16-byte salt and 12-byte IV per copy. A wrong passphrase or tampered text fails authentication; nothing is extracted. The passphrase is never stored, and its char array is cleared after use. |
| Huge selection freezes the IDE | Size caps, background tasks | 10 MB per file (skipped and reported) and 50 MB per copy (refused). Collection, encryption and decoding run in `Task.Backgroundable`. |

## Known limitations

- **Visible metadata.** The header line of an encrypted copy shows the file count and total size. File names and
  content are encrypted.
- **Passphrase strength.** There is no strength check. PBKDF2 slows down guessing, but a weak passphrase is still weak.
  Share the passphrase through a different channel than the text.
- **Executable bit.** Files marked executable by the sender are made executable on paste (Unix only). Review the preview
  before pasting text from someone you don't trust, as you would with any downloaded script.

## Reporting

Please report vulnerabilities privately through GitHub security advisories on
[spsoni/copy-pasta](https://github.com/spsoni/copy-pasta/security/advisories/new), not in a public issue.
