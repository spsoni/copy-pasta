/** The error categories of spec/FORMAT.md §7. Every decoding failure maps to exactly one. */
export type ErrorCategory = "NOT_FOUND" | "INCOMPLETE" | "CORRUPTED" | "NEWER_VERSION" | "WRONG_PASSPHRASE" | "INVALID_ARCHIVE";

export class CopyPastaError extends Error {
  constructor(
    readonly category: ErrorCategory,
    message?: string,
    /** For INCOMPLETE: how many Base64 characters arrived and how many were declared. */
    readonly received = 0,
    readonly expected = 0,
  ) {
    super(message ?? category);
    this.name = "CopyPastaError";
  }
}

export function invalidArchive(message: string): CopyPastaError {
  return new CopyPastaError("INVALID_ARCHIVE", message);
}
