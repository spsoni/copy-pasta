import { deflateRawSync, inflateRawSync } from "node:zlib";
import { crc32 } from "./crc32";
import { invalidArchive } from "./errors";
import { PERMISSION_BITS, isOwnerExecutable } from "./fileMode";

/**
 * The payload archive (spec/FORMAT.md §4), with no third-party code: a standard ZIP written with Node's zlib, and a
 * streaming reader that walks the local headers exactly as the reference (Java's ZipInputStream) does, so crafted
 * archives decode the same way everywhere. Everything here runs on untrusted input.
 */
export interface FileEntry {
  /** Relative, `/`-separated. */
  path: string;
  bytes: Buffer;
  executable: boolean;
  /** The nine permission bits, or null when the sender didn't record them. */
  mode: number | null;
}

export interface Limits {
  /** Files. */
  maxEntries: number;
  /** All local records, directory entries included, so a flood of empty entries can't keep the reader busy. */
  maxRecords: number;
  maxTotalBytes: number;
}

export const DEFAULT_LIMITS: Limits = { maxEntries: 10_000, maxRecords: 20_000, maxTotalBytes: 200 * 1024 * 1024 };

const LOCAL_HEADER = 0x04034b50;
const CENTRAL_HEADER = 0x02014b50;
const END_OF_CENTRAL = 0x06054b50;
const DATA_DESCRIPTOR = 0x08074b50;
const LOCAL_HEADER_BYTES = 30;
const PERMISSIONS_FIELD = 0x4350; // "CP"
const FLAG_ENCRYPTED = 0x0001;
const FLAG_DATA_DESCRIPTOR = 0x0008;
const FLAG_UTF8 = 0x0800;
const STORED = 0;
const DEFLATED = 8;
const ZIP64_MARKER = 0xffffffff;

const utf8 = new TextDecoder("utf-8", { fatal: true, ignoreBOM: true });

// ---------------------------------------------------------------- writing

function dosDateTime(date: Date): { time: number; date: number } {
  const year = Math.min(2107, Math.max(1980, date.getFullYear())); // the DOS date range
  return {
    time: (date.getHours() << 11) | (date.getMinutes() << 5) | Math.floor(date.getSeconds() / 2),
    date: ((year - 1980) << 9) | ((date.getMonth() + 1) << 5) | date.getDate(),
  };
}

/** The `0x4350` field: 3 data bytes when the mode is known, the 2.0 one-byte form for executable-only, else none. */
function permissionsField(file: FileEntry): Buffer {
  const exec = file.executable ? 1 : 0;
  if (file.mode !== null) {
    const mode = file.mode & PERMISSION_BITS;
    return Buffer.from([0x50, 0x43, 0x03, 0x00, exec, mode & 0xff, mode >> 8]);
  }
  return file.executable ? Buffer.from([0x50, 0x43, 0x01, 0x00, 1]) : Buffer.alloc(0);
}

export function encode(files: readonly FileEntry[], now: Date = new Date()): Buffer {
  const { time, date } = dosDateTime(now);
  const parts: Buffer[] = [];
  const central: Buffer[] = [];
  let offset = 0;
  for (const file of files) {
    const name = Buffer.from(file.path, "utf8");
    const extra = permissionsField(file);
    const data = deflateRawSync(file.bytes, { level: 9 });
    const crc = crc32(file.bytes);

    const local = Buffer.alloc(LOCAL_HEADER_BYTES);
    local.writeUInt32LE(LOCAL_HEADER, 0);
    local.writeUInt16LE(20, 4); // version needed
    local.writeUInt16LE(FLAG_UTF8, 6);
    local.writeUInt16LE(DEFLATED, 8);
    local.writeUInt16LE(time, 10);
    local.writeUInt16LE(date, 12);
    local.writeUInt32LE(crc, 14);
    local.writeUInt32LE(data.length, 18);
    local.writeUInt32LE(file.bytes.length, 22);
    local.writeUInt16LE(name.length, 26);
    local.writeUInt16LE(extra.length, 28);
    parts.push(local, name, extra, data);

    const header = Buffer.alloc(46);
    header.writeUInt32LE(CENTRAL_HEADER, 0);
    header.writeUInt16LE(20, 4); // version made by (readers ignore it, spec §4.2)
    header.writeUInt16LE(20, 6);
    header.writeUInt16LE(FLAG_UTF8, 8);
    header.writeUInt16LE(DEFLATED, 10);
    header.writeUInt16LE(time, 12);
    header.writeUInt16LE(date, 14);
    header.writeUInt32LE(crc, 16);
    header.writeUInt32LE(data.length, 20);
    header.writeUInt32LE(file.bytes.length, 24);
    header.writeUInt16LE(name.length, 28);
    header.writeUInt16LE(extra.length, 30);
    header.writeUInt32LE(offset, 42); // comment length, disk, attributes stay 0
    central.push(header, name, extra);

    offset += local.length + name.length + extra.length + data.length;
  }
  const centralBytes = Buffer.concat(central);
  const end = Buffer.alloc(22);
  end.writeUInt32LE(END_OF_CENTRAL, 0);
  end.writeUInt16LE(files.length, 8);
  end.writeUInt16LE(files.length, 10);
  end.writeUInt32LE(centralBytes.length, 12);
  end.writeUInt32LE(offset, 16);
  return Buffer.concat([...parts, centralBytes, end]);
}

