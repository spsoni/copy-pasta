<!-- Keep a Changelog guide -> https://keepachangelog.com -->

# Copy-Pasta Changelog

## [Unreleased]

## [2.1.0] - 2026-11-17

### Added

- A status bar icon that appears while the clipboard holds Copy-Pasta text. It pulses once when new text arrives, dims
  after the text has been pasted, and disappears when you copy something else. Click it to paste into the project
  root or a chosen folder, save the text as a file, or clear Copy-Pasta text from the clipboard and the IDE's
  clipboard history. Hide it from the status bar's context menu.
- **Paste…** no longer stops with an error when the clipboard holds something else. It opens a dialog where you can
  paste the message or open a file that contains it, and it reports cut-off or damaged text right there.
- **Save as File…** on the "long for chat" notification saves the text as a `.txt` file to send as an attachment. The
  suggested name includes the local date and time, for example `copy-pasta-2026-11-17-135212.txt`.
- The Project view menu shows what's on the clipboard, for example **Paste… (3 files, 4.1 KB)**.
- An error about cut-off or damaged text offers **Paste Text or Open File…**.
- File permissions travel on macOS and Linux (the full `rwx` bits, not just the executable flag), so private files
  such as keys stay private. Pasted files are never group- or world-writable, never more open than your umask
  allows, the owner can always edit them, and special bits such as setuid are never applied. The preview flags unusual permissions. Text from 2.0 still pastes,
  and 2.0 still reads the executable flag from 2.1 text.

### Fixed

- Pasting while the clipboard held a very long line of dashes (for example a log or a text table) could freeze the
  IDE for seconds.

## [2.0.1] - 2026-11-17

### Changed

- Copy-Pasta is now released under the MIT License (previously Apache 2.0).
- A clearer Marketplace description of what the plugin is for.

## [2.0.0] - 2026-11-17

A redesign focused on safe pasting and chat-proof text. **Text copied with 1.x can't be pasted with 2.x.**

### Added

- A paste preview: every file is marked new, overwrite or identical, with checkboxes, before anything is written.
- One Local History checkpoint per paste, so a whole paste can be reverted.
- Self-describing text with BEGIN/END markers, a readable header line, and detection of messages cut off by chat apps.
- **Copy Encrypted…** with a passphrase that is never stored (AES-256-GCM, PBKDF2 with 600,000 iterations).
- **Edit | Paste Files from Copy-Pasta…** in addition to the Project view menu.
- Warnings when the text is likely too long for chat apps, and when large files are skipped.
- Executable files are marked in the preview. Entries that would write into `.git` or similar VCS folders are blocked.

### Changed

- Files ignored by your VCS, excluded folders and the IDE's ignored files are skipped automatically, replacing the
  hand-maintained exclude patterns. Folders you select explicitly are always copied.
- Unsaved editor changes are included in the copy.
- The content is a standard ZIP instead of tar.gz; the plugin no longer depends on a library bundled with the IDE.
- Requires a 2025.3 or newer JetBrains IDE.

### Removed

- The Settings page, the stored encryption key and its shared default value.
- Clearing the clipboard after a paste.

### Fixed

- Pasted archives could write outside the target folder.
- Pasting unencrypted text always failed.
- Exclude patterns never matched.
- Pasted files bypassed the IDE's virtual file system, so editors and Local History didn't see them.

## [1.0.0] - 2025-07-19

### Added

- Initial release: copy files and folders to the clipboard as Base64 text and paste them back, with optional encryption.

[Unreleased]: https://github.com/spsoni/copy-pasta/compare/v2.1.0...HEAD
[2.1.0]: https://github.com/spsoni/copy-pasta/compare/v2.0.1...v2.1.0
[2.0.1]: https://github.com/spsoni/copy-pasta/compare/v2.0.0...v2.0.1
[2.0.0]: https://github.com/spsoni/copy-pasta/compare/v1.0.0...v2.0.0
[1.0.0]: https://github.com/spsoni/copy-pasta/commits/v1.0.0
