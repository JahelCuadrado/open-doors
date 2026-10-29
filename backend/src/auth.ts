/**
 * Stateless login challenges. A challenge is a 40-char lowercase hex "serverId" (the same charset
 * and length class as vanilla login digests, which Mojang's join endpoint accepts):
 * hex(nonce 8 B) + hex(expiry u32 BE, seconds) + hex(first 8 B of HMAC-SHA256(secret, nonce||expiry)).
 */
export const CHALLENGE_LIFETIME_SECONDS = 120;
const NONCE_BYTES = 8;
const EXPIRY_BYTES = 4;
const MAC_BYTES = 8;
const PAYLOAD_BYTES = NONCE_BYTES + EXPIRY_BYTES;
const SERVER_ID_PATTERN = /^[0-9a-f]{40}$/;

const keyCache = new Map<string, Promise<CryptoKey>>();

function hmacKey(secret: string): Promise<CryptoKey> {
  let key = keyCache.get(secret);
  if (key === undefined) {
    key = crypto.subtle.importKey(
      "raw",
      new TextEncoder().encode(secret),
      { name: "HMAC", hash: "SHA-256" },
      false,
      ["sign"],
    );
    keyCache.set(secret, key);
  }
  return key;
}

export function toHex(bytes: Uint8Array): string {
  let out = "";
  for (const b of bytes) out += b.toString(16).padStart(2, "0");
  return out;
}

function fromHex(hex: string): Uint8Array {
  const out = new Uint8Array(hex.length / 2);
  for (let i = 0; i < out.length; i++) out[i] = parseInt(hex.substring(i * 2, i * 2 + 2), 16);
  return out;
}

async function mac(secret: string, payload: Uint8Array): Promise<Uint8Array> {
  const signature = await crypto.subtle.sign("HMAC", await hmacKey(secret), payload);
  return new Uint8Array(signature, 0, MAC_BYTES);
}

/** Creates a challenge valid until {@code nowSeconds + CHALLENGE_LIFETIME_SECONDS}. */
export async function createChallenge(
  secret: string,
  nowSeconds: number,
): Promise<{ serverId: string; expiresAt: number }> {
  const payload = new Uint8Array(PAYLOAD_BYTES);
  crypto.getRandomValues(payload.subarray(0, NONCE_BYTES));
  const expiresAt = nowSeconds + CHALLENGE_LIFETIME_SECONDS;
  new DataView(payload.buffer).setUint32(NONCE_BYTES, expiresAt >>> 0, false);
  return { serverId: toHex(payload) + toHex(await mac(secret, payload)), expiresAt };
}

/** Constant-time comparison of two equal-length byte arrays. */
export function constantTimeEquals(a: Uint8Array, b: Uint8Array): boolean {
  if (a.length !== b.length) return false;
  let diff = 0;
  for (let i = 0; i < a.length; i++) diff |= (a[i] ?? 0) ^ (b[i] ?? 0);
  return diff === 0;
}

/** True when the challenge was issued by us and has not expired. */
export async function verifyChallenge(
  secret: string,
  serverId: string,
  nowSeconds: number,
): Promise<boolean> {
  if (!SERVER_ID_PATTERN.test(serverId)) return false;
  const bytes = fromHex(serverId);
  const payload = bytes.subarray(0, PAYLOAD_BYTES);
  const expiresAt = new DataView(bytes.buffer).getUint32(NONCE_BYTES, false);
  if (expiresAt < nowSeconds || expiresAt > nowSeconds + CHALLENGE_LIFETIME_SECONDS) return false;
  return constantTimeEquals(await mac(secret, payload), bytes.subarray(PAYLOAD_BYTES));
}

/** Formats 32 hex digits as a dashed lowercase UUID. */
export function dashUuid(hex32: string): string {
  const h = hex32.toLowerCase();
  return `${h.slice(0, 8)}-${h.slice(8, 12)}-${h.slice(12, 16)}-${h.slice(16, 20)}-${h.slice(20)}`;
}

/** Vanilla {@code UUIDUtil.createOfflinePlayerUUID}: name-based v3 UUID of "OfflinePlayer:" + name. */
export async function offlineUuid(name: string): Promise<string> {
  const digest = new Uint8Array(
    await crypto.subtle.digest("MD5", new TextEncoder().encode("OfflinePlayer:" + name)),
  );
  digest[6] = ((digest[6] ?? 0) & 0x0f) | 0x30;
  digest[8] = ((digest[8] ?? 0) & 0x3f) | 0x80;
  return dashUuid(toHex(digest));
}
