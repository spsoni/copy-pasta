import assert from "node:assert/strict";
import { execFileSync } from "node:child_process";
import { chmodSync, existsSync, mkdirSync, mkdtempSync, readFileSync, rmSync, symlinkSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { afterEach, beforeEach, describe, it } from "node:test";
import { plan, type FileEntry } from "../src/format";

/** Rules the vectors can't express, because they depend on what's already in the recipient's folder (spec §5.1). */
const entry = (path: string, text: string, mode: number | null = null, executable = false): FileEntry => ({
  path,
  bytes: Buffer.from(text),
  executable,
  mode,
});

const posix = process.platform !== "win32";
let root: string;
let target: string;

beforeEach(() => {
  root = mkdtempSync(join(tmpdir(), "copy-pasta-links-"));
  target = join(root, "target");
  mkdirSync(target);
});
afterEach(() => rmSync(root, { recursive: true, force: true }));

describe("paste plan on a real folder", { skip: !posix && "symlinks need extra rights on Windows" }, () => {
  it("never follows symbolic links in the target", () => {
    const outside = join(root, "outside");
    mkdirSync(outside);
    writeFileSync(join(outside, "victim.txt"), "original");
    symlinkSync(outside, join(target, "link"));
    symlinkSync(join(outside, "victim.txt"), join(target, "filelink"));
    symlinkSync(join(root, "missing"), join(target, "broken"));
    mkdirSync(join(target, "real/inner"), { recursive: true });
    symlinkSync(outside, join(target, "real/inner/deep"));

    const statuses = plan
      .plan(target, [entry("link/planted.txt", "x"), entry("filelink", "overwritten"), entry("broken", "x"), entry("real/inner/deep/planted.txt", "x"), entry("real/ok.txt", "fine")])
      .map((p) => p.status);
    assert.deepEqual(statuses, ["BLOCKED", "BLOCKED", "BLOCKED", "BLOCKED", "NEW"]);

    assert.throws(() => plan.assertWritable(target, "link/planted.txt"));
    assert.throws(() => plan.assertWritable(target, "filelink"));
    assert.doesNotThrow(() => plan.assertWritable(target, "real/ok.txt"));
    assert.ok(!existsSync(join(outside, "planted.txt")));
    assert.equal(readFileSync(join(outside, "victim.txt"), "utf8"), "original");
  });

  it("blocks special files such as a FIFO", () => {
    execFileSync("mkfifo", [join(target, "pipe")]);
    assert.deepEqual(plan.plan(target, [entry("pipe", "x")]).map((p) => p.status), ["BLOCKED"]);
  });

  it("marks new, identical, overwrite and blocked entries", () => {
    writeFileSync(join(target, "same.txt"), "same");
    chmodSync(join(target, "same.txt"), 0o644);
    writeFileSync(join(target, "changed.txt"), "old");
    writeFileSync(join(target, "file"), "a file, not a folder");
    mkdirSync(join(target, "dir"));
    const statuses = plan
      .plan(target, [entry("new/a.txt", "a"), entry("same.txt", "same", 0o644), entry("changed.txt", "new"), entry("file/inner.txt", "x"), entry("dir", "x")])
      .map((p) => p.status);
    assert.deepEqual(statuses, ["NEW", "IDENTICAL", "OVERWRITE", "BLOCKED", "BLOCKED"]);
  });

  it("treats a permissions-only change as an overwrite, never widening access", () => {
    writeFileSync(join(target, "private.txt"), "same");
    chmodSync(join(target, "private.txt"), 0o600);
    writeFileSync(join(target, "script.sh"), "same");
    chmodSync(join(target, "script.sh"), 0o600);
    const statuses = plan
      .plan(target, [entry("private.txt", "same", 0o644), entry("script.sh", "same", 0o755, true)], { windows: false })
      .map((p) => p.status);
    // 0644 over 0600 stays 0600 (never widened), so nothing changes; 0755 adds owner-execute.
    assert.deepEqual(statuses, ["IDENTICAL", "OVERWRITE"]);
    assert.equal(plan.targetMode(entry("script.sh", "same", 0o755, true), 0o600), 0o700);
    assert.equal(plan.targetMode(entry("legacy.sh", "same", null, true), 0o600), 0o700); // 2.0 text: flag only
    assert.equal(plan.targetMode(entry("plain.txt", "same"), 0o600), null);
  });
});

describe("paste plan names", () => {
  it("treats NFC and NFD spellings of one name as one file", () => {
    const nfc = "caf" + String.fromCodePoint(0xe9) + ".txt";
    const nfd = "cafe" + String.fromCodePoint(0x301) + ".txt";
    const statuses = plan.plan(target, [entry(nfc, "a"), entry(nfd, "b")], { caseSensitive: true }).map((p) => p.status);
    assert.deepEqual(statuses, ["NEW", "BLOCKED"]);
  });

  it("blocks Windows-unsafe names on Windows targets only", () => {
    const unsafe = ["a/C:/x.txt", "notes.txt:stream", "con.txt", "dir/AUX", "lpt1.log", "COM0", "com" + String.fromCodePoint(0xb9) + ".txt", "trail./x", "space /x", "q?.txt", "pipe|.txt"];
    const onWindows = plan.plan(target, [...unsafe.map((p) => entry(p, "x")), entry("console.txt", "fine")], { windows: true }).map((p) => p.status);
    assert.deepEqual(onWindows, [...unsafe.map(() => "BLOCKED"), "NEW"]);
    const elsewhere = plan.plan(target, [entry("12:30.log", "x"), entry("con.txt", "x")], { windows: false }).map((p) => p.status);
    assert.deepEqual(elsewhere, ["NEW", "NEW"]);
  });

  it("blocks VCS metadata lookalikes", () => {
    const names = [
      ".git./hooks/a",
      ".git /hooks/b",
      "GIT~1/hooks/c",
      ".g" + String.fromCodePoint(0x200c) + "it/hooks/d",
      "p/.GIT/config",
      "x/.svn./entries",
      "HG~1/hgrc",
      "Svn~1/entries",
      "bzr~1/branch.conf",
      "." + String.fromCodePoint(0x17f) + "vn/entries",
      "CV" + String.fromCodePoint(0x17f) + "/Root",
      ".g" + String.fromCodePoint(0x130) + "t/config", // dotted capital I folds to i
    ];
    const statuses = plan.plan(target, [...names.map((p) => entry(p, "x")), entry("p/.github/ok.yml", "fine")]).map((p) => p.status);
    assert.deepEqual(statuses, [...names.map(() => "BLOCKED"), "NEW"]);
  });
});
