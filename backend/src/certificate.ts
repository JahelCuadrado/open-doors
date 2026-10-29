import { EMBEDDED_PLAYER_CERTIFICATE_KEYS } from "./mojang-keys";
import { outboundFetch } from "./outbound";

/**
 * Login by Mojang player certificate (the chat-signing key pair every account gets), replacing
 * hasJoined, which Mojang's session server refuses to Cloudflare Workers. Mirrors the game's
 * CertificateProof.java: change both together.
 *
 * Checks, all required:
 *  1. Mojang signed the certificate: SHA1withRSA (authlib MinecraftServicesKeyInfo) over vanilla's
 *     ProfilePublicKey.Data#signedPayload = UUID msb (8 B BE) + lsb (8 B BE) + expiresAt millis
 *     (8 B BE) + public key DER, with one of the playerCertificateKeys.
 *  2. The certificate has not expired.
 *  3. The challenge "opendoors-auth-v1\n<serverId>\n<uuid>" is signed with the certificate's key
 *     (SHA256withRSA, as ProfilePublicKey#createSignatureValidator).
 * The caller checks the challenge HMAC and expiry. The identity is the UUID; names are cosmetic.
 */
export const PUBLIC_KEYS_URL = "https://api.minecraftservices.com/publickeys";
const DOMAIN = "opendoors-auth-v1";
const UUID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;
const BASE64_PATTERN = /^[A-Za-z0-9+/]+={0,2}$/;
const EXPIRY_PATTERN = /^[0-9]{1,15}$/;
const MAX_KEY_BYTES = 1024;
const MAX_SIGNATURE_BYTES = 1024;
const MAX_TRUSTED_KEYS = 16;

export const HEADERS = {
  profile: "X-OpenDoors-Profile",
  key: "X-OpenDoors-Key",
  expiry: "X-OpenDoors-Key-Expiry",
  keySignature: "X-OpenDoors-Key-Signature",
  signature: "X-OpenDoors-Signature",
} as const;

/** Why a proof was refused (reported only as 403 to clients). */
export type CertificateFailure =
  | "malformed"
  | "untrusted_certificate"
  | "expired_certificate"
  | "bad_signature";

const mojangKeyCache = new Map<string, Promise<CryptoKey | null>>();
let trustedOverride: readonly string[] | null = null;

/** Test hook: replaces the trusted Mojang keys; null restores the normal ones. */
export function setTrustedPlayerCertificateKeys(keys: readonly string[] | null): void {
  trustedOverride = keys;
}

function fromBase64(text: string, maxBytes: number): Uint8Array | null {
  if (text.length === 0 || text.length > Math.ceil(maxBytes / 3) * 4 || !BASE64_PATTERN.test(text)) {
    return null;
  }
  try {
    const binary = atob(text);
    const bytes = new Uint8Array(binary.length);
    for (let i = 0; i < binary.length; i++) bytes[i] = binary.charCodeAt(i);
    return bytes.length <= maxBytes ? bytes : null;
  } catch {
    return null;
  }
}

function mojangKey(base64: string): Promise<CryptoKey | null> {
  let key = mojangKeyCache.get(base64);
  if (key === undefined) {
    const der = fromBase64(base64, 4096);
    key =
      der === null
        ? Promise.resolve(null)
        : crypto.subtle
            .importKey("spki", der, { name: "RSASSA-PKCS1-v1_5", hash: "SHA-1" }, false, ["verify"])
            .catch(() => null);
    mojangKeyCache.set(base64, key);
  }
  return key;
}

/** Vanilla ProfilePublicKey.Data#signedPayload. */
export function signedPayload(uuid: string, expiresAtMillis: number, keyDer: Uint8Array): Uint8Array {
  const hex = uuid.replaceAll("-", "");
  const payload = new Uint8Array(24 + keyDer.length);
  for (let i = 0; i < 16; i++) payload[i] = parseInt(hex.substring(i * 2, i * 2 + 2), 16);
  new DataView(payload.buffer).setBigUint64(16, BigInt(expiresAtMillis), false);
  payload.set(keyDer, 24);
  return payload;
}

