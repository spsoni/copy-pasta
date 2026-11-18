# Status board — copy-pasta

> ≤20 lines, always. In-flight + next + blocked. Story detail lives in stories/; releases in each product's CHANGELOG.

## Done
- 001–003: monorepo, format spec + vectors, paste hardening → JetBrains 2.1.1 (2026-11-17, see stories/).

## Now
- 004–005 VS Code extension released: `spsoni.copy-pasta-files` 0.1.2 on the Marketplace (2026-11-18)

## Next
1. JetBrains 2.1.2 (wording, ZIP caps); Open VSX (`OVSX_PAT`); PR #17 (tools' minimatch)
2. 006 Rust CLI `copy-pasta copy | paste`, passing the vectors
3. 007 macOS Finder integration (Quick Action first) · 008 Windows Explorer menu
4. 009 public website on GitHub Pages (owner's request, 2026-11-17)

## Blocked / decisions needed
- Optional: Open VSX namespace `spsoni` + token (`OVSX_PAT`) for VSCodium/Cursor users.
- macOS signing/notarization (Apple Developer account) and Windows signing, before 007/008 ship.
