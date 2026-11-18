import assert from "node:assert/strict";
import { describe, it } from "node:test";
import { CopyPastaError, crypto, decodeText, envelope, fileMode, pathRules, zip, type FileEntry } from "../src/format";
import { decodeInWorker } from "../src/format/worker";

const files: FileEntry[] = [
  { path: "demo/README.md", bytes: Buffer.from("# Demo\n"), executable: false, mode: 0o644 },
  { path: "demo/run.sh", bytes: Buffer.from("#!/bin/sh\n"), executable: true, mode: 0o755 },
  { path: "demo/secret.pem", bytes: Buffer.from("key"), executable: false, mode: 0o600 },
  { path: "demo/empty.txt", bytes: Buffer.alloc(0), executable: false, mode: null },
  { path: "demo/legacy.sh", bytes: Buffer.from("x"), executable: true, mode: null },
  { path: "demo/unicodé/naïve.txt", bytes: Buffer.from("Grüße"), executable: false, mode: 0o644 },
  { path: "demo/bin.dat", bytes: Buffer.from(Array.from({ length: 256 }, (_, i) => i)), executable: false, mode: 0o644 },
];

async function category(promise: Promise<unknown> | (() => unknown)): Promise<string> {
  try {
    await (typeof promise === "function" ? promise() : promise);
    return "no error";
  } catch (error) {
    return error instanceof CopyPastaError ? error.category : String(error);
  }
}

describe("zip", () => {
  it("round-trips paths, content, modes and the executable flag", () => {
    const decoded = zip.decode(zip.encode(files));
    assert.deepEqual(
      decoded.map((f) => [f.path, f.bytes.toString("base64"), f.mode, f.executable]),
      files.map((f) => [f.path, f.bytes.toString("base64"), f.mode, f.executable]),
    );
  });

  it("enforces the entry and size limits", async () => {
    const many = Array.from({ length: 4 }, (_, i): FileEntry => ({ path: `f${i}`, bytes: Buffer.from("x"), executable: false, mode: null }));
    assert.equal(await category(() => zip.decode(zip.encode(many), { maxEntries: 3, maxRecords: 100, maxTotalBytes: 1000 })), "INVALID_ARCHIVE");
    const big: FileEntry[] = [{ path: "big", bytes: Buffer.alloc(2000), executable: false, mode: null }];
    assert.equal(await category(() => zip.decode(zip.encode(big), { maxEntries: 10, maxRecords: 100, maxTotalBytes: 1000 })), "INVALID_ARCHIVE");
  });

  it("caps the number of ZIP records, directory entries included", async () => {
    // Hand-built: four Stored directory entries "d0/".."d3/" (empty), then nothing.
    const records = Array.from({ length: 4 }, (_, i) => {
      const name = Buffer.from(`d${i}/`);
      const header = Buffer.alloc(30);
      header.writeUInt32LE(0x04034b50, 0);
      header.writeUInt16LE(20, 4);
      header.writeUInt16LE(name.length, 26);
      return Buffer.concat([header, name]);
    });
    const archive = Buffer.concat(records);
    assert.deepEqual(zip.decode(archive, { maxEntries: 1, maxRecords: 4, maxTotalBytes: 10 }), []);
    assert.equal(await category(() => zip.decode(archive, { maxEntries: 10, maxRecords: 3, maxTotalBytes: 10 })), "INVALID_ARCHIVE");
  });

  it("rejects unsafe paths", async () => {
    for (const path of ["../x", "a/../../x", "/etc/x", "C:/x", "a\u0001b", "", "./"]) {
      assert.equal(await category(() => zip.safePath(path)), "INVALID_ARCHIVE", JSON.stringify(path));
    }
    assert.equal(zip.safePath("dir\\file.txt"), "dir/file.txt");
    assert.equal(zip.safePath("./a//b"), "a/b");
  });
});

