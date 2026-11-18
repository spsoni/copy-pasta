<p align="center">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="intellij/src/main/resources/META-INF/pluginIcon_dark.svg">
    <img src="intellij/src/main/resources/META-INF/pluginIcon.svg" width="96" height="96" alt="Copy-Pasta icon: a clipboard holding three wavy lines">
  </picture>
</p>

<h1 align="center">Copy-Pasta</h1>

<p align="center">
  <strong>Send files and folders through any chat, and get them back as real files on the other side.</strong>
</p>

<p align="center">
  <a href="https://github.com/spsoni/copy-pasta/actions/workflows/build.yml"><img src="https://github.com/spsoni/copy-pasta/actions/workflows/build.yml/badge.svg" alt="Build"></a>
  <a href="https://plugins.jetbrains.com/plugin/36213-copy-pasta"><img src="https://img.shields.io/jetbrains/plugin/v/36213?label=JetBrains%20Marketplace" alt="JetBrains Marketplace version"></a>
  <a href="https://plugins.jetbrains.com/plugin/36213-copy-pasta"><img src="https://img.shields.io/jetbrains/plugin/d/36213" alt="JetBrains Marketplace downloads"></a>
  <a href="https://github.com/spsoni/copy-pasta/releases/latest"><img src="https://img.shields.io/github/v/release/spsoni/copy-pasta" alt="Release"></a>
  <a href="LICENSE"><img src="https://img.shields.io/badge/license-MIT-blue.svg" alt="License: MIT"></a>
</p>

Copy-Pasta packs a selection of files and folders into one block of plain text that survives Slack, Teams, email,
forums and support tickets, optionally encrypted with a passphrase. The recipient copies the text, sees exactly what
will be written, and gets the same files back. No repository, file-sharing service or account is needed, and nothing
is sent anywhere.

## Products

Every product reads and writes the same text, so you can copy in one and paste in another.

| Product | Status | Folder |
|---|---|---|
| **JetBrains IDEs** (IntelliJ IDEA, PyCharm, WebStorm, GoLand, …) | [Available on the JetBrains Marketplace](https://plugins.jetbrains.com/plugin/36213-copy-pasta) | [`intellij/`](intellij/README.md) |
| **VS Code** (and VSCodium, Cursor via Open VSX) | In development: first release pending | [`vscode/`](vscode/README.md) |
| **macOS Finder** right-click menu | Planned | — |
| **Windows Explorer** right-click menu | Planned | — |

Start with the [JetBrains plugin's README](intellij/README.md) and its [user guide](intellij/docs/user-guide.md).

## Security

Pasting never writes outside the folder you choose, never touches VCS metadata such as `.git/hooks`, and shows a
preview first. Encrypted text uses AES-256-GCM with a PBKDF2-derived key; the passphrase is never stored. See
[SECURITY.md](SECURITY.md) for the threat model and how to report a vulnerability.

## Repository layout

- [`spec/`](spec/) — the clipboard format every product shares ([FORMAT.md](spec/FORMAT.md)), the conformance
  [vectors](spec/vectors/README.md) each one is tested against, and the [feature parity](spec/FEATURES.md) table.
- [`intellij/`](intellij/) — the JetBrains plugin (Kotlin, Gradle).
- [`vscode/`](vscode/) — the VS Code extension (TypeScript, no runtime dependencies), first release pending.
- [`AGENTS.md`](AGENTS.md) — conventions, decisions and lessons for contributors.
- Run `make help` at the root for build, test and release targets.

## License

[MIT](LICENSE)
