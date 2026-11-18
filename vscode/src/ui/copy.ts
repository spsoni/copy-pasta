import { existsSync, statSync } from "node:fs";
import { dirname, join } from "node:path";
import * as vscode from "vscode";
import { crypto, fileMode, pathRules, zip, type FileEntry } from "../format";

/** Limits that keep the text pasteable everywhere (spec/FORMAT.md §6 and the readers' §4.2 limits). */
export const MAX_FILE_BYTES = 10 * 1024 * 1024;
export const MAX_TOTAL_BYTES = 50 * 1024 * 1024;
export const MAX_FILES = 10_000;
/** Directory entries looked at while walking, so a huge excluded tree can't make Copy run forever. */
const MAX_WALKED = 200_000;
/** Longer than many chat apps accept (UTF-16 code units, like the plugin). */
export const LONG_TEXT_CHARS = 35_000;

export class CopyTooLargeError extends Error {}
export class CopyPathError extends Error {}

export interface Collected {
  files: FileEntry[];
  totalBytes: number;
  skippedIgnored: number;
  skippedLinks: number;
  skippedLarge: string[];
  /** Folders inside a Git work tree whose ignore rules couldn't be applied (repository not open, or git failed). */
  gitRulesMissing: string[];
}

interface GitRepository {
  checkIgnore(paths: string[]): Promise<Set<string>>;
}
interface GitApi {
  state: "uninitialized" | "initialized";
  onDidChangeState(listener: (state: string) => void): vscode.Disposable;
  getRepository(uri: vscode.Uri): GitRepository | null;
}

/** The built-in Git extension's API once it has finished scanning (or after a short wait), if it's enabled. */
async function gitApi(): Promise<GitApi | undefined> {
  const extension = vscode.extensions.getExtension<{ getAPI(version: 1): GitApi }>("vscode.git");
  if (!extension) return undefined;
  try {
    const api = (extension.isActive ? extension.exports : await extension.activate()).getAPI(1);
    if (api.state !== "initialized") {
      await new Promise<void>((resolve) => {
        const timer = setTimeout(done, 5000);
        const listener = api.onDidChangeState((state) => state === "initialized" && done());
        function done() {
          clearTimeout(timer);
          listener.dispose();
          resolve();
        }
      });
    }
    return api;
  } catch {
    return undefined;
  }
}

/** True when [uri] (a local folder or file) is inside a Git work tree: a `.git` at or above it. */
function insideGitWorkTree(uri: vscode.Uri): boolean {
  if (uri.scheme !== "file") return false;
  let dir = uri.fsPath;
  for (;;) {
    if (existsSync(join(dir, ".git"))) return true;
    const parent = dirname(dir);
    if (parent === dir) return false;
    dir = parent;
  }
}

/** A key for matching URIs from different VS Code APIs: NFC, and case-folded where the file system usually is. */
function uriKey(uri: vscode.Uri): string {
  const path = uri.fsPath.normalize("NFC");
  return process.platform === "win32" ? path.toLowerCase() : path;
}

/** Drops roots inside other roots, so nested selections are copied once. */
function outermost(roots: vscode.Uri[]): vscode.Uri[] {
  const unique = [...new Map(roots.map((root) => [root.toString(), root])).values()];
  return unique.filter((root) => !unique.some((other) => other !== root && other.scheme === root.scheme && root.path.startsWith(other.path.replace(/\/?$/, "/"))));
}

function segments(uri: vscode.Uri): string[] {
  return uri.path.split("/").filter((s) => s.length > 0);
}

/**
 * Archive paths start at the deepest folder containing every root's parent (spec §4.1). When that would be a file
 * system root (`/`, or a drive like `C:`), which would leak the folder layout or produce a path every reader rejects,
 * each root contributes just its own name instead.
 */