export function challengePayload(serverId: string, uuid: string): Uint8Array {
  return new TextEncoder().encode(`${DOMAIN}\n${serverId}\n${uuid}`);
}

/** Verifies the proof headers for {@code serverId}; returns the proven UUID or why not. */
export async function verifyCertificate(
  headers: Headers,
  serverId: string,
  nowMillis: number,
  trustedKeys: readonly string[],
): Promise<{ uuid: string } | { failure: CertificateFailure }> {
  const uuid = headers.get(HEADERS.profile);
  const keyText = headers.get(HEADERS.key);
  const expiryText = headers.get(HEADERS.expiry);
  const keySignatureText = headers.get(HEADERS.keySignature);
  const signatureText = headers.get(HEADERS.signature);
  if (
    uuid === null ||
    !UUID_PATTERN.test(uuid) ||
    keyText === null ||
    expiryText === null ||
    !EXPIRY_PATTERN.test(expiryText) ||
    keySignatureText === null ||
    signatureText === null
  ) {
    return { failure: "malformed" };
  }
  const keyDer = fromBase64(keyText, MAX_KEY_BYTES);
  const keySignature = fromBase64(keySignatureText, MAX_SIGNATURE_BYTES);
  const signature = fromBase64(signatureText, MAX_SIGNATURE_BYTES);
  const expiresAt = Number(expiryText);
  if (keyDer === null || keySignature === null || signature === null) return { failure: "malformed" };

  const payload = signedPayload(uuid, expiresAt, keyDer);
  let trusted = false;
  for (const base64 of trustedKeys.slice(0, MAX_TRUSTED_KEYS)) {
    const key = await mojangKey(base64);
    if (key !== null && (await crypto.subtle.verify("RSASSA-PKCS1-v1_5", key, keySignature, payload))) {
      trusted = true;
      break;
    }
  }
  if (!trusted) return { failure: "untrusted_certificate" };
  if (expiresAt <= nowMillis) return { failure: "expired_certificate" };

  let playerKey: CryptoKey;
  try {
    playerKey = await crypto.subtle.importKey(
      "spki",
      keyDer,
      { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" },
      false,
      ["verify"],
    );
  } catch {
    return { failure: "malformed" };
  }
  const valid = await crypto.subtle.verify(
    "RSASSA-PKCS1-v1_5",
    playerKey,
    signature,
    challengePayload(serverId, uuid),
  );
  return valid ? { uuid } : { failure: "bad_signature" };
}

/** Trusted keys: the test override, else the refreshed set (if any), else the embedded one. */
export function trustedKeys(refreshed: readonly string[] | null): readonly string[] {
  if (trustedOverride !== null) return trustedOverride;
  return refreshed !== null && refreshed.length > 0 ? refreshed : EMBEDDED_PLAYER_CERTIFICATE_KEYS;
}

/**
 * Cron: fetches the current playerCertificateKeys. Returns null when the service cannot be reached
 * or answers something unexpected (the previous or embedded keys stay in use).
 */
export async function fetchPlayerCertificateKeys(): Promise<string[] | null> {
  let response: Response;
  try {
    response = await outboundFetch(PUBLIC_KEYS_URL);
  } catch {
    return null;
  }
  if (!response.ok) return null;
  let body: { playerCertificateKeys?: unknown };
  try {
    body = (await response.json()) as { playerCertificateKeys?: unknown };
  } catch {
    return null;
  }
  const list = body.playerCertificateKeys;
  if (!Array.isArray(list) || list.length === 0 || list.length > MAX_TRUSTED_KEYS) return null;
  const keys: string[] = [];
  for (const entry of list) {
    const key = (entry as { publicKey?: unknown } | null)?.publicKey;
    if (typeof key !== "string" || (await mojangKey(key)) === null) return null;
    keys.push(key);
  }
  return keys;
}
