import * as vscode from "vscode";
import { CopyPastaError } from "../format";

/**
 * User-facing text, kept in one place so wording matches the JetBrains plugin and the spec (§7).
 *
 * Security: VS Code renders `[text](link)` in notifications as a link and opens `command:` links, so text that came
 * from a pasted archive (file names, decoder messages) never goes into a notification. It goes to the output channel,
 * which shows plain text.
 */
let output: vscode.OutputChannel | undefined;
export function log(line: string): void {
  output ??= vscode.window.createOutputChannel("Copy-Pasta");
  output.appendLine(`[${new Date().toISOString()}] ${line}`);
}

export function showLog(): void {
  output?.show(true);
}

export function describeError(error: unknown): string {
  if (!(error instanceof CopyPastaError)) {
    log(`Error: ${error instanceof Error ? (error.stack ?? error.message) : String(error)}`);
    return "Copy-Pasta failed. Details are in the Copy-Pasta output (View › Output).";
  }
  switch (error.category) {
    case "NOT_FOUND":
      return "The clipboard doesn't contain Copy-Pasta text. Copy the whole message, from BEGIN COPY-PASTA to END COPY-PASTA.";
    case "INCOMPLETE":
      return error.expected > 0
        ? `The text was cut off: ${error.received.toLocaleString()} of ${error.expected.toLocaleString()} characters arrived. Ask the sender to send it as a file, or copy the whole message again.`
        : "The text was cut off: the END COPY-PASTA line is missing. Copy the whole message again.";
    case "CORRUPTED":
      return "The Copy-Pasta text is damaged. Copy the whole message again, or ask the sender to save it as a file.";
    case "NEWER_VERSION":
      return "This text was made by a newer version of Copy-Pasta. Update the extension to paste it.";
    case "WRONG_PASSPHRASE":
      return "Wrong passphrase, or the text was changed after it was encrypted.";
    case "INVALID_ARCHIVE":
      log(`Invalid archive: ${error.message}`); // the detail may contain names chosen by the sender
      return "The Copy-Pasta text contains an unsafe or invalid archive, so nothing was written. Details are in the Copy-Pasta output.";
  }
}

const ZERO_WIDTH_SPACE = String.fromCodePoint(0x200b);

/**
 * Shows untrusted text literally in a QuickPick: `$(name)` would render as an icon, and bidirectional overrides,
 * zero-width and other invisible characters could disguise a name (`gpj.exe` shown as `exe.jpg`). Those are shown
 * as visible `<U+202E>` escapes.
 */
export function literal(text: string): string {
  return text
    .replace(/[\p{Cc}\p{Cf}\p{Zl}\p{Zp}]/gu, (char) => `<U+${(char.codePointAt(0) as number).toString(16).toUpperCase().padStart(4, "0")}>`)
    .replace(/\$\(/g, "$" + ZERO_WIDTH_SPACE + "(");
}

export function formatSize(bytes: number): string {
  if (bytes < 1024) return `${bytes} bytes`;
  const units = ["KB", "MB", "GB"];
  let value = bytes / 1024;
  let unit = 0;
  while (value >= 1024 && unit < units.length - 1) {
    value /= 1024;
    unit++;
  }
  return `${value.toFixed(1)} ${units[unit]}`;
}

/** "3 files, 4.1 KB": the envelope's summary line (spec §2.1 forbids `. ` inside it, which this never produces). */
export function summary(count: number, bytes: number): string {
  return `${count} ${count === 1 ? "file" : "files"}, ${formatSize(bytes)}`;
}

/** `copy-pasta-yyyy-MM-dd-HHmmss.txt` in local time, as the JetBrains plugin names saved files. */
export function savedFileName(now: Date = new Date()): string {
  const pad = (n: number) => String(n).padStart(2, "0");
  return `copy-pasta-${now.getFullYear()}-${pad(now.getMonth() + 1)}-${pad(now.getDate())}-${pad(now.getHours())}${pad(now.getMinutes())}${pad(now.getSeconds())}.txt`;
}
