import assert from "node:assert/strict";
import { mkdtempSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { describe, it } from "node:test";
import { CopyPastaError, decodeText, envelope, fileMode, plan } from "../src/format";
import { caseText, loadCases, loadModeRules, octal } from "./vectors";

/**
 * Every case in spec/vectors/manifest.json through the full decode path. The JetBrains plugin runs the same cases
 * (ConformanceTest.kt): passing both is what makes the two products interoperate.
 */
const cases = loadCases();

describe("spec vectors", () => {
  it("has cases", () => assert.ok(cases.length >= 52, `only ${cases.length} cases`));

  for (const c of cases) {
    it(c.name, async () => {
      const text = caseText(c);
      const started = performance.now();
      let files;
      let error: unknown;
      try {
        files = await decodeText(text, c.passphrase ?? undefined);
      } catch (e) {
        error = e;
      }
      const millis = performance.now() - started;
      if (c.maxMillis !== undefined) assert.ok(millis <= c.maxMillis, `took ${millis.toFixed(0)} ms, budget ${c.maxMillis} ms`);

      if (c.expect.error !== undefined) {
        const category = error instanceof CopyPastaError ? error.category : error === undefined ? "decoded without error" : `UNEXPECTED ${String(error)}`;
        assert.equal(category, c.expect.error, c.description);
        return;
      }
      if (error !== undefined) throw error;
      const expected = [...(c.expect.files ?? [])].sort((a, b) => (a.path < b.path ? -1 : 1));
      const actual = [...(files ?? [])].sort((a, b) => (a.path < b.path ? -1 : 1));
      assert.deepEqual(
        actual.map((f) => f.path),
        expected.map((f) => f.path),
      );
      expected.forEach((want, i) => {
        const got = actual[i]!;
        assert.equal(got.bytes.toString("base64"), want.content, `content of ${want.path}`);
        assert.equal(octal(got.mode), want.mode, `mode of ${want.path}`);
        assert.equal(got.executable, want.executable, `executable flag of ${want.path}`);
      });
      if (c.expect.encrypted !== undefined) assert.equal(envelope.isEncrypted(text), c.expect.encrypted);
      if (c.expect.summary !== undefined) assert.equal(envelope.peek(text)?.summary, c.expect.summary);
    });
  }
});

describe("spec modes table", () => {
  for (const rule of loadModeRules()) {
    it(`sender ${rule.sender}, current ${rule.current}`, () => {
      assert.equal(octal(fileMode.safe(parseInt(rule.sender, 8))), rule.safe);
      assert.equal(octal(fileMode.applied(parseInt(rule.sender, 8), parseInt(rule.current, 8))), rule.applied);
    });
  }
});

describe("spec blocked entries", () => {
  const withBlocks = cases.filter((c) => c.expect.error === undefined);
  it("covers blocked and blockedOnWindows", () => {
    assert.ok(withBlocks.some((c) => (c.expect.blocked ?? []).length > 0));
    assert.ok(withBlocks.some((c) => (c.expect.blockedOnWindows ?? []).length > 0));
  });
  for (const c of withBlocks) {
    it(c.name, async () => {
      const files = await decodeText(caseText(c), c.passphrase ?? undefined);
      const target = mkdtempSync(join(tmpdir(), "copy-pasta-plan-"));
      try {
        for (const windows of [false, true]) {
          const blocked = plan
            .plan(target, files, { caseSensitive: false, windows })
            .filter((p) => p.status === "BLOCKED")
            .map((p) => p.entry.path)
            .sort();
          const expected = [...(c.expect.blocked ?? []), ...(windows ? (c.expect.blockedOnWindows ?? []) : [])].sort();
          assert.deepEqual(blocked, expected, `windows=${windows}`);
        }
      } finally {
        rmSync(target, { recursive: true, force: true });
      }
    });
  }
});
