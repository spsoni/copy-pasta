# Copy-Pasta

[![Build](https://github.com/spsoni/copy-pasta/actions/workflows/build.yml/badge.svg)](https://github.com/spsoni/copy-pasta/actions/workflows/build.yml)

A plugin for IntelliJ-based IDEs that lets you share files and folders as text you copy and paste.

<!-- Plugin description -->
**Copy-Pasta** lets you share files and whole folders as a single block of text. Copy a selection from the Project view, paste the text into Slack, Teams, email or a forum post, and the person you send it to pastes it back into their IDE. They get the same files and folders you selected.

You don't need a repository, a file-sharing service or a zip attachment. It's useful when chat tools block attachments, or when you just want to pass a small project, a failing example or a set of config files to a teammate.

**How it works**

1. Right-click files or folders in the **Project** view and choose **Copy-Pasta | Copy**.
   The selection is archived (tar.gz), optionally encrypted, Base64-encoded and put on the clipboard.
2. Send the text however you like.
3. The recipient copies the text, right-clicks a folder in their **Project** view and chooses **Copy-Pasta | Paste**.
   The files are extracted into that folder and the IDE picks them up right away.

**Features**

- Copy several files and folders at once. The folder structure and executable permissions are kept.
- Optional **AES-256-GCM encryption** using a key you share with the recipient, so the pasted text can't be read in transit.
- **Exclude patterns** (for example `.git/*`, `build/*`, `node_modules/*`, `*.log`) keep generated files and secrets out of the archive. Sensible defaults are included.
- Pasting is safe: entries that try to write outside the target folder are rejected, and links and special files are skipped.
- A size limit keeps you from accidentally putting a huge payload on the clipboard.
- Copying and pasting run in the background, so the IDE stays responsive.

**Settings**

Go to **Settings | Tools | Copy-Pasta** to configure:

- **Exclude patterns**: comma- or newline-separated globs, matched against paths relative to the selection.
- **Max clipboard payload**: the largest encoded text, in bytes, that will be copied (default 1 MB).
- **Encrypt copied content** / **Decrypt pasted content**: turn encryption on or off in each direction.
- **Encryption key**: the sender and the recipient must use the same key. The plugin ships with a shared default key, so set your own if the content is private.

**Note:** pasting overwrites existing files with the same names in the target folder.
<!-- Plugin description end -->

## Installation

- **JetBrains Marketplace**: Settings | Plugins | Marketplace, then search for "Copy-Pasta".
- **Manual**: download the latest ZIP from [Releases](https://github.com/spsoni/copy-pasta/releases), then go to Settings | Plugins | ⚙️ | Install Plugin from Disk…

## Payload format

The clipboard text is Base64 of either:

- **Plain**: a gzip-compressed tar archive.
- **Encrypted**: `CPv2` | 16-byte salt | 12-byte IV | AES-256-GCM ciphertext and tag. The key is derived from the passphrase with PBKDF2-HMAC-SHA256 (100,000 iterations).

Payloads from Copy-Pasta 1.x (AES/ECB) can't be read by 2.x.

## Development

Requires JDK 21. Gradle is provided by the wrapper.

```bash
./gradlew check          # unit tests + coverage
./gradlew buildPlugin    # plugin ZIP in build/distributions/
./gradlew runIde         # start a sandbox IDE with the plugin installed
./gradlew verifyPlugin   # IntelliJ Plugin Verifier against recommended IDEs
```

The text shown on the Marketplace and in the IDE's plugin install screen is generated from the
`Plugin description` block above. Change notes come from [CHANGELOG.md](CHANGELOG.md).

## Feedback and contributions

Bug reports and pull requests are welcome. Please [open an issue](https://github.com/spsoni/copy-pasta/issues).

## License

[Apache License 2.0](LICENSE)