// ---------------------------------------------------------------- reading

/** Normalises an entry name and rejects anything that could escape the target folder (spec §4.2 step 6). */
export function safePath(name: string): string {
  if (/[\x00-\x1F]/.test(name)) throw invalidArchive("control character in a file name");
  const normalized = name.replace(/\\/g, "/");
  if (normalized.startsWith("/") || /^[A-Za-z]:/.test(normalized)) throw invalidArchive(`absolute path: ${name}`);
  const segments = normalized.split("/").filter((segment) => segment !== "" && segment !== ".");
  if (segments.length === 0 || segments.includes("..")) throw invalidArchive(`unsafe path: ${name}`);
  return segments.join("/");
}

function readPermissions(extra: Buffer): { executable: boolean; mode: number | null } {
  let i = 0;
  while (i + 4 <= extra.length) {
    const id = extra.readUInt16LE(i);
    const size = extra.readUInt16LE(i + 2);
    const data = i + 4;
    if (id === PERMISSIONS_FIELD && size >= 1 && data + size <= extra.length) {
      const executable = extra[data] === 1;
      const mode = size >= 3 ? extra.readUInt16LE(data + 1) & PERMISSION_BITS : null;
      return { executable, mode };
    }
    i = data + size;
  }
  return { executable: false, mode: null };
}

/** Inflates one entry, returning the content and how many compressed bytes it used. */
function inflate(zip: Buffer, start: number, budget: number): { content: Buffer; consumed: number } {
  try {
    const result = inflateRawSync(zip.subarray(start), { info: true, maxOutputLength: Math.max(budget, 1) }) as unknown as {
      buffer: Buffer;
      engine: { bytesWritten: number };
    };
    if (result.buffer.length > budget) throw invalidArchive("archive expands beyond the size limit");
    return { content: result.buffer, consumed: result.engine.bytesWritten };
  } catch (error) {
    if ((error as { code?: string }).code === "ERR_BUFFER_TOO_LARGE") throw invalidArchive("archive expands beyond the size limit");
    if ((error as { category?: string }).category === "INVALID_ARCHIVE") throw error;
    throw invalidArchive(`damaged archive: ${(error as Error).message}`);
  }
}

/**
 * Decodes a payload ZIP (spec §4.2): local headers in order, stopping at the first record that isn't one; the central
 * directory is never read. Throws INVALID_ARCHIVE for anything unsafe, damaged or over the limits.
 */
