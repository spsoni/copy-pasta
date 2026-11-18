import { CopyPastaError } from "./errors";

/**
 * The text that goes on the clipboard (spec/FORMAT.md §2). Built to survive chat apps: markers are found anywhere,
 * quote prefixes, CRLF, re-wrapping and collapsed newlines are tolerated, and a declared length detects truncation.
 */
export const FORMAT_VERSION = 2;
export const HOME_URL = "https://github.com/spsoni/copy-pasta";

const LINE_WIDTH = 76;
const PEEK_CHARS = 65_536;
const SUMMARY_SEARCH_CHARS = 400;
const MAX_SUMMARY_CHARS = 60;
const MAX_INT32 = 2_147_483_647;

// `\s` in the spec means exactly this ASCII class; JavaScript's own `\s` is Unicode, so it's spelled out.
const WS = "[\\t\\n\\x0B\\f\\r ]";
// Linear time: a match can only start at the beginning of a dash run (the look-behind), so each run is scanned once.
// The "i" flag without "u" folds ASCII letters only (spec: case-insensitive means ASCII).
const BEGIN = new RegExp(`(?<!-)-{3,}${WS}*BEGIN (COPY-PASTA(?: ENCRYPTED)?)${WS}*-{3,}`, "i");
const END = new RegExp(`(?<!-)-{3,}${WS}*END COPY-PASTA(?: ENCRYPTED)?${WS}*-{3,}`, "i");
const FORMAT_LINE = new RegExp(`Format:${WS}*([0-9]+)${WS}*;${WS}*length:${WS}*([0-9]+)`, "i");
const SUMMARY_LINE = new RegExp(`Copy-Pasta:${WS}*([^\\r\\n]{1,120}?)\\.${WS}+Paste it`);
const QUOTE_PREFIX = /^[\t\n\x0B\f\r >]+/;
const BASE64_LINE = /^[A-Za-z0-9+/=]+$/;
const LINE_BREAK = /\r\n|\n|\r/;
const BASE64_SHAPE = /^[A-Za-z0-9+/]*(?:[A-Za-z0-9+/]{2}==|[A-Za-z0-9+/]{3}=)?$/;

/**
 * "Body whitespace" (spec §2.2): Java's `Character.isWhitespace || isSpaceChar`, which chat apps' Unicode spaces fall
 * under. Wider than WS on purpose.
 */
export function isBodyWhitespace(code: number): boolean {
  return (
    (code >= 0x09 && code <= 0x0d) ||
    (code >= 0x1c && code <= 0x20) ||
    code === 0xa0 ||
    code === 0x1680 ||
    (code >= 0x2000 && code <= 0x200a) ||
    code === 0x2028 ||
    code === 0x2029 ||
    code === 0x202f ||
    code === 0x205f ||
    code === 0x3000
  );
}

// The same set as isBodyWhitespace, as one regex: building the body a character at a time made a 50-million-character
// single-line body (collapsed newlines) take 5 s and 1.85 GB. Escapes, not literal invisible characters.
const BODY_WHITESPACE_RUN = new RegExp("[\\t-\\r\\x1c-\\x20\\xa0\\u1680\\u2000-\\u200a\\u2028\\u2029\\u202f\\u205f\\u3000]+", "g");

export function removeBodyWhitespace(line: string): string {
  return line.replace(BODY_WHITESPACE_RUN, "");
}

function trimBodyWhitespace(line: string): string {
  let start = 0;
  let end = line.length;
  while (start < end && isBodyWhitespace(line.charCodeAt(start))) start++;
  while (end > start && isBodyWhitespace(line.charCodeAt(end - 1))) end--;
  return line.slice(start, end);
}

/** A decimal that fits a signed 32-bit integer, or null (leading zeros are fine). */
function parseInt32(digits: string): number | null {
  const significant = digits.replace(/^0+(?=.)/, "");
  if (significant.length > 10) return null;
  const value = Number(significant);
  return value <= MAX_INT32 ? value : null;
}

/** Standard Base64 as Java's basic decoder reads it (spec §2.2 step 5), or null. */
function decodeBase64(body: string): Buffer | null {
  if (!BASE64_SHAPE.test(body)) return null;
  if (body.endsWith("=") ? body.length % 4 !== 0 : body.length % 4 === 1) return null;
  return Buffer.from(body, "base64"); // validated above: Node no longer gets a chance to skip anything
}

export interface Decoded {
  payload: Buffer;
  encrypted: boolean;
}

