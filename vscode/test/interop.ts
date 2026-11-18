import { readFileSync, writeFileSync } from "node:fs";
import { join, resolve } from "node:path";
import { crypto, decodeText, envelope, zip, type FileEntry } from "../src/format";

/**
 * Cross-implementation check (spec/interop/fixture.json, run by .github/workflows/interop.yml and `make interop`):
 *   node out/test/interop.js encode <dir>           writes vscode-plain.txt and vscode-encrypted.txt
 *   node out/test/interop.js decode <dir> <prefix>  decodes <prefix>-plain.txt and <prefix>-encrypted.txt
 */
interface Fixture {
  passphrase: string;
  files: { path: string; content: string; mode: string | null; executable: boolean }[];
}

const fixture = JSON.parse(readFileSync(resolve(__dirname, "../../../spec/interop/fixture.json"), "utf8")) as Fixture;
const files: FileEntry[] = fixture.files.map((f) => ({
  path: f.path,
  bytes: Buffer.from(f.content, "base64"),
  mode: f.mode === null ? null : parseInt(f.mode, 8),
  executable: f.executable,
}));

function describe(list: FileEntry[]): string[] {
  return [...list]
    .sort((a, b) => (a.path < b.path ? -1 : 1))
    .map((f) => `${f.path} ${f.bytes.toString("base64")} ${f.mode === null ? "null" : f.mode.toString(8)} ${f.executable}`);
}

async function main(): Promise<void> {
  const [mode, dir, prefix] = process.argv.slice(2);
  if (mode === "encode" && dir) {
    const archive = zip.encode(files);
    writeFileSync(join(dir, "vscode-plain.txt"), envelope.encode(archive, false, `${files.length} files`));
    writeFileSync(join(dir, "vscode-encrypted.txt"), envelope.encode(await crypto.encrypt(archive, fixture.passphrase), true, `${files.length} files`));
    console.log(`encoded the fixture into ${dir}`);
  } else if (mode === "decode" && dir && prefix) {
    for (const kind of ["plain", "encrypted"]) {
      const text = readFileSync(join(dir, `${prefix}-${kind}.txt`), "utf8");
      const decoded = await decodeText(text, fixture.passphrase);
      const want = describe(files).join("\n");
      const got = describe(decoded).join("\n");
      if (want !== got) throw new Error(`${prefix}-${kind}.txt decoded differently:\n--- want\n${want}\n--- got\n${got}`);
      console.log(`decoded ${prefix}-${kind}.txt: ${decoded.length} files match the fixture`);
    }
  } else {
    throw new Error("usage: interop encode <dir> | interop decode <dir> <prefix>");
  }
}

main().catch((error: unknown) => {
  console.error(error);
  process.exitCode = 1;
});
