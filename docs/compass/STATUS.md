# Status board — copy-pasta

> ≤20 lines, always. In-flight + next + blocked. Story detail lives in stories/; releases in each product's CHANGELOG.

## Done
- 001–003: monorepo, format spec + vectors, paste hardening → JetBrains 2.1.1 (2026-11-17, see stories/).

## Now
- 004–005 VS Code extension done on PR #12 (CI green, owner desktop check passed); release waits on the Marketplace trusted-publishing policy

## Next
1. First VS Code release (vscode-v0.1.0) once the owner has the publisher accounts; JetBrains 2.1.2 (wording, ZIP caps)
2. 006 Rust CLI `copy-pasta copy | paste`, passing the vectors
3. 007 macOS Finder integration (Quick Action first) · 008 Windows Explorer menu
4. 009 public website on GitHub Pages (owner's request, 2026-11-17)

## Blocked / decisions needed
- Marketplace trusted-publishing policy: workflow `release-vscode.yml`, environment `vscode-marketplace`. Optional: Open VSX token (`OVSX_PAT`).
- macOS signing/notarization (Apple Developer account) and Windows signing, before 007/008 ship.
