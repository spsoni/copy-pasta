import { chmodSync, lstatSync } from "node:fs";
import * as vscode from "vscode";
import { envelope, fileMode, plan, type FileEntry } from "../format";
import type { PasteStatus, PlannedFile } from "../format/plan";
import { decodeInWorker } from "../format/worker";
import { describeError, formatSize, literal, log, showLog } from "./messages";

/** At most what a saved Copy-Pasta file can sensibly be (the plugin uses the same bound). */
export const MAX_FILE_TEXT_BYTES = 80 * 1024 * 1024;

export interface PasteHooks {
  /** Supplies the passphrase for encrypted text; undefined cancels. */
  passphrase?: () => Promise<string | undefined>;
  /** Chooses which offered entries to write; undefined cancels. Default: the preview QuickPick. */
  choose?: (offered: Offered[], target: vscode.Uri) => Promise<FileEntry[] | undefined>;
}

export interface Offered {
  entry: FileEntry;
  status: PasteStatus;
  /** Why it will never be written, for skipped entries. */
  skipReason?: string;
}

export interface PasteResult {
  written: FileEntry[];
  skipped: string[];
}

export class FolderChangedError extends Error {}

const STATUS_LABEL: Record<PasteStatus, string> = { NEW: "new", OVERWRITE: "overwrite", IDENTICAL: "identical", BLOCKED: "skipped" };

function permissionLabel(entry: FileEntry): string {
  if (entry.mode === null) return entry.executable ? ", executable" : "";
  const appliedMode = fileMode.safe(entry.mode);
  if (appliedMode === fileMode.DEFAULT_MODE) return "";
  if (appliedMode === fileMode.EXECUTABLE_MODE) return ", executable";
  return `, ${fileMode.describe(appliedMode)}`;
}

function uriOf(target: vscode.Uri, entry: FileEntry): vscode.Uri {
  return vscode.Uri.joinPath(target, ...entry.path.split("/"));
}

function dirtyDocument(uri: vscode.Uri): vscode.TextDocument | undefined {
  return vscode.workspace.textDocuments.find((doc) => doc.isDirty && doc.uri.toString() === uri.toString());
}

/**
 * The plan plus what only the editor knows: a file open with unsaved changes is never overwritten (saving it for the
 * user would lose their edits on undo), and every skipped entry gets a reason.
 */
function offer(target: vscode.Uri, planned: PlannedFile[]): Offered[] {
  const windows = process.platform === "win32";
  return planned.map(({ entry, status }) => {
    if (status === "BLOCKED") {
      const reason = plan.forbiddenName(entry.path, windows)
        ? "version-control metadata, or a name this system can't hold safely"
        : plan.linkOnPath(target.fsPath, entry.path, windows)
          ? "it would go through a symbolic link"
          : "it clashes with another entry, or with an existing file or folder";
      return { entry, status, skipReason: reason };
    }
    if (status !== "NEW" && dirtyDocument(uriOf(target, entry))) {
      return { entry, status: "BLOCKED", skipReason: "it's open with unsaved changes: save or revert it, then paste again" };
    }
    return { entry, status };
  });
}

/** The preview (spec §5.1): every entry with its status. Identical ones start unticked; skipped ones are listed below. */
async function preview(offered: Offered[], target: vscode.Uri): Promise<FileEntry[] | undefined> {
  const count = (status: PasteStatus) => offered.filter((o) => o.status === status).length;
  type Item = vscode.QuickPickItem & { entry?: FileEntry };
  const items: Item[] = offered
    .filter((o) => o.status !== "BLOCKED")
    .map((o) => ({
      label: literal(o.entry.path),
      description: `${STATUS_LABEL[o.status]} · ${formatSize(o.entry.bytes.length)}${permissionLabel(o.entry)}`,
      picked: o.status !== "IDENTICAL",
      entry: o.entry,
    }));
  const skipped = offered.filter((o) => o.status === "BLOCKED");
  if (skipped.length > 0) {
    items.push({ label: "Never written", kind: vscode.QuickPickItemKind.Separator });
    for (const o of skipped) items.push({ label: `$(circle-slash) ${literal(o.entry.path)}`, description: `skipped: ${o.skipReason ?? ""}` });
  }
  const folder = target.path.split("/").pop() || target.fsPath;
  const chosen = await vscode.window.showQuickPick(items, {
    title: `Paste into ${literal(folder)}`,
    placeHolder:
      `${count("NEW")} new, ${count("OVERWRITE")} overwrite, ${count("IDENTICAL")} identical` +
      (skipped.length > 0 ? `, ${skipped.length} skipped` : "") +
      ". Untick anything you don't want, then press Enter.",
    canPickMany: true,
    ignoreFocusOut: true,
    matchOnDescription: true,
  });
  return chosen?.flatMap((item) => (item.entry ? [item.entry] : []));
}

