import { readFileSync } from "node:fs";
import { join, resolve } from "node:path";

/** The shared conformance vectors in spec/vectors (see spec/vectors/README.md). */
export const VECTORS_DIR = process.env.COPY_PASTA_VECTORS ?? resolve(__dirname, "../../../spec/vectors");

export interface ExpectedFile {
  path: string;
  content: string; // Base64
  mode: string | null; // octal
  executable: boolean;
}

export interface VectorCase {
  name: string;
  description: string;
  file: string;
  passphrase: string | null;
  maxMillis?: number;
  expect: {
    error?: string;
    files?: ExpectedFile[];
    encrypted?: boolean;
    blocked?: string[];
    blockedOnWindows?: string[];
    summary?: string;
  };
}

export interface ModeRule {
  sender: string;
  current: string;
  safe: string;
  applied: string;
}

export function loadCases(): VectorCase[] {
  return (JSON.parse(readFileSync(join(VECTORS_DIR, "manifest.json"), "utf8")) as { cases: VectorCase[] }).cases;
}

export function loadModeRules(): ModeRule[] {
  return (JSON.parse(readFileSync(join(VECTORS_DIR, "modes.json"), "utf8")) as { rules: ModeRule[] }).rules;
}

export function caseText(c: VectorCase): string {
  return readFileSync(join(VECTORS_DIR, c.file), "utf8");
}

export function octal(mode: number | null): string | null {
  return mode === null ? null : "0" + mode.toString(8).padStart(3, "0");
}