export function decode(zip: Buffer, limits: Limits = DEFAULT_LIMITS): FileEntry[] {
  if (zip.length < 4 || zip.readUInt32LE(0) !== LOCAL_HEADER) throw invalidArchive("not a ZIP archive");
  const files: FileEntry[] = [];
  const seen = new Set<string>();
  let total = 0;
  let pos = 0;
  let records = 0;
  for (;;) {
    // A header cut short, or a record of another kind (the central directory, junk): the archive ends here.
    if (pos + LOCAL_HEADER_BYTES > zip.length || zip.readUInt32LE(pos) !== LOCAL_HEADER) break;
    const flags = zip.readUInt16LE(pos + 6);
    const method = zip.readUInt16LE(pos + 8);
    const crc = zip.readUInt32LE(pos + 14);
    const compressedSize = zip.readUInt32LE(pos + 18);
    const size = zip.readUInt32LE(pos + 22);
    const nameLength = zip.readUInt16LE(pos + 26);
    const extraLength = zip.readUInt16LE(pos + 28);
    if (flags & FLAG_ENCRYPTED) throw invalidArchive("encrypted ZIP entries are not supported");
    if (method !== STORED && method !== DEFLATED) throw invalidArchive("invalid compression method");
    const hasDescriptor = (flags & FLAG_DATA_DESCRIPTOR) !== 0;
    if (hasDescriptor && method !== DEFLATED) throw invalidArchive("only Deflated entries can have a data descriptor");
    // Zip64 is never needed (spec §4.2 lets readers reject it). A Stored entry's compressed size isn't used, so only
    // its size counts as a marker.
    const zip64 = method === STORED ? size === ZIP64_MARKER : !hasDescriptor && (compressedSize === ZIP64_MARKER || size === ZIP64_MARKER);
    if (zip64) throw invalidArchive("Zip64 is not supported");
    if (++records > limits.maxRecords) throw invalidArchive(`more than ${limits.maxRecords} ZIP records`);

    const nameStart = pos + LOCAL_HEADER_BYTES;
    const extraStart = nameStart + nameLength;
    const dataStart = extraStart + extraLength;
    if (dataStart > zip.length) throw invalidArchive("damaged archive: header cut short");
    let name: string;
    try {
      name = utf8.decode(zip.subarray(nameStart, extraStart));
    } catch {
      throw invalidArchive("a file name is not valid UTF-8");
    }
    const extra = zip.subarray(extraStart, dataStart);
    const isDirectory = name.endsWith("/"); // on the raw name, before backslashes are replaced

    let path: string | null = null;
    if (!isDirectory) {
      if (files.length >= limits.maxEntries) throw invalidArchive(`more than ${limits.maxEntries} files`);
      path = safePath(name);
      if (seen.has(path)) throw invalidArchive(`duplicate entry: ${path}`);
      seen.add(path);
    }

    // Directory entries' data counts against the size limit too, so it can't be used as a decompression bomb.
    const budget = limits.maxTotalBytes - total;
    let content: Buffer;
    let next: number;
    if (method === STORED) {
      // Like the reference, a Stored entry's length is its uncompressed size; the compressed-size field isn't used.
      if (size > budget) throw invalidArchive("archive expands beyond the size limit");
      if (dataStart + size > zip.length) throw invalidArchive("damaged archive: data cut short");
      content = zip.subarray(dataStart, dataStart + size);
      next = dataStart + size;
      // The reference never checks the CRC of an empty Stored entry (it stops reading before comparing).
      if (size > 0 && crc32(content) !== crc) throw invalidArchive("invalid entry CRC");
    } else {
      const inflated = inflate(zip, dataStart, budget);
      content = inflated.content;
      let expectedCrc = crc;
      let expectedCompressed = compressedSize;
      let expectedSize = size;
      next = dataStart + inflated.consumed;
      if (hasDescriptor) {
        // The descriptor's signature is optional; without it, the 12 bytes are CRC and sizes. The reference always
        // reads 16 bytes first, so fewer than 16 left is damage even for an unsigned descriptor.
        if (next + 16 > zip.length) throw invalidArchive("damaged archive: data descriptor cut short");
        const signed = zip.readUInt32LE(next) === DATA_DESCRIPTOR;
        const fields = signed ? next + 4 : next;
        expectedCrc = zip.readUInt32LE(fields);
        expectedCompressed = zip.readUInt32LE(fields + 4);
        expectedSize = zip.readUInt32LE(fields + 8);
        next = fields + 12;
      }
      if (inflated.consumed !== expectedCompressed) throw invalidArchive("invalid entry compressed size");
      if (content.length !== expectedSize) throw invalidArchive("invalid entry size");
      if (crc32(content) !== expectedCrc) throw invalidArchive("invalid entry CRC");
    }
    total += content.length;
    pos = next;

    if (path !== null) {
      const permissions = readPermissions(extra);
      files.push({ path, bytes: Buffer.from(content), executable: permissions.executable, mode: permissions.mode });
    }
  }
  return files;
}

/** A writer-side helper: the executable flag that goes with a known mode. */
export function executableFromMode(mode: number): boolean {
  return isOwnerExecutable(mode);
}
