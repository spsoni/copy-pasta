import * as vscode from "vscode";
import { envelope } from "./format";
import { encodeInWorker } from "./format/worker";
import { CopyPathError, CopyTooLargeError, LONG_TEXT_CHARS, askNewPassphrase, collect, type Collected } from "./ui/copy";
import { describeError, savedFileName, summary } from "./ui/messages";
import { chooseFolder, pasteFromFile, pasteText, pasteWithMessages, resolveTarget } from "./ui/paste";
import { ClipboardIndicator } from "./ui/statusBar";

/**
 * Copy-Pasta for VS Code. Copy turns the Explorer selection into one block of text (spec/FORMAT.md); Paste previews
 * and recreates the files. No network, no telemetry, no runtime dependencies.
 */
export function activate(context: vscode.ExtensionContext) {
  const indicator = new ClipboardIndicator();
  const onPasted = (text: string) => indicator.markPasted(text);
  context.subscriptions.push(indicator);

  /** The finished clipboard text for [collected], built in a worker so a 50 MB copy can't freeze VS Code. */
  const encodeText = (collected: Collected, passphrase?: string) => encodeInWorker(collected.files, summary(collected.files.length, collected.totalBytes), passphrase);

  const copy = async (clicked: vscode.Uri | undefined, selected: vscode.Uri[] | undefined, encrypted: boolean) => {
    const roots = selected && selected.length > 0 ? selected : clicked ? [clicked] : vscode.window.activeTextEditor ? [vscode.window.activeTextEditor.document.uri] : [];
    if (roots.length === 0) {
      void vscode.window.showInformationMessage("Select files or folders in the Explorer, then choose Copy-Pasta: Copy.");
      return;
    }
    try {
      const collected = await vscode.window.withProgress(
        { location: vscode.ProgressLocation.Notification, title: "Copy-Pasta: collecting files…", cancellable: true },
        (_progress, token) => collect(roots, token),
      );
      if (collected.files.length === 0) {
        void vscode.window.showWarningMessage("Nothing to copy: every selected file is ignored, a link, or too large.");
        return;
      }
      if (collected.gitRulesMissing.length > 0) {
        // Without Git's ignore rules, files such as .env or build output could be included. Ask first.
        const choice = await vscode.window.showWarningMessage(
          "Git ignore rules couldn't be applied to the selection (the repository isn't open in VS Code, or Git failed), so ignored files such as .env may be included.",
          { modal: true },
          "Copy Anyway",
        );
        if (choice !== "Copy Anyway") return;
      }
      const passphrase = encrypted ? await askNewPassphrase() : undefined;
      if (encrypted && passphrase === undefined) return;
      const text = await vscode.window.withProgress({ location: vscode.ProgressLocation.Notification, title: "Copy-Pasta: packing…" }, () => encodeText(collected, passphrase));
      await vscode.env.clipboard.writeText(text);
      indicator.markCopied(text);

      const notes = [
        collected.skippedIgnored > 0 ? `${collected.skippedIgnored} ignored` : "",
        collected.skippedLinks > 0 ? `${collected.skippedLinks} symbolic links` : "",
        collected.skippedLarge.length > 0 ? `${collected.skippedLarge.length} over 10 MB` : "",
      ].filter(Boolean);
      const done = `Copied ${summary(collected.files.length, collected.totalBytes)}${encrypted ? ", encrypted" : ""}. Paste it into a chat, email or ticket.${notes.length > 0 ? ` Skipped: ${notes.join(", ")}.` : ""}`;
      if (text.length > LONG_TEXT_CHARS) {
        const choice = await vscode.window.showWarningMessage(
          `${done} It's ${text.length.toLocaleString()} characters long, more than many chat apps accept: send it as a file instead.`,
          "Save as File…",
        );
        if (choice) await saveAsFile(text);
      } else {
        void vscode.window.showInformationMessage(done);
      }
    } catch (error) {
      if (error instanceof vscode.CancellationError) return;
      if (error instanceof CopyTooLargeError) {
        void vscode.window.showErrorMessage(`The selection is too large to copy (${error.message}). Copy-Pasta handles up to 50 MB and 10,000 files.`);
      } else if (error instanceof CopyPathError) {
        void vscode.window.showErrorMessage(`Copy-Pasta can't copy this selection: ${error.message}.`);
      } else {
        void vscode.window.showErrorMessage(describeError(error));
      }
    }
  };

  const paste = async (clicked?: vscode.Uri) => {
    const target = await resolveTarget(clicked);
    if (target) await pasteWithMessages(target, await vscode.env.clipboard.readText(), onPasted);
  };

  const saveAsFile = async (text?: string) => {
    const content = text ?? (await vscode.env.clipboard.readText());
    if (!envelope.looksLikeEnvelope(content)) {
      void vscode.window.showInformationMessage("The clipboard doesn't contain Copy-Pasta text.");
      return;
    }
    const folder = vscode.workspace.workspaceFolders?.[0]?.uri;
    const options: vscode.SaveDialogOptions = { filters: { "Copy-Pasta text": ["txt"] }, saveLabel: "Save" };
    if (folder) options.defaultUri = vscode.Uri.joinPath(folder, savedFileName());
    const file = await vscode.window.showSaveDialog(options);
    if (!file) return;
    await vscode.workspace.fs.writeFile(file, Buffer.from(content, "utf8"));
    void vscode.window.showInformationMessage("Saved. Send the file as an attachment; the recipient uses Copy-Pasta: Paste from File….");
  };

  const clear = async () => {
    if (!envelope.looksLikeEnvelope(await vscode.env.clipboard.readText())) {
      void vscode.window.showInformationMessage("The clipboard doesn't contain Copy-Pasta text.");
      return;
    }
    await vscode.env.clipboard.writeText(""); // only on the user's explicit request
    await indicator.refresh();
  };

  const statusMenu = async () => {
    await indicator.refresh(); // the clipboard may have changed without a focus change
    const items = [
      { label: "$(folder-opened) Paste into Workspace Root…", run: () => paste() },
      {
        label: "$(folder) Paste into Folder…",
        run: async () => {
          const folder = await chooseFolder();
          if (folder) await pasteWithMessages(folder, await vscode.env.clipboard.readText(), onPasted);
        },
      },
      { label: "$(save) Save as File…", run: () => saveAsFile() },
      { label: "$(clear-all) Clear from Clipboard", run: () => clear() },
    ];
    const picked = await vscode.window.showQuickPick(items, { title: "Copy-Pasta" });
    await picked?.run();
  };

  /** Every command reports its own failures (generic text; details go to the output channel). */
  const register = <A extends unknown[]>(id: string, run: (...args: A) => Promise<unknown>) =>
    context.subscriptions.push(
      vscode.commands.registerCommand(id, async (...args: A) => {
        try {
          await run(...args);
        } catch (error) {
          if (!(error instanceof vscode.CancellationError)) void vscode.window.showErrorMessage(describeError(error));
        }
      }),
    );
  register("copyPasta.copy", (clicked?: vscode.Uri, selected?: vscode.Uri[]) => copy(clicked, selected, false));
  register("copyPasta.copyEncrypted", (clicked?: vscode.Uri, selected?: vscode.Uri[]) => copy(clicked, selected, true));
  register("copyPasta.paste", (clicked?: vscode.Uri) => paste(clicked));
  register("copyPasta.pasteFromFile", async (clicked?: vscode.Uri) => pasteFromFile(clicked ? await resolveTarget(clicked) : undefined, onPasted));
  register("copyPasta.saveAsFile", () => saveAsFile());
  register("copyPasta.clearClipboard", () => clear());
  register("copyPasta.statusMenu", () => statusMenu());

  context.subscriptions.push(vscode.window.onDidChangeWindowState((state) => state.focused && void indicator.refresh()));
  void indicator.refresh();

  // For the integration tests: the same code paths without the dialogs.
  return { collect, encodeText, pasteText, indicator };
}

export function deactivate(): void {}
