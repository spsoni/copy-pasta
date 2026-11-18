/**
 * Unix permission bits for a copied file (spec/FORMAT.md §5.3): the nine `rwxrwxrwx` bits only.
 */
export const PERMISSION_BITS = 0o777;
export const DEFAULT_MODE = 0o644;
export const EXECUTABLE_MODE = 0o755;
export const OWNER_EXECUTE = 0o100;

/** The owner can always read and write; group and others never write; special bits are dropped. */
export function safe(mode: number): number {
  return ((mode & PERMISSION_BITS) | 0o600) & 0o755;
}

/**
 * What a paste applies to a file whose mode is now [current] (the recipient's umask default for a new file, or the
 * existing file's mode): group and others never gain access beyond [current], except that read allows execute.
 */
export function applied(sender: number, current: number): number {
  const now = current & PERMISSION_BITS;
  const allowed = now | 0o700 | ((now & 0o044) >> 2);
  return safe(sender) & allowed;
}

export function isOwnerExecutable(mode: number): boolean {
  return (mode & OWNER_EXECUTE) !== 0;
}

/** `rwxr-x---` style, as `ls -l` shows it. */
export function describe(mode: number): string {
  let out = "";
  for (const shift of [6, 3, 0]) {
    const bits = (mode >> shift) & 0b111;
    out += (bits & 0b100 ? "r" : "-") + (bits & 0b010 ? "w" : "-") + (bits & 0b001 ? "x" : "-");
  }
  return out;
}
