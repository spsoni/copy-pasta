import { createCipheriv, createDecipheriv, pbkdf2, randomBytes } from "node:crypto";
import { CopyPastaError } from "./errors";

/**
 * Passphrase encryption (spec/FORMAT.md §3.2):
 * `CPv2` | PBKDF2 iterations (int32, big-endian) | salt (16) | iv (12) | AES-256-GCM ciphertext ‖ tag (16).
 * The key is PBKDF2-HMAC-SHA256 over the passphrase's UTF-8 bytes. GCM authentication means a wrong passphrase or
 * tampered text is detected, never "decrypted" into garbage. Key derivation is asynchronous so it never blocks the
 * extension host.
 */
const MAGIC = Buffer.from("CPv2", "ascii");
const SALT_BYTES = 16;
const IV_BYTES = 12;
const TAG_BYTES = 16;
const KEY_BYTES = 32;
const HEADER_BYTES = MAGIC.length + 4 + SALT_BYTES + IV_BYTES;

/** OWASP's guidance for PBKDF2-HMAC-SHA256. */
export const ITERATIONS = 600_000;
/** Upper bound accepted when reading, so crafted text can't make the reader spin for minutes. */
const MAX_ITERATIONS = 5_000_000;

function deriveKey(passphrase: string, salt: Buffer, iterations: number): Promise<Buffer> {
  return new Promise((resolve, reject) =>
    pbkdf2(Buffer.from(passphrase, "utf8"), salt, iterations, KEY_BYTES, "sha256", (error, key) => (error ? reject(error) : resolve(key))),
  );
}

/**
 * False when [passphrase] contains an unpaired UTF-16 surrogate. Encoders disagree on those (Java writes `?`, Node
 * writes U+FFFD), so text encrypted with one could never be decrypted elsewhere.
 */
export function isWellFormed(passphrase: string): boolean {
  for (let i = 0; i < passphrase.length; i++) {
    const unit = passphrase.charCodeAt(i);
    if (unit >= 0xd800 && unit <= 0xdbff) {
      const next = passphrase.charCodeAt(i + 1);
      if (!(next >= 0xdc00 && next <= 0xdfff)) return false;
      i++;
    } else if (unit >= 0xdc00 && unit <= 0xdfff) {
      return false;
    }
  }
  return true;
}

export async function encrypt(data: Uint8Array, passphrase: string, iterations = ITERATIONS): Promise<Buffer> {
  if (passphrase.length === 0 || !isWellFormed(passphrase)) throw new RangeError("the passphrase must be non-empty, well-formed text");
  const salt = randomBytes(SALT_BYTES);
  const iv = randomBytes(IV_BYTES);
  const cipher = createCipheriv("aes-256-gcm", await deriveKey(passphrase, salt, iterations), iv, { authTagLength: TAG_BYTES });
  const header = Buffer.alloc(MAGIC.length + 4);
  MAGIC.copy(header);
  header.writeInt32BE(iterations, MAGIC.length);
  const ciphertext = Buffer.concat([cipher.update(data), cipher.final()]);
  return Buffer.concat([header, salt, iv, ciphertext, cipher.getAuthTag()]);
}

/** Throws INVALID_ARCHIVE for a malformed payload (before any key derivation) and WRONG_PASSPHRASE on a bad tag. */
export async function decrypt(payload: Uint8Array, passphrase: string): Promise<Buffer> {
  const bytes = Buffer.from(payload.buffer, payload.byteOffset, payload.byteLength);
  if (bytes.length < HEADER_BYTES + TAG_BYTES || !bytes.subarray(0, MAGIC.length).equals(MAGIC)) {
    throw new CopyPastaError("INVALID_ARCHIVE", "not an encrypted Copy-Pasta payload");
  }
  const iterations = bytes.readInt32BE(MAGIC.length);
  if (iterations < 1 || iterations > MAX_ITERATIONS) throw new CopyPastaError("INVALID_ARCHIVE", "unsupported key derivation settings");

  const saltStart = MAGIC.length + 4;
  const ivStart = saltStart + SALT_BYTES;
  const key = await deriveKey(passphrase, bytes.subarray(saltStart, ivStart), iterations);
  const decipher = createDecipheriv("aes-256-gcm", key, bytes.subarray(ivStart, HEADER_BYTES), { authTagLength: TAG_BYTES });
  decipher.setAuthTag(bytes.subarray(bytes.length - TAG_BYTES));
  try {
    return Buffer.concat([decipher.update(bytes.subarray(HEADER_BYTES, bytes.length - TAG_BYTES)), decipher.final()]);
  } catch {
    throw new CopyPastaError("WRONG_PASSPHRASE");
  }
}
