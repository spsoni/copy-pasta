# Copy-Pasta for VS Code

**Send files and folders through any chat, and get them back as real files on the other side.**

Copy-Pasta packs your selection into a single block of text for Slack, Teams, email, a forum or a support ticket.
The recipient copies that text and pastes it into VS Code or a JetBrains IDE, where it becomes the same files again.
They see exactly what will be written first.

No repository, file-sharing service or account is needed. Copy-Pasta works offline and sends no data anywhere.

## How it works

1. **Copy.** In the Explorer, right-click files or folders and choose **Copy-Pasta › Copy**.
2. **Send.** Paste the text into a chat, email or ticket.
3. **Copy it on the other side.** The recipient selects the whole message, from `BEGIN COPY-PASTA` to
   `END COPY-PASTA`, and copies it. A clipboard item in the status bar confirms that the text is ready.
4. **Paste.** Right-click the target folder and choose **Copy-Pasta › Paste…**, review the preview and press Enter.
   Or click the status bar item and choose where to paste.

## Features

- **Works with the JetBrains plugin.** Text copied in VS Code pastes in IntelliJ IDEA, PyCharm, WebStorm and the
  other JetBrains IDEs, and the other way round.
- **Survives chat apps.** Quoting, re-wrapping and changed line endings don't matter. A message that was cut off
  is reported as such, never written half-way.
- **Preview before writing.** Every file is marked new, overwrite or identical, and you can untick any of them.
  The whole paste can be undone with **Edit › Undo** in the Explorer.
- **Optional encryption.** **Copy Encrypted…** protects the text with a passphrase (AES-256-GCM). Share the
  passphrase separately; it is never stored.
- **Respects your ignores.** Files hidden by `files.exclude` or ignored by Git are skipped, unless you select them
  yourself. Unsaved changes in open editors are included.
- **Keeps permissions** on macOS and Linux, so scripts stay runnable and private keys stay private. Pasted files
  are never writable by others.
- **Long selections:** when the text is longer than chat apps accept, **Save as File…** saves it to send as an
  attachment, and the recipient uses **Copy-Pasta: Paste from File…**.

## Safety

Pasted text comes from someone else, so Copy-Pasta treats it as untrusted:

- nothing is written outside the folder you choose;
- nothing is written through a symbolic link already in that folder;
- nothing is written into version-control metadata such as `.git/hooks`, under any spelling;
- archives that would expand beyond 200 MB are refused;
- the extension has no third-party runtime code at all.

The format is documented in
[spec/FORMAT.md](https://github.com/spsoni/copy-pasta/blob/main/spec/FORMAT.md). Report vulnerabilities privately,
as described in [SECURITY.md](https://github.com/spsoni/copy-pasta/blob/main/SECURITY.md).

## Commands

| Command | Where |
|---|---|
| Copy-Pasta: Copy | Explorer context menu, Command Palette |
| Copy-Pasta: Copy Encrypted… | Explorer context menu, Command Palette |
| Copy-Pasta: Paste… | Explorer context menu (folders), Command Palette, status bar |
| Copy-Pasta: Paste from File… | Explorer context menu (folders), Command Palette |
| Copy-Pasta: Save Clipboard as File… | Command Palette, status bar |
| Copy-Pasta: Clear from Clipboard | Command Palette, status bar |

## License

[MIT](LICENSE)
