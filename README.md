# Copy-Pasta

[![JetBrains Marketplace](https://img.shields.io/jetbrains/plugin/v/info.suryasoni.copy_pasta?label=JetBrains%20Marketplace)](https://plugins.jetbrains.com/plugin/XXXXX-copy-pasta)

A convenient IntelliJ-based IDE plugin that lets you easily share code and project structures through the clipboard.


## What is Copy-Pasta?

Copy-Pasta makes it effortless to share code snippets, examples, or entire directories with teammates and the community. The plugin compresses, encodes, and copies content to your clipboard in a format that can be easily shared through chat, email, or forums. Recipients can paste and decode this content to recreate the original files and directory structure.

## Features

- **Copy (Encode)**: Right-click on any file or directory → Copy-Pasta → Copy
  - Compress and encode the selected content to your clipboard
  - Ready to share anywhere text can be pasted

- **Paste (Decode)**: Right-click in your project → Copy-Pasta → Paste
  - Decode and extract the content from your clipboard
  - Recreates the original file/directory structure

- **Optional Encryption**: Protect sensitive content with customizable encryption

- **Configuration Options**: Customize encryption settings through Tools → Copy-Pasta settings

## Usage

### Encoding (Copying) Files and Folders

1. Right-click on any file or directory in your project view
2. Select Copy-Pasta → Copy
3. The selected content is now compressed, encoded, and copied to your clipboard
4. Share the clipboard content with anyone

### Decoding (Pasting) Content

1. Copy the encoded text to your clipboard
2. In your IDE, right-click on the target location in your project
3. Select Copy-Pasta → Paste
4. The content will be decoded and extracted to the selected location

## Settings

Access Copy-Pasta settings through:

File → Settings → Tools → Copy-Pasta

- Enable/disable encryption
- Set a custom encryption key
- Configure other plugin preferences

## Installation

- **JetBrains Marketplace**: Install directly from your IDE's plugin manager
  - Settings → Plugins → Marketplace → Search for "Copy-Pasta"

- **Manual Installation**:
  1. Download the latest plugin ZIP from [Releases](https://github.com/YourUsername/copy-pasta/releases)
  2. In your IDE: Settings → Plugins → ⚙️ → Install Plugin from Disk...

## Feedback and Contributions

Feedback, bug reports, and pull requests are welcome! Feel free to [open an issue](https://github.com/YourUsername/copy-pasta/issues) or submit a pull request.

## License

This project is licensed under the [Apache License](LICENSE).