describe("envelope", () => {
  it("round-trips and peeks", () => {
    const payload = zip.encode(files);
    const text = envelope.encode(payload, false, "7 files, 1 KB");
    assert.ok(text.split("\n").every((line) => line.length <= 130));
    assert.deepEqual(envelope.decode(text).payload, payload);
    assert.deepEqual(envelope.peek(text), { encrypted: false, summary: "7 files, 1 KB" });
    assert.equal(envelope.looksLikeEnvelope("hello"), false);
  });

  it("removes exactly the body-whitespace set", () => {
    for (let code = 0; code <= 0xffff; code++) {
      if (code >= 0xd800 && code <= 0xdfff) continue;
      const char = String.fromCharCode(code);
      assert.equal(envelope.removeBodyWhitespace(char) === "", envelope.isBodyWhitespace(code), `U+${code.toString(16).padStart(4, "0")}`);
    }
  });

  it("decodes a huge single-line body quickly (collapsed newlines)", () => {
    const payload = Buffer.alloc(7_500_000, 7); // 10 million Base64 characters
    const text = envelope.encode(payload, false, "1 file, 7.2 MB").replace(/\n/g, " ");
    const started = performance.now();
    assert.equal(envelope.decode(text).payload.length, payload.length);
    assert.ok(performance.now() - started < 3000, `took ${(performance.now() - started).toFixed(0)} ms`);
  });

  it("reports truncation with the counts", () => {
    const text = envelope.encode(Buffer.alloc(300, 1), false, "1 file, 300 bytes");
    try {
      envelope.decode(text.slice(0, text.length / 2));
      assert.fail("expected INCOMPLETE");
    } catch (error) {
      assert.ok(error instanceof CopyPastaError);
      assert.equal(error.category, "INCOMPLETE");
      assert.equal(error.expected, 400);
      assert.ok(error.received > 0 && error.received < 400);
    }
  });
});

describe("crypto", () => {
  it("round-trips, and detects a wrong passphrase", async () => {
    const payload = zip.encode(files);
    const sealed = await crypto.encrypt(payload, "correct horse", 1000);
    assert.deepEqual(await crypto.decrypt(sealed, "correct horse"), payload);
    assert.equal(await category(crypto.decrypt(sealed, "wrong")), "WRONG_PASSPHRASE");
    const text = envelope.encode(sealed, true, "7 files, 1 KB");
    assert.equal((await decodeText(text, "correct horse")).length, files.length);
  });

  it("refuses empty passphrases and unpaired surrogates", async () => {
    assert.equal(crypto.isWellFormed("ok ✓ 😀"), true);
    for (const bad of ["", "\uD800", "a\uDC00b", "x\uD83D"]) {
      await assert.rejects(crypto.encrypt(Buffer.from("x"), bad, 1), RangeError, JSON.stringify(bad));
    }
  });

  it("rejects payloads too short for a tag before key derivation", async () => {
    const header = Buffer.alloc(8);
    header.write("CPv2", 0, "ascii");
    header.writeInt32BE(5_000_000, 4);
    const started = performance.now();
    assert.equal(await category(crypto.decrypt(Buffer.concat([header, Buffer.alloc(16 + 12 + 15)]), "x")), "INVALID_ARCHIVE");
    assert.ok(performance.now() - started < 500);
  });
});

describe("worker decoding", () => {
  it("decodes in a worker with the same results and error categories", async () => {
    const text = envelope.encode(zip.encode(files), false, "7 files, 1 KB");
    const decoded = await decodeInWorker(text);
    assert.deepEqual(
      decoded.map((f) => [f.path, f.bytes.toString("base64"), f.mode, f.executable]),
      files.map((f) => [f.path, f.bytes.toString("base64"), f.mode, f.executable]),
    );
    assert.equal(await category(decodeInWorker(text.slice(0, 200))), "INCOMPLETE");
    assert.equal(await category(decodeInWorker("hello")), "NOT_FOUND");
  });

  it("can be cancelled", async () => {
    const abort = new AbortController();
    const pending = decodeInWorker(envelope.encode(await crypto.encrypt(Buffer.from("x"), "pw", 5_000_000), true, "1 file, 1 byte"), "pw", abort.signal);
    setTimeout(() => abort.abort(), 50);
    await assert.rejects(pending, /cancelled/);
  });
});

describe("file modes and names", () => {
  it("describes modes like ls", () => {
    assert.equal(fileMode.describe(0o755), "rwxr-xr-x");
    assert.equal(fileMode.describe(0o600), "rw-------");
  });

  it("folds like a case-insensitive file system", () => {
    assert.equal(pathRules.fold("." + String.fromCodePoint(0x17f) + "VN"), ".svn");
    assert.equal(pathRules.fold("G" + String.fromCodePoint(0x131) + "T"), "git");
    assert.equal(pathRules.fold(String.fromCodePoint(0x212a)), "k");
    assert.equal(pathRules.fold("STRASSE" + String.fromCodePoint(0xdf)), "strasse" + String.fromCodePoint(0xdf)); // ß has no simple upper case
  });
});
