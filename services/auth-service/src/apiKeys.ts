import { createHash, randomBytes } from "node:crypto";

const KEY_BYTES = 24;
const PREFIX_LENGTH = 11;

/** `ob_` + 48 hex chars (192 random bits), the same shape the in-process registry used. */
export function newApiKey(): string {
  return "ob_" + randomBytes(KEY_BYTES).toString("hex");
}

export function hashApiKey(key: string): Buffer {
  return createHash("sha256").update(key.trim(), "utf8").digest();
}

export function keyPrefix(key: string): string {
  return key.slice(0, PREFIX_LENGTH);
}
