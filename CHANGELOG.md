<!-- Keep a Changelog guide -> https://keepachangelog.com -->

# Copy-Pasta Changelog

## [Unreleased]
### Added
- Settings checkbox for encrypting copied content (it was previously always on).
- Unit tests for archiving, extraction, exclusion and encryption.

### Changed
- Encryption now uses AES-256-GCM with a PBKDF2-derived key and a random salt and IV. **Payloads from 1.x can't be pasted with 2.x.**
- Settings moved to the application level (Settings | Tools | Copy-Pasta) and rebuilt with the Kotlin UI DSL. The encryption key field is now masked.
- Copy and Paste run as background tasks and use the IDE clipboard.
- The menu actions are hidden when they don't apply to the selection. Paste into a selected file targets that file's folder.
- Compatibility: IntelliJ-based IDEs 2024.3 and newer (no upper bound). Built with Kotlin 2.4 and Gradle 9.

### Fixed
- Pasting could write files outside the target folder (path traversal / "zip slip").
- Pasting unencrypted content always failed, because gzip data was mistaken for encrypted data.
- The encryption key was derived incorrectly, so many keys produced invalid AES key lengths.
- Exclude patterns such as `.git/*` or `build/*` never matched.
- The clipboard was cleared even when pasting failed.
- Notifications used an unregistered notification group.

## [1.2.0] - 2023-12-01
### Added
- Code cleanup to address Qodana reported issues.

## [1.1.0] - 2023-11-01
### Added
- Code cleanup to address Qodana reported issues.

## [1.0.0] - 2023-10-01
### Added
- Initial release of Copy-Pasta, a tool for managing and sharing code snippets.
