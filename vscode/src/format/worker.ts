import { Worker, isMainThread, parentPort, workerData } from "node:worker_threads";
import { encrypt } from "./crypto";
import { encode as encodeEnvelope } from "./envelope";
import { CopyPastaError, type ErrorCategory } from "./errors";
import { decodeText } from "./index";
import { encode as encodeZip, type FileEntry } from "./zip";

/**
 * Runs the heavy work off the extension host's thread: decoding untrusted text (a 70 MB or hostile paste takes
 * seconds of synchronous inflating) and encoding a copy (deflating up to 50 MB at level 9). In a worker it can't
 * freeze VS Code and Cancel terminates it. The worker's V8 heap is capped; Buffer memory isn't, which is why the
 * format's own limits (200 MB, 10,000 files, 20,000 records) remain the real guard.
 */
type WireFile = { path: string; bytes: Uint8Array; executable: boolean; mode: number | null };

type Request =
  | { copyPastaJob: "decode"; text: string; passphrase: string | undefined }
  | { copyPastaJob: "encode"; files: WireFile[]; passphrase: string | undefined; summary: string };

type Reply =
  | { ok: true; files?: WireFile[]; text?: string }
  | { ok: false; category: ErrorCategory | undefined; message: string; received: number; expected: number };

const MAX_WORKER_HEAP_MB = 1536;

async function perform(request: Request): Promise<Reply> {
  if (request.copyPastaJob === "decode") return { ok: true, files: await decodeText(request.text, request.passphrase) };
  const files = request.files.map((f) => ({ ...f, bytes: Buffer.from(f.bytes.buffer, f.bytes.byteOffset, f.bytes.byteLength) }));
  const archive = encodeZip(files);
  const payload = request.passphrase === undefined ? archive : await encrypt(archive, request.passphrase);
  return { ok: true, text: encodeEnvelope(payload, request.passphrase !== undefined, request.summary) };
}

if (!isMainThread && (workerData as Request | undefined)?.copyPastaJob) {
  perform(workerData as Request).then(
    (reply) => parentPort?.postMessage(reply),
    (error: unknown) =>
      parentPort?.postMessage({
        ok: false,
        category: error instanceof CopyPastaError ? error.category : undefined,
        message: error instanceof Error ? error.message : String(error),
        received: error instanceof CopyPastaError ? error.received : 0,
        expected: error instanceof CopyPastaError ? error.expected : 0,
      } satisfies Reply),
  );
}

function run(request: Request, signal?: AbortSignal): Promise<Reply & { ok: true }> {
  return new Promise((resolve, reject) => {
    const worker = new Worker(__filename, { workerData: request, resourceLimits: { maxOldGenerationSizeMb: MAX_WORKER_HEAP_MB } });
    let settled = false;
    const finish = (action: () => void) => {
      if (settled) return;
      settled = true;
      signal?.removeEventListener("abort", abort);
      void worker.terminate();
      action();
    };
    const abort = () => finish(() => reject(new Error("cancelled")));
    if (signal?.aborted) return abort();
    signal?.addEventListener("abort", abort, { once: true });
    worker.once("message", (reply: Reply) =>
      finish(() => {
        if (reply.ok) resolve(reply);
        else if (reply.category) reject(new CopyPastaError(reply.category, reply.message, reply.received, reply.expected));
        else reject(new Error(reply.message));
      }),
    );
    // Running out of the capped heap is what a decompression bomb that slipped past the limits would do.
    worker.once("error", (error) => finish(() => reject(error.message.includes("heap") ? new CopyPastaError("INVALID_ARCHIVE", "the archive needs too much memory") : error)));
    worker.once("exit", (code) => finish(() => reject(new Error(`the worker stopped unexpectedly (exit code ${code})`))));
  });
}

/** [decodeText] in a worker thread. Rejects with CopyPastaError as decodeText does; [signal] terminates the worker. */
export async function decodeInWorker(text: string, passphrase?: string, signal?: AbortSignal): Promise<FileEntry[]> {
  const reply = await run({ copyPastaJob: "decode", text, passphrase }, signal);
  return (reply.files ?? []).map((f) => ({ path: f.path, bytes: Buffer.from(f.bytes.buffer, f.bytes.byteOffset, f.bytes.byteLength), executable: f.executable, mode: f.mode }));
}

/** ZIP → optional CPv2 → envelope (spec §1) in a worker thread. */
export async function encodeInWorker(files: FileEntry[], summary: string, passphrase?: string, signal?: AbortSignal): Promise<string> {
  const reply = await run({ copyPastaJob: "encode", files, passphrase, summary }, signal);
  return reply.text ?? "";
}
