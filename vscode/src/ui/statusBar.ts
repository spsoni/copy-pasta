import * as vscode from "vscode";
import { envelope } from "../format";

/**
 * The clipboard indicator (like the plugin's status bar icon): shown while the clipboard holds Copy-Pasta text.
 * VS Code has no clipboard-change event, so it refreshes when the window gains focus and after our own copy and
 * paste. No polling. The clipboard is never changed on our own; "copied" and "pasted" are fingerprints, not copies.
 */
type State = "none" | "ready" | "copied" | "pasted";

/** Identifies clipboard text without keeping it: length plus a hash of its head and tail. */
function fingerprint(text: string): string {
  let hash = 0x811c9dc5;
  const sample = text.length > 70_000 ? text.slice(0, 65_536) + text.slice(-4_096) : text;
  for (let i = 0; i < sample.length; i++) hash = Math.imul(hash ^ sample.charCodeAt(i), 0x01000193) >>> 0;
  return `${text.length}:${hash.toString(16)}`;
}

export class ClipboardIndicator implements vscode.Disposable {
  private readonly item = vscode.window.createStatusBarItem("copyPasta.clipboard", vscode.StatusBarAlignment.Right, 100);
  private copied: string | undefined;
  private pasted: string | undefined;
  state: State = "none";

  constructor() {
    this.item.name = "Copy-Pasta clipboard";
    this.item.command = "copyPasta.statusMenu";
  }

  markCopied(text: string): void {
    this.copied = fingerprint(text);
    void this.refresh();
  }

  markPasted(text: string): void {
    this.pasted = fingerprint(text);
    void this.refresh();
  }

  async refresh(): Promise<void> {
    let text: string;
    try {
      text = await vscode.env.clipboard.readText();
    } catch {
      return;
    }
    const peek = envelope.peek(text);
    if (!peek) {
      this.state = "none";
      this.item.hide();
      return;
    }
    const print = fingerprint(text);
    this.state = print === this.pasted ? "pasted" : print === this.copied ? "copied" : "ready";
    // The summary is sanitised by peek (letters, digits, spaces, ",.-"), so it can't contain $(icon) syntax.
    const what = peek.summary ?? "Copy-Pasta text";
    const lock = peek.encrypted ? "$(lock) " : "";
    this.item.text = `$(clippy) ${lock}${what}${this.state === "pasted" ? " (pasted)" : this.state === "copied" ? " (copied)" : ""}`;
    this.item.tooltip =
      this.state === "copied"
        ? "Copy-Pasta text you copied is on the clipboard. Paste it into a chat, email or ticket."
        : this.state === "pasted"
          ? "The Copy-Pasta text on the clipboard has been pasted. Click for options."
          : "Copy-Pasta text is on the clipboard, ready to paste. Click to choose where.";
    this.item.show();
  }

  dispose(): void {
    this.item.dispose();
  }
}
