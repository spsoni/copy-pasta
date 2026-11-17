# Security posture

Copy-Pasta moves files between IDEs as clipboard text. It has no server, no network
calls, no telemetry and no accounts. What it handles is **untrusted input pasted by the
user** and **content the user may want to keep private in transit**.

## Threat model

| Risk | Where | Control |
|---|---|---|
| Pasted archive writes outside the chosen folder (path traversal / "zip slip") | `CopyPastaUtils.extractTarGz` | Every entry path is normalised and must stay under the target folder. Otherwise extraction aborts with an error. Covered by a unit test. |
| Pasted archive plants symlinks / hard links / device files | `extractTarGz` | Only regular files and directories are written. Everything else is skipped. |
| Copying follows a symlink out of the selection or loops forever | `CopyPastaUtils.addToTar` | Symlinks are not followed when archiving. |
| Secrets or bulky build output copied by accident | Exclude patterns | Defaults exclude `.git`, virtualenvs, `build`, `dist`, `node_modules`, IDE folders, logs and caches. Users can extend the list. |
| Clipboard contents read by other apps or chat services | Encryption | AES-256-GCM (authenticated), key from PBKDF2-HMAC-SHA256 (100k iterations) with a random 16-byte salt and 12-byte IV per payload. A wrong key or tampered data fails the GCM tag check, so nothing is extracted. |
| Huge payload freezes the IDE | Size limit + background tasks | Encoding and extraction run in `Task.Backgroundable`. Copies above the configured limit (default 1 MB of text) are refused. |

## Known limitations

- **Default key.** The plugin ships with a shared default key (`default_key_123456`) so
  two fresh installs can exchange payloads. That is obfuscation, not secrecy. Set your own
  key in Settings | Tools | Copy-Pasta when the content is private, and share it out of band.
- **Key storage.** The key is stored in plain text in the IDE's `CopyPastaSettings.xml`
  (application settings), not in the IDE password safe.
- **Overwrites.** Pasting replaces existing files with the same names in the target
  folder, without asking.
- **Decompression size.** Extraction does not cap the total decompressed size. A crafted
  small payload could expand to a large amount of disk.

## Reporting

Please report vulnerabilities privately through GitHub security advisories on
[spsoni/copy-pasta](https://github.com/spsoni/copy-pasta/security/advisories/new), not in a public issue.
