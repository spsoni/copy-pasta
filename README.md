# Copy-Pasta

[![Build](https://github.com/spsoni/copy-pasta/actions/workflows/build.yml/badge.svg)](https://github.com/spsoni/copy-pasta/actions/workflows/build.yml)

Share files and folders as a block of text you paste into any chat, and get them back as real files in another JetBrains IDE.

<!-- Plugin description -->
**Copy-Pasta** turns files and folders into one block of text you can paste into Slack, Teams, email or a forum. The person you send it to pastes it back and gets the same files, after previewing exactly what will be written.

You don't need a repository, a file-sharing service or an attachment. It works offline, has no account and sends no data anywhere.

**Why you'll want it**

Chat is where developers actually talk, but it's bad at moving code. Paste three files into Slack and they arrive as a wall of text with the folder structure lost, curly quotes where straight ones should be, and the last file cut off. Attachments get blocked or expire. A branch needs everyone on the same repository. Copy-Pasta gives you one block of text that holds a whole folder tree and comes back byte for byte.

- **Send a working reproducer.** Copy the four files that show the bug. Your colleague gets a runnable folder, not a description of one.
- **Help across repositories.** Share a config, a Gradle setup or a scaffold with someone who doesn't have access to your project.
- **Move work between machines.** Get a prototype from a work laptop to a home desktop, or into a locked-down VM where chat is the only thing that gets through.
- **Answer on forums and in support tickets.** Post a complete example in one message instead of five code blocks and "put this in `src/`".
- **Keep secrets secret.** Add a passphrase and send it through a different channel. The chat log only ever holds ciphertext.

Two clicks to copy and two to paste, and you see every file before it touches your project.

**How it works**

1. In the **Project** view, right-click files or folders and choose **Copy-Pasta | Copy**.
2. Paste the text wherever you like. It starts with a line explaining what it is.
3. The recipient copies the whole block, right-clicks a folder and chooses **Copy-Pasta | Paste…** (or **Edit | Paste Files from Copy-Pasta…**).
   A preview lists every file as *new*, *overwrite* or *identical*, and nothing is written until they confirm.

**Features**

- **Smart selection:** files ignored by your VCS (`.gitignore`), excluded folders and the IDE's ignored files are left out automatically. Unsaved editor changes are included.
- **Safe paste:** preview with checkboxes (executable files are marked), a single Local History checkpoint so the whole paste can be undone, and protection against paths that escape the target folder or write into `.git`.
- **Chat-proof text:** survives email quoting, line re-wrapping and surrounding chat text. If a chat app cuts the message, you're told it's incomplete instead of getting broken files.
- **Optional encryption:** **Copy Encrypted…** protects the content with a passphrase (AES-256-GCM). The passphrase is never stored; share it separately.
- **No lock-in:** the content is a standard ZIP. Without the plugin, the Base64 lines between the `Format:` line and the END line decode with `base64 -d`, then `unzip`.
- **Lightweight:** no settings to configure, no dependencies, and work runs in the background.

Works in IntelliJ IDEA, PyCharm, WebStorm, GoLand, Rider, CLion, PhpStorm, RubyMine, DataGrip, Android Studio and other JetBrains IDEs, version 2025.3 or later.
<!-- Plugin description end -->

![The Paste Files preview: every file is listed as new, overwrite or identical before anything is written](docs/images/paste-preview.png)

**[User guide](docs/user-guide.md)**: copying, pasting, encryption, chat limits, undo and troubleshooting.

## Installation

- **JetBrains Marketplace**: Settings | Plugins | Marketplace, then search for "Copy-Pasta".
- **Manual**: download the ZIP from [Releases](https://github.com/spsoni/copy-pasta/releases), then go to Settings | Plugins | ⚙️ | Install Plugin from Disk…

## What the text looks like

```
-----BEGIN COPY-PASTA-----
Copy-Pasta: 3 files, 4.1 KB. Paste it with the Copy-Pasta plugin for JetBrains IDEs: https://github.com/spsoni/copy-pasta
Format: 2; length: 5612
UEsDBBQACAgIA...                      (Base64, 76 characters per line)
-----END COPY-PASTA-----
```

- The body is Base64 of a ZIP. File paths are relative to the selection, and the executable bit is stored in a private ZIP extra field.
- `BEGIN COPY-PASTA ENCRYPTED` means the body is `CPv2` | PBKDF2 iteration count (int32) | 16-byte salt | 12-byte IV |
  AES-256-GCM ciphertext and tag. The key comes from the passphrase via PBKDF2-HMAC-SHA256 (600,000 iterations by default).
- `length` lets the plugin detect a message cut off by a chat app.
- Copy-Pasta 1.x text (raw Base64 tar.gz) can't be pasted with 2.x.

Size limits: 10 MB per file and 50 MB per copy. A notification warns when the text is longer than chat apps usually allow (about 35k characters).

## Development

Requires JDK 21; Gradle comes from the wrapper. On macOS, `make setup-mac` installs JDK 21, the GitHub CLI and the
workflow linters with Homebrew (see [Brewfile](Brewfile)) and enables the pre-commit hook. It takes a few minutes with
Homebrew at its default prefix (`/opt/homebrew`); elsewhere, some JDK dependencies build from source. Run `make help` to
list every target.

```bash
make check     # ktlint + unit and platform tests
make build     # plugin ZIP in build/distributions/
make run       # sandbox IDE with the plugin installed (needs a display)
make verify    # IntelliJ Plugin Verifier against the supported IDEs
make release VERSION=2.0.1   # bump version, date the changelog, commit and tag
```

Releases are tag-based. Pushing a `vX.Y.Z` tag runs [release.yml](.github/workflows/release.yml), which tests, verifies, signs,
publishes to the Marketplace and creates the GitHub release. The Marketplace description is the `Plugin description` block
above; the change notes come from [CHANGELOG.md](CHANGELOG.md).

## Feedback and contributions

Bug reports and pull requests are welcome. Please [open an issue](https://github.com/spsoni/copy-pasta/issues).

## License

[MIT](LICENSE)
