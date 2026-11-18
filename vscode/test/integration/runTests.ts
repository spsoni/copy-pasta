import { mkdirSync, mkdtempSync, writeFileSync, chmodSync } from "node:fs";
import { tmpdir } from "node:os";
import { join, resolve } from "node:path";
import { runTests } from "@vscode/test-electron";

/**
 * Downloads VS Code (the minimum supported version, so the extension is tested where it must run) and runs the
 * integration suite inside it, on a throwaway workspace. CI runs this under xvfb-run; dev01 has no display.
 */
async function main(): Promise<void> {
  const workspace = mkdtempSync(join(tmpdir(), "copy-pasta-it-"));
  mkdirSync(join(workspace, "src/nested"), { recursive: true });
  writeFileSync(join(workspace, "src/hello.txt"), "Hello from VS Code\n");
  writeFileSync(join(workspace, "src/nested/run.sh"), "#!/bin/sh\necho hi\n");
  chmodSync(join(workspace, "src/nested/run.sh"), 0o755);
  writeFileSync(join(workspace, "src/secret.pem"), "private\n");
  chmodSync(join(workspace, "src/secret.pem"), 0o600);
  mkdirSync(join(workspace, "target"));

  await runTests({
    version: process.env.VSCODE_TEST_VERSION ?? "1.90.0",
    extensionDevelopmentPath: resolve(__dirname, "../../.."),
    extensionTestsPath: resolve(__dirname, "suite/index.js"),
    launchArgs: [workspace, "--disable-extensions", "--disable-workspace-trust"],
    extensionTestsEnv: { COPY_PASTA_IT_WORKSPACE: workspace },
  });
}

main().catch((error: unknown) => {
  console.error(error);
  process.exit(1);
});