export interface Peek {
  encrypted: boolean;
  summary: string | null;
}

/** [summary] is the human-readable part, e.g. "3 files, 4.1 KB" (spec §2.1 restricts what it may contain). */
export function encode(payload: Uint8Array, encrypted: boolean, summary: string): string {
  const body = Buffer.from(payload).toString("base64");
  const label = encrypted ? "COPY-PASTA ENCRYPTED" : "COPY-PASTA";
  const lines = [
    `-----BEGIN ${label}-----`,
    `Copy-Pasta: ${summary}. Paste it with Copy-Pasta for JetBrains IDEs or VS Code: ${HOME_URL}`,
    `Format: ${FORMAT_VERSION}; length: ${body.length}`,
  ];
  for (let i = 0; i < body.length; i += LINE_WIDTH) lines.push(body.slice(i, i + LINE_WIDTH));
  lines.push(`-----END ${label}-----`);
  return lines.join("\n") + "\n";
}

/**
 * What a glance reveals without decoding (spec §2.3): only the first 64k characters are searched, and the summary
 * is untrusted, so it's reduced to letters, digits, spaces and `,.-` and capped.
 */
export function peek(text: string): Peek | null {
  const head = text.length > PEEK_CHARS ? text.slice(0, PEEK_CHARS) : text;
  const begin = BEGIN.exec(head);
  if (!begin) return null;
  const after = begin.index + begin[0].length;
  const window = head.slice(after, Math.min(head.length, after + SUMMARY_SEARCH_CHARS));
  const raw = SUMMARY_LINE.exec(window)?.[1];
  let summary: string | null = null;
  if (raw !== undefined) {
    let kept = "";
    for (const unit of raw.split("")) if (/[\p{L}\p{Nd}]/u.test(unit) || " ,.-".includes(unit)) kept += unit;
    kept = kept.trim().slice(0, MAX_SUMMARY_CHARS);
    summary = kept.length > 0 ? kept : null;
  }
  return { encrypted: /ENCRYPTED/i.test(begin[1] ?? ""), summary };
}

export function looksLikeEnvelope(text: string): boolean {
  return BEGIN.test(text);
}

export function isEncrypted(text: string): boolean {
  return /ENCRYPTED/i.test(BEGIN.exec(text)?.[1] ?? "");
}

/** Finds and decodes the first envelope in [text] (spec §2.2). Throws [CopyPastaError]. */
export function decode(text: string): Decoded {
  const begin = BEGIN.exec(text);
  if (!begin) throw new CopyPastaError("NOT_FOUND");
  const encrypted = /ENCRYPTED/i.test(begin[1] ?? "");
  // The END search starts right after BEGIN's dash run, and that position is the start of the text for the
  // look-behind, so BEGIN's dashes can't start END.
  const rest = text.slice(begin.index + begin[0].length);
  const end = END.exec(rest);
  const inner = end ? rest.slice(0, end.index) : rest;

  const format = FORMAT_LINE.exec(inner);
  let expected = -1;
  let lines: string[];
  if (format) {
    const version = parseInt32(format[1] ?? "");
    if (version === null) throw new CopyPastaError("CORRUPTED");
    if (version > FORMAT_VERSION) throw new CopyPastaError("NEWER_VERSION");
    const length = parseInt32(format[2] ?? "");
    if (length === null) throw new CopyPastaError("CORRUPTED");
    expected = length;
    lines = inner
      .slice(format.index + format[0].length)
      .split(LINE_BREAK)
      .map((line) => line.replace(QUOTE_PREFIX, ""));
  } else {
    // The format line was lost: keep only lines that can be Base64.
    lines = inner
      .split(LINE_BREAK)
      .map((line) => trimBodyWhitespace(line.replace(QUOTE_PREFIX, "")))
      .filter((line) => BASE64_LINE.test(line));
  }
  const body = lines.map(removeBodyWhitespace).join("");

  if (!end) throw new CopyPastaError("INCOMPLETE", undefined, body.length, Math.max(expected, 0));
  if (/[^\x00-\x7F]/.test(body)) throw new CopyPastaError("CORRUPTED");
  if (expected >= 0 && body.length < expected) throw new CopyPastaError("INCOMPLETE", undefined, body.length, expected);
  if (expected >= 0 && body.length !== expected) throw new CopyPastaError("CORRUPTED");
  const payload = decodeBase64(body);
  if (payload === null) throw new CopyPastaError("CORRUPTED");
  return { payload, encrypted };
}
