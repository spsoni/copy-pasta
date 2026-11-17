<!-- Keep a Changelog guide -> https://keepachangelog.com -->

# Copy-Pasta Changelog

## [Unreleased]

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

[Unreleased]: https://github.com/spsoni/copy-pasta/compare/v2.0.0...HEAD
[2.0.0]: https://github.com/spsoni/copy-pasta/compare/v1.0.0...v2.0.0
[1.0.0]: https://github.com/spsoni/copy-pasta/commits/v1.0.0