function pathMapper(roots: vscode.Uri[]): (uri: vscode.Uri) => string {
  const parents = roots.map((root) => segments(root).slice(0, -1));
  const first = parents[0] ?? [];
  let length = first.length;
  for (const parent of parents) {
    let i = 0;
    while (i < length && i < parent.length && parent[i] === first[i] && roots[0]?.scheme === roots[parents.indexOf(parent)]?.scheme) i++;
    length = i;
  }
  const atFileSystemRoot = length === 0 || (length === 1 && /^[A-Za-z]:$/.test(first[0] ?? ""));
  if (!atFileSystemRoot) return (uri) => segments(uri).slice(length).join("/");
  const names = roots.map((root) => segments(root).pop() ?? "");
  if (new Set(names.map((n) => n.toLowerCase())).size !== names.length) {
    throw new CopyPathError("the selected items have the same name but no common folder; copy them one at a time");
  }
  return (uri) => {
    const root = roots.find((r) => r.scheme === uri.scheme && (uri.path === r.path || uri.path.startsWith(r.path.replace(/\/?$/, "/")))) as vscode.Uri;
    return [segments(root).pop() ?? "", ...segments(uri).slice(segments(root).length)].join("/");
  };
}

/**
 * Collects [selection] like the JetBrains plugin does:
 * - symbolic links are never followed or stored;
 * - VCS metadata (`.git` and lookalikes) is never copied: paste would refuse it, and it can hold credentials;
 * - folders and files ignored by Git, or hidden by `files.exclude`, are skipped, unless the user selected that item
 *   itself;
 * - unsaved editor content is used; files over 10 MB are skipped; more than 50 MB or 10,000 files is refused.
 *
 * The walk prunes ignored folders level by level (one `git check-ignore` per level), so `node_modules` is never read.
 */
