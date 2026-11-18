/**
 * Path-segment rules for pasting (spec/FORMAT.md §5.1). Invisible characters are written as code points, never as
 * literals, so an edit can't silently change them.
 */
const VCS_FOLDERS = new Set([".git", ".hg", ".svn", ".bzr", "cvs", "git~1", "hg~1", "svn~1", "bzr~1"]);

/** Code points HFS+ ignores in file names (the CVE-2014-9390 family). */
function isIgnorable(codePoint: number): boolean {
  return (
    (codePoint >= 0x200b && codePoint <= 0x200f) ||
    (codePoint >= 0x202a && codePoint <= 0x202e) ||
    (codePoint >= 0x2060 && codePoint <= 0x206f) ||
    codePoint === 0xfeff
  );
}

/**
 * One code point through Unicode *simple* case mapping. JavaScript only offers full mappings (`ß` → `SS`), so a
 * mapping that yields more than one code point is treated as no mapping, which is what simple mapping does, with the
 * one exception where the simple mapping differs: U+0130 lower-cases to `i`.
 */
function simpleUpper(codePoint: number): number {
  const mapped = String.fromCodePoint(codePoint).toUpperCase();
  return [...mapped].length === 1 ? (mapped.codePointAt(0) as number) : codePoint;
}

function simpleLower(codePoint: number): number {
  if (codePoint === 0x130) return 0x69;
  const mapped = String.fromCodePoint(codePoint).toLowerCase();
  return [...mapped].length === 1 ? (mapped.codePointAt(0) as number) : codePoint;
}

/**
 * Case folding as case-insensitive file systems compare names: upper-case then lower-case each code point, so
 * U+017F `ſ` → `s`, U+0131 `ı` → `i`, U+212A (Kelvin) → `k` (Java's `toLowerCase(toUpperCase(cp))`).
 */
export function fold(segment: string): string {
  let out = "";
  for (const char of segment) out += String.fromCodePoint(simpleLower(simpleUpper(char.codePointAt(0) as number)));
  return out;
}

function trimTrailingDotsAndSpaces(text: string): string {
  return text.replace(/[. ]+$/, "");
}

/** Paths that would land on the same file: NFC (macOS), plus folding on a case-insensitive target. */
export function sameFileKey(path: string, caseSensitive: boolean): string {
  const nfc = path.normalize("NFC");
  return caseSensitive ? nfc : fold(nfc);
}

/** True when [segment] names VCS metadata on some file system (spec §5.1). */
export function isVcsMetadata(segment: string): boolean {
  let visible = "";
  for (const char of segment) if (!isIgnorable(char.codePointAt(0) as number)) visible += char;
  return VCS_FOLDERS.has(fold(trimTrailingDotsAndSpaces(visible).normalize("NFC")));
}

const WINDOWS_RESERVED = new Set([
  "con",
  "prn",
  "aux",
  "nul",
  ...Array.from({ length: 10 }, (_, n) => [`com${n}`, `lpt${n}`]).flat(),
  ...[0xb9, 0xb2, 0xb3].flatMap((superscript) => [`com${String.fromCodePoint(superscript)}`, `lpt${String.fromCodePoint(superscript)}`]),
]);

/** True when [segment] can't be created safely on Windows (spec §5.1). */
export function isUnsafeOnWindows(segment: string): boolean {
  if (/[:<>"|?*]/.test(segment)) return true;
  if (segment.endsWith(".") || segment.endsWith(" ")) return true;
  const base = segment.split(".")[0] ?? "";
  return WINDOWS_RESERVED.has(fold(base.replace(/ +$/, "")));
}
