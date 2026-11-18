import assert from "node:assert/strict";
import { existsSync, lstatSync, mkdirSync, readFileSync, symlinkSync, writeFileSync } from "node:fs";
import { join, resolve } from "node:path";
import * as vscode from "vscode";
import type { FileEntry } from "../../../src/format";

/**
 * Runs inside VS Code (see runTests.ts). A tiny runner instead of a test framework, so the extension keeps a minimal
 * set of dev dependencies. Each test drives the same code paths as the commands, minus the dialogs.
 */
interface Api {
  collect(selection: vscode.Uri[]): Promise<{ files: FileEntry[]; totalBytes: number }>;
  encodeText(collected: { files: FileEntry[]; totalBytes: number }, passphrase?: string): Promise<string>;
  pasteText(
    text: string,
    target: vscode.Uri,
    hooks: { passphrase?: () => Promise<string | undefined>; choose?: (offered: { entry: FileEntry; status: string }[]) => Promise<FileEntry[] | undefined> },
  ): Promise<{ written: FileEntry[]; skipped: string[] } | undefined>;
}

const workspace = process.env.COPY_PASTA_IT_WORKSPACE as string;
const vectors = resolve(__dirname, "../../../../../spec/vectors");
const posix = process.platform !== "win32";
const everything = async (offered: { entry: FileEntry }[]) => offered.map((o) => o.entry); // pasteText still drops skipped entries

function folder(name: string): vscode.Uri {
  const path = join(workspace, name);
  mkdirSync(path, { recursive: true });
  return vscode.Uri.file(path);
}

const tests: [string, (api: Api) => Promise<void>][] = [
  [
    "copies a folder and pastes it elsewhere with content and permissions",
    async (api) => {
      const collected = await api.collect([vscode.Uri.file(join(workspace, "src"))]);
      assert.deepEqual(collected.files.map((f) => f.path).sort(), ["src/hello.txt", "src/nested/run.sh", "src/secret.pem"]);
      const text = await api.encodeText(collected);
      assert.match(text, /^-----BEGIN COPY-PASTA-----\n/);
      const target = folder("pasted-plain");
      const result = await api.pasteText(text, target, { choose: everything });
      assert.equal(result?.written.length, 3);
      assert.equal(readFileSync(join(target.fsPath, "src/hello.txt"), "utf8"), "Hello from VS Code\n");
      if (posix) {
        assert.equal(lstatSync(join(target.fsPath, "src/nested/run.sh")).mode & 0o777, 0o755);
        assert.equal(lstatSync(join(target.fsPath, "src/secret.pem")).mode & 0o777, 0o600);
      }
    },
  ],
  [
    "round-trips encrypted text with the passphrase",
    async (api) => {
      const collected = await api.collect([vscode.Uri.file(join(workspace, "src/hello.txt"))]);
      const text = await api.encodeText(collected, "correct horse");
      assert.match(text, /BEGIN COPY-PASTA ENCRYPTED/);
      const target = folder("pasted-encrypted");
      await api.pasteText(text, target, { passphrase: async () => "correct horse", choose: everything });
      assert.equal(readFileSync(join(target.fsPath, "hello.txt"), "utf8"), "Hello from VS Code\n");
    },
  ],
  [
    "pastes text written by the JetBrains plugin",
    async (api) => {
      const target = folder("pasted-from-jetbrains");
      const result = await api.pasteText(readFileSync(join(vectors, "plain-project.txt"), "utf8"), target, { choose: everything });
      assert.equal(result?.written.length, 7);
      assert.equal(readFileSync(join(target.fsPath, "demo/README.md"), "utf8"), "# Demo\n");
    },
  ],
  [
    "never writes VCS metadata lookalikes, even if chosen",
    async (api) => {
      const target = folder("pasted-vcs");
      const result = await api.pasteText(readFileSync(join(vectors, "vcs-lookalikes-blocked.txt"), "utf8"), target, { choose: everything });
      assert.deepEqual(result?.written.map((f) => f.path), ["ok.txt"]);
      assert.equal(result?.skipped.length, 8);
      assert.ok(!existsSync(join(target.fsPath, "HG~1")));
    },
  ],
  [
    "overwrites an existing file, and skips one with unsaved changes in an editor",
    async (api) => {
      const target = folder("pasted-overwrite");
      writeFileSync(join(target.fsPath, "plain.txt"), "old\n");
      writeFileSync(join(target.fsPath, "edited.txt"), "on disk\n");
      const document = await vscode.workspace.openTextDocument(vscode.Uri.file(join(target.fsPath, "edited.txt")));
      const edit = new vscode.WorkspaceEdit();
      edit.insert(document.uri, new vscode.Position(0, 0), "unsaved ");
      await vscode.workspace.applyEdit(edit);
      assert.ok(document.isDirty);

      const files = [
        { path: "plain.txt", bytes: Buffer.from("new\n"), executable: false, mode: null },
        { path: "edited.txt", bytes: Buffer.from("pasted\n"), executable: false, mode: null },
      ];
      const result = await api.pasteText(await api.encodeText({ files, totalBytes: 11 }), target, { choose: everything });
      assert.deepEqual(result?.written.map((f) => f.path), ["plain.txt"]);
      assert.deepEqual(result?.skipped, ["edited.txt"]);
      assert.equal(readFileSync(join(target.fsPath, "plain.txt"), "utf8"), "new\n");
      assert.equal(readFileSync(join(target.fsPath, "edited.txt"), "utf8"), "on disk\n");
      assert.ok(document.isDirty, "the user's unsaved edits are untouched");
    },
  ],
  [
    "never copies VCS metadata, even inside a selected folder",
    async (api) => {
      const project = folder("with-git");
      mkdirSync(join(project.fsPath, ".git"), { recursive: true });
      writeFileSync(join(project.fsPath, ".git/config"), "[remote] url = https://token@example.com/repo\n");
      writeFileSync(join(project.fsPath, "code.txt"), "code\n");
      const collected = await api.collect([project]);
      assert.deepEqual(collected.files.map((f) => f.path), ["with-git/code.txt"]);
    },
  ],
  [
    "never writes through a symbolic link in the target",
    async (api) => {
      if (!posix) return;
      const target = folder("pasted-links");
      const outside = folder("outside");
      symlinkSync(outside.fsPath, join(target.fsPath, "link"));
      const collected = { files: [{ path: "link/planted.txt", bytes: Buffer.from("x"), executable: false, mode: null }], totalBytes: 1 };
      const result = await api.pasteText(await api.encodeText(collected), target, { choose: everything });
      assert.deepEqual(result?.written, []);
      assert.ok(!existsSync(join(outside.fsPath, "planted.txt")));
    },
  ],
];

export async function run(): Promise<void> {
  const extension = vscode.extensions.getExtension<Api>("spsoni.copy-pasta-files");
  assert.ok(extension, "the extension is installed");
  const api = await extension.activate();
  const failures: string[] = [];
  for (const [name, test] of tests) {
    try {
      await test(api);
      console.log(`ok - ${name}`);
    } catch (error) {
      console.error(`not ok - ${name}\n${error instanceof Error ? error.stack : String(error)}`);
      failures.push(name);
    }
  }
  if (failures.length > 0) throw new Error(`${failures.length} integration test(s) failed: ${failures.join("; ")}`);
}
