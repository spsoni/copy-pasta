import { decrypt } from "./crypto";
import { decode as decodeEnvelope } from "./envelope";
import { decode as decodeZip, type FileEntry } from "./zip";

export * as crypto from "./crypto";
export * as envelope from "./envelope";
export * as fileMode from "./fileMode";
export * as pathRules from "./pathRules";
export * as plan from "./plan";
export * as zip from "./zip";
export { CopyPastaError, type ErrorCategory } from "./errors";
export type { FileEntry } from "./zip";

/**
 * The full decode path (spec/FORMAT.md §1): envelope → decrypt if encrypted → ZIP. [passphrase] is only used for
 * encrypted text; callers ask for it after checking `envelope.isEncrypted`.
 */
export async function decodeText(text: string, passphrase?: string): Promise<FileEntry[]> {
  const decoded = decodeEnvelope(text);
  const zip = decoded.encrypted ? await decrypt(decoded.payload, passphrase ?? "") : decoded.payload;
  return decodeZip(zip);
}
