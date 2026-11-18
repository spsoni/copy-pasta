# Status board — copy-pasta

> ≤20 lines, always. In-flight + next + blocked. Story detail lives in stories/; releases in each product's CHANGELOG.

## Done
- 001–003: monorepo, format spec + vectors, paste hardening → JetBrains 2.1.1 (2026-11-17, see stories/).

## Now
- 004 vscode-core: format in TypeScript, zero runtime deps, vectors + interop CI (in progress)

## Next
1. 005 vscode-extension: commands, preview, status bar, packaging, release; host-neutral envelope wording
2. 006 Rust CLI `copy-pasta copy | paste`, passing the vectors
3. 007 macOS Finder integration (Quick Action first) · 008 Windows Explorer menu
4. 009 public website on GitHub Pages (owner's request, 2026-11-17)

## Blocked / decisions needed
- VS Code Marketplace publisher `spsoni` + PAT (`VSCE_PAT`), Open VSX namespace + token (`OVSX_PAT`): before 005 ships.
- macOS signing/notarization (Apple Developer account) and Windows signing, before 007/008 ship.