export async function collect(selection: vscode.Uri[], token?: vscode.CancellationToken): Promise<Collected> {
  const roots = outermost(selection);
  const result: Collected = { files: [], totalBytes: 0, skippedIgnored: 0, skippedLinks: 0, skippedLarge: [], gitRulesMissing: [] };
  if (roots.length === 0) return result;
  const toPath = pathMapper(roots);
  const dirty = new Map(vscode.workspace.textDocuments.filter((doc) => doc.isDirty).map((doc) => [uriKey(doc.uri), doc.getText()]));
  const git = await gitApi();
  const kept: vscode.Uri[] = [];
  let walked = 0;
  const cancelled = () => {
    if (token?.isCancellationRequested) throw new vscode.CancellationError();
  };

  for (const root of roots) {
    cancelled();
    const type = (await vscode.workspace.fs.stat(root)).type;
    if (type & vscode.FileType.SymbolicLink) {
      result.skippedLinks++;
      continue;
    }
    if (!(type & vscode.FileType.Directory)) {
      kept.push(root); // a file the user selected is always copied (VCS metadata aside, checked below)
      continue;
    }

    const repository = git && root.scheme === "file" ? git.getRepository(root) : null;
    if (!repository && insideGitWorkTree(root)) result.gitRulesMissing.push(root.fsPath);
    const ignoredBy = async (uris: vscode.Uri[]): Promise<Set<string>> => {
      if (!repository || uris.length === 0) return new Set();
      try {
        return new Set([...(await repository.checkIgnore(uris.map((u) => u.fsPath)))].map((p) => p.normalize("NFC")));
      } catch {
        if (!result.gitRulesMissing.includes(root.fsPath)) result.gitRulesMissing.push(root.fsPath);
        return new Set();
      }
    };
    // A folder the user selected although Git ignores it is copied in full, like the plugin does.
    const applyGit = !(await ignoredBy([root])).has(root.fsPath.normalize("NFC"));

    // files.exclude: workspace search applies it, so a file it doesn't return is hidden. Outside the workspace (or for
    // more files than the search returns) there's no reliable answer, so nothing is filtered there.
    let visible: Set<string> | undefined;
    if (vscode.workspace.getWorkspaceFolder(root)) {
      const found = await vscode.workspace.findFiles(new vscode.RelativePattern(root, "**/*"), undefined, MAX_WALKED, token);
      if (found.length < MAX_WALKED) visible = new Set(found.map(uriKey));
    }

    let level: vscode.Uri[] = [root];
    while (level.length > 0) {
      cancelled();
      const nextLevel: vscode.Uri[] = [];
      const files: vscode.Uri[] = [];
      for (const dir of level) {
        for (const [name, kind] of await vscode.workspace.fs.readDirectory(dir)) {
          if (++walked > MAX_WALKED) throw new CopyTooLargeError(`more than ${MAX_WALKED.toLocaleString()} items to look through`);
          if (pathRules.isVcsMetadata(name)) continue;
          const child = vscode.Uri.joinPath(dir, name);
          if (kind & vscode.FileType.SymbolicLink) result.skippedLinks++;
          else if (kind & vscode.FileType.Directory) nextLevel.push(child);
          else if (kind & vscode.FileType.File) files.push(child);
        }
      }
      const ignored = applyGit ? await ignoredBy([...nextLevel, ...files]) : new Set<string>();
      const isIgnored = (uri: vscode.Uri) => ignored.has(uri.fsPath.normalize("NFC"));
      for (const file of files) {
        if (isIgnored(file) || (visible !== undefined && !visible.has(uriKey(file)))) result.skippedIgnored++;
        else kept.push(file);
        if (kept.length > MAX_FILES) throw new CopyTooLargeError(`more than ${MAX_FILES.toLocaleString()} files`);
      }
      level = nextLevel.filter((dir) => {
        if (!isIgnored(dir)) return true;
        result.skippedIgnored++;
        return false;
      });
    }
  }

  for (const uri of kept) {
    cancelled();
    const path = toPath(uri);
    try {
      zip.safePath(path); // never send a path the recipient's reader would reject
    } catch {
      throw new CopyPathError(`can't copy ${path}: its name or location can't be represented safely`);
    }
    if (path.split("/").some(pathRules.isVcsMetadata)) continue; // also for explicitly selected items
    const unsaved = dirty.get(uriKey(uri));
    let bytes: Buffer;
    if (unsaved !== undefined) {
      bytes = Buffer.from(unsaved, "utf8");
    } else {
      if ((await vscode.workspace.fs.stat(uri)).size > MAX_FILE_BYTES) {
        result.skippedLarge.push(path);
        continue;
      }
      bytes = Buffer.from(await vscode.workspace.fs.readFile(uri));
    }
    if (bytes.length > MAX_FILE_BYTES) {
      result.skippedLarge.push(path);
      continue;
    }
    result.totalBytes += bytes.length;
    if (result.totalBytes > MAX_TOTAL_BYTES) throw new CopyTooLargeError("more than 50 MB");
    let mode: number | null = null;
    if (uri.scheme === "file" && process.platform !== "win32") {
      try {
        mode = statSync(uri.fsPath).mode & fileMode.PERMISSION_BITS;
      } catch {
        mode = null;
      }
    }
    result.files.push({ path, bytes, mode, executable: mode !== null && fileMode.isOwnerExecutable(mode) });
  }
  return result;
}

/** Asks for a new passphrase twice. Undefined when cancelled. */
export async function askNewPassphrase(): Promise<string | undefined> {
  const validate = (value: string) =>
    value.length === 0 ? "Enter a passphrase" : crypto.isWellFormed(value) ? undefined : "The passphrase contains an incomplete character. Retype it instead of pasting it";
  const first = await vscode.window.showInputBox({
    title: "Copy Encrypted",
    prompt: "Passphrase. Share it with the recipient separately (not in the same chat). It is never stored.",
    password: true,
    ignoreFocusOut: true,
    validateInput: validate,
  });
  if (first === undefined) return undefined;
  const second = await vscode.window.showInputBox({
    title: "Copy Encrypted",
    prompt: "Confirm the passphrase",
    password: true,
    ignoreFocusOut: true,
    validateInput: (value) => (value === first ? undefined : "The passphrases do not match"),
  });
  return second === undefined ? undefined : first;
}