/**
 * Writes [entries] into [target] (spec §5.2): the plan is made again first, and if anything changed since the preview
 * nothing is written. Each path is checked for links right before the edit. One WorkspaceEdit makes it undoable: an
 * existing file is deleted (which keeps its content for undo) and created again, because VS Code's undo of an
 * overwriting create just deletes the file.
 */
async function write(target: vscode.Uri, entries: Offered[]): Promise<void> {
  const windows = process.platform === "win32";
  const replanned = offer(target, plan.plan(target.fsPath, entries.map((o) => o.entry)));
  if (replanned.some((now, i) => now.status !== entries[i]?.status)) throw new FolderChangedError();

  const edit = new vscode.WorkspaceEdit();
  const before = new Map<string, number>();
  for (const { entry, status } of entries) {
    plan.assertWritable(target.fsPath, entry.path, windows);
    const uri = uriOf(target, entry);
    if (status === "OVERWRITE") {
      if (!windows) before.set(entry.path, lstatSync(uri.fsPath).mode & fileMode.PERMISSION_BITS);
      edit.deleteFile(uri, { recursive: false });
    }
    edit.createFile(uri, { contents: entry.bytes });
  }
  if (!(await vscode.workspace.applyEdit(edit))) throw new Error("VS Code refused to write the files");

  if (windows) return; // permissions are neither recorded nor applied on Windows
  for (const { entry } of entries) {
    const path = uriOf(target, entry).fsPath;
    try {
      const now = lstatSync(path);
      if (now.isSymbolicLink() || !now.isFile()) continue; // chmod would follow it
      const current = before.get(entry.path) ?? now.mode & fileMode.PERMISSION_BITS;
      const wanted = plan.targetMode(entry, current);
      if (wanted !== null) chmodSync(path, wanted);
    } catch (error) {
      log(`Could not set permissions on ${entry.path}: ${(error as Error).message}`); // the content is written
    }
  }
}

/** Decode → plan → choose → write. Throws CopyPastaError for undecodable text; returns undefined when cancelled. */
export async function pasteText(text: string, target: vscode.Uri, hooks: PasteHooks = {}): Promise<PasteResult | undefined> {
  if (target.scheme !== "file") throw new Error("Copy-Pasta can only paste into a folder on this computer.");
  let passphrase: string | undefined;
  if (envelope.isEncrypted(text)) {
    passphrase = await (hooks.passphrase ?? askPassphrase)();
    if (passphrase === undefined) return undefined;
  }
  let files: FileEntry[];
  try {
    files = await vscode.window.withProgress(
      { location: vscode.ProgressLocation.Notification, title: "Copy-Pasta: decoding…", cancellable: true },
      async (_progress, token) => {
        const abort = new AbortController();
        const listener = token.onCancellationRequested(() => abort.abort());
        try {
          return await decodeInWorker(text, passphrase, abort.signal);
        } finally {
          listener.dispose();
        }
      },
    );
  } catch (error) {
    if (error instanceof Error && error.message === "cancelled") return undefined;
    throw error;
  }
  const offered = offer(target, plan.plan(target.fsPath, files));
  const skipped = offered.filter((o) => o.status === "BLOCKED");
  for (const o of skipped) log(`Skipped ${o.entry.path}: ${o.skipReason ?? ""}`);
  const chosen = await (hooks.choose ?? preview)(offered, target);
  if (chosen === undefined) return undefined;
  const picked = new Set(chosen);
  const toWrite = offered.filter((o) => o.status !== "BLOCKED" && picked.has(o.entry));
  if (toWrite.length > 0) await write(target, toWrite);
  return { written: toWrite.map((o) => o.entry), skipped: skipped.map((o) => o.entry.path) };
}

