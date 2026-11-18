import { lstatSync, readFileSync, type Stats } from "node:fs";
import { join } from "node:path";
import { OWNER_EXECUTE, applied } from "./fileMode";
import { isUnsafeOnWindows, isVcsMetadata, sameFileKey } from "./pathRules";
import type { FileEntry } from "./zip";

/**
 * The paste plan (spec/FORMAT.md §5.1) on the real file system. Nothing is followed: every existing component is
 * examined with `lstat`.
 */
export type PasteStatus = "NEW" | "OVERWRITE" | "IDENTICAL" | "BLOCKED";

export interface PlannedFile {
  entry: FileEntry;
  status: PasteStatus;
}

export interface PlanOptions {
  /** Default: case-insensitive on macOS and Windows, case-sensitive elsewhere. */
  caseSensitive?: boolean;
  /** Default: whether this is Windows. */
  windows?: boolean;
}

const isWindowsHost = process.platform === "win32";

function lstatOrNull(path: string): Stats | null {
  try {
    return lstatSync(path);
  } catch (error) {
    const code = (error as NodeJS.ErrnoException).code;
    if (code === "ENOENT" || code === "ENOTDIR") return null;
    throw error;
  }
}

/** True when a segment of [path] must never be written: VCS metadata anywhere, unsafe names on Windows. */
export function forbiddenName(path: string, windows: boolean): boolean {
  return path.split("/").some((segment) => isVcsMetadata(segment) || (windows && isUnsafeOnWindows(segment)));
}

/**
 * True when an existing component of [path] below [target], or the file itself, is a symbolic link (Node reports
 * Windows junctions as links too) or a special file (FIFO, socket, device). Broken links count. Any other error while
 * looking counts as "don't write".
 */
export function linkOnPath(target: string, path: string, windows: boolean = isWindowsHost): boolean {
  let current = target;
  for (const segment of path.split("/")) {
    current = join(current, segment);
    let stats: Stats | null;
    try {
      stats = lstatOrNull(current);
    } catch {
      return true;
    }
    if (stats === null) return false; // doesn't exist: nothing below it exists either
    if (stats.isSymbolicLink()) return true;
    const special = stats.isFIFO() || stats.isSocket() || stats.isBlockDevice() || stats.isCharacterDevice();
    if (special && !windows) return true;
  }
  return false;
}

/** The mode a paste gives a file whose mode is now [current], or null to leave it (spec §5.3). */
export function targetMode(entry: FileEntry, current: number): number | null {
  if (entry.mode !== null) return applied(entry.mode, current);
  if (entry.executable) return current | OWNER_EXECUTE;
  return null;
}

function statusOf(target: string, entry: FileEntry, windows: boolean): PasteStatus {
  const segments = entry.path.split("/");
  let dir = target;
  for (const segment of segments.slice(0, -1)) {
    dir = join(dir, segment);
    const stats = lstatOrNull(dir);
    if (stats === null) return "NEW";
    if (!stats.isDirectory()) return "BLOCKED"; // a file (or link) sits where a folder is needed
  }
  const file = join(dir, segments[segments.length - 1] as string);
  const stats = lstatOrNull(file);
  if (stats === null) return "NEW";
  if (stats.isDirectory() || stats.isSymbolicLink()) return "BLOCKED";
  if (stats.size === entry.bytes.length && readFileSync(file).equals(entry.bytes)) {
    if (windows) return "IDENTICAL"; // no permissions on Windows
    const current = stats.mode & 0o777;
    const wanted = targetMode(entry, current);
    return wanted === null || wanted === current ? "IDENTICAL" : "OVERWRITE";
  }
  return "OVERWRITE";
}

/**
 * What each entry would do in [target]. BLOCKED: VCS metadata, a symbolic link or special file on the path, a name
 * Windows can't hold safely (on Windows), a duplicate (NFC, and folded on case-insensitive targets), a file where
 * another entry needs a folder, or a clash with an existing file or folder.
 */
export function plan(target: string, files: readonly FileEntry[], options: PlanOptions = {}): PlannedFile[] {
  const caseSensitive = options.caseSensitive ?? !(process.platform === "darwin" || isWindowsHost);
  const windows = options.windows ?? isWindowsHost;
  const key = (path: string) => sameFileKey(path, caseSensitive);
  const folders = new Set<string>();
  for (const file of files) {
    const segments = file.path.split("/");
    for (let i = 1; i < segments.length; i++) folders.add(key(segments.slice(0, i).join("/")));
  }
  const seen = new Set<string>();
  return files.map((entry) => {
    let status: PasteStatus;
    const k = key(entry.path);
    if (forbiddenName(entry.path, windows)) status = "BLOCKED";
    else if (seen.has(k)) status = "BLOCKED";
    else if ((seen.add(k), folders.has(k))) status = "BLOCKED";
    else if (linkOnPath(target, entry.path, windows)) status = "BLOCKED";
    else {
      try {
        status = statusOf(target, entry, windows);
      } catch {
        status = "BLOCKED"; // unreadable or unexpected: never write what we couldn't examine
      }
    }
    return { entry, status };
  });
}

/**
 * The re-check right before writing one entry (spec §5.1): the tree may have changed since the plan. Throws instead
 * of following a link.
 */
export function assertWritable(target: string, path: string, windows: boolean = isWindowsHost): void {
  if (forbiddenName(path, windows) || linkOnPath(target, path, windows)) {
    throw new Error(`refusing to write ${path}: it goes through a symbolic link or a protected name`);
  }
}