export async function askPassphrase(): Promise<string | undefined> {
  return vscode.window.showInputBox({
    title: "Encrypted Copy-Pasta Text",
    prompt: "Passphrase",
    password: true,
    ignoreFocusOut: true,
    validateInput: (value) => (value.length === 0 ? "Enter a passphrase" : undefined),
  });
}

/** The folder to paste into: the Explorer selection (or its folder), else the workspace root. */
export async function resolveTarget(clicked?: vscode.Uri): Promise<vscode.Uri | undefined> {
  if (clicked) {
    const type = (await vscode.workspace.fs.stat(clicked)).type;
    return type & vscode.FileType.Directory ? clicked : vscode.Uri.joinPath(clicked, "..");
  }
  const folders = vscode.workspace.workspaceFolders ?? [];
  if (folders.length === 1) return (folders[0] as vscode.WorkspaceFolder).uri;
  if (folders.length > 1) return (await vscode.window.showWorkspaceFolderPick({ placeHolder: "Paste into which folder?" }))?.uri;
  return chooseFolder();
}

export async function chooseFolder(): Promise<vscode.Uri | undefined> {
  const picked = await vscode.window.showOpenDialog({ canSelectFolders: true, canSelectFiles: false, canSelectMany: false, openLabel: "Paste Here" });
  return picked?.[0];
}

/** Runs a paste with the user-facing messages. No text from the archive ever goes into a notification. */
export async function pasteWithMessages(target: vscode.Uri, text: string, onPasted: (text: string) => void): Promise<void> {
  if (!envelope.looksLikeEnvelope(text)) {
    const choice = await vscode.window.showInformationMessage(
      "The clipboard doesn't contain Copy-Pasta text. Copy the whole message you received, from BEGIN COPY-PASTA to END COPY-PASTA, or open a saved file.",
      "Paste from File…",
    );
    if (choice) await pasteFromFile(target, onPasted);
    return;
  }
  try {
    const result = await pasteText(text, target);
    if (!result) return;
    if (result.written.length === 0) {
      const choice = await vscode.window.showInformationMessage(result.skipped.length > 0 ? "Nothing was pasted: every file was skipped." : "Nothing was pasted.", ...(result.skipped.length > 0 ? ["Show Details"] : []));
      if (choice) showLog();
      return;
    }
    onPasted(text);
    const files = `${result.written.length} ${result.written.length === 1 ? "file" : "files"}`;
    const skipped = result.skipped.length > 0 ? ` ${result.skipped.length} skipped (see the Copy-Pasta output).` : "";
    const choice = await vscode.window.showInformationMessage(`Pasted ${files}.${skipped} Edit › Undo in the Explorer reverts the paste.`, "Reveal in Explorer");
    if (choice) await vscode.commands.executeCommand("revealInExplorer", uriOf(target, result.written[0] as FileEntry));
  } catch (error) {
    if (error instanceof FolderChangedError) {
      void vscode.window.showWarningMessage("The folder changed while the preview was open, so nothing was written. Paste again to see the current state.");
      return;
    }
    const message = describeError(error);
    const category = (error as { category?: string }).category;
    if (category === "INCOMPLETE" || category === "CORRUPTED") {
      if (await vscode.window.showErrorMessage(message, "Paste from File…")) await pasteFromFile(target, onPasted);
    } else {
      void vscode.window.showErrorMessage(message);
    }
  }
}

/** The fallback for text too long for the clipboard: a file saved by **Save as File…**. */
export async function pasteFromFile(target: vscode.Uri | undefined, onPasted: (text: string) => void): Promise<void> {
  const files = await vscode.window.showOpenDialog({ canSelectFiles: true, canSelectMany: false, filters: { "Copy-Pasta text": ["txt"], "All files": ["*"] }, openLabel: "Paste" });
  const file = files?.[0];
  if (!file) return;
  if ((await vscode.workspace.fs.stat(file)).size > MAX_FILE_TEXT_BYTES) {
    void vscode.window.showErrorMessage("That file is too large to be Copy-Pasta text.");
    return;
  }
  const text = Buffer.from(await vscode.workspace.fs.readFile(file)).toString("utf8");
  const into = target ?? (await resolveTarget());
  if (into) await pasteWithMessages(into, text, onPasted);
}
