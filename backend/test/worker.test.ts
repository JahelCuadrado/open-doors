import { env } from "cloudflare:test";
import { afterEach, describe, expect, it } from "vitest";
import { createChallenge } from "../src/auth";
import worker, { resetKeysCache } from "../src/index";
import {
  HEADERS,
  PUBLIC_KEYS_URL,
  challengePayload,
  setTrustedPlayerCertificateKeys,
  signedPayload,
} from "../src/certificate";
import { EMBEDDED_PLAYER_CERTIFICATE_KEYS } from "../src/mojang-keys";
import { setOutboundFetch } from "../src/outbound";
import { createExecutionContext, createScheduledController, waitOnExecutionContext } from "cloudflare:test";
import { TestClient, devEnv, freshIp, freshName } from "./helpers";

const PROFILE_UUID = "01234567-89ab-cdef-0123-456789abcdef";
const DAY_MS = 24 * 60 * 60 * 1000;

function base64(bytes: ArrayBuffer | Uint8Array): string {
  let binary = "";
  for (const b of new Uint8Array(bytes)) binary += String.fromCharCode(b);
  return btoa(binary);
}

async function rsa(hash: "SHA-1" | "SHA-256"): Promise<CryptoKeyPair> {
  return (await crypto.subtle.generateKey(
    { name: "RSASSA-PKCS1-v1_5", modulusLength: 2048, publicExponent: new Uint8Array([1, 0, 1]), hash },
    true,
    ["sign", "verify"],
  )) as CryptoKeyPair;
}

/** A stand-in "Mojang" signing key and one player certificate it signed. */
interface Identity {
  mojangPublic: string;
  mojang: CryptoKeyPair;
  player: CryptoKeyPair;
  playerDer: Uint8Array;
}

let identity: Identity | null = null;

async function testIdentity(): Promise<Identity> {
  if (identity === null) {
    const mojang = await rsa("SHA-1");
    const player = await rsa("SHA-256");
    identity = {
      mojang,
      player,
      mojangPublic: base64((await crypto.subtle.exportKey("spki", mojang.publicKey)) as ArrayBuffer),
      playerDer: new Uint8Array((await crypto.subtle.exportKey("spki", player.publicKey)) as ArrayBuffer),
    };
  }
  return identity;
}

interface ProofOptions {
  uuid?: string;
  signedUuid?: string;
  expiresAt?: number;
  mojangKey?: CryptoKey;
  challengeFor?: string;
  playerKey?: CryptoKey;
}

/** The upgrade headers the game sends (CertificateProof.java). */
async function proofHeaders(serverId: string, options: ProofOptions = {}): Promise<Record<string, string>> {
  const id = await testIdentity();
  const uuid = options.uuid ?? PROFILE_UUID;
  const expiresAt = options.expiresAt ?? Date.now() + DAY_MS;
  const keySignature = await crypto.subtle.sign(
    "RSASSA-PKCS1-v1_5",
    options.mojangKey ?? id.mojang.privateKey,
    signedPayload(options.signedUuid ?? uuid, expiresAt, id.playerDer),
  );
  const signature = await crypto.subtle.sign(
    "RSASSA-PKCS1-v1_5",
    options.playerKey ?? id.player.privateKey,
    challengePayload(options.challengeFor ?? serverId, uuid),
  );
  return {
    [HEADERS.profile]: uuid,
    [HEADERS.key]: base64(id.playerDer),
    [HEADERS.expiry]: String(expiresAt),
    [HEADERS.keySignature]: base64(keySignature),
    [HEADERS.signature]: base64(signature),
  };
}

function challengeRequest(ip: string): Request {
  return new Request("http://hub.test/v1/auth/challenge", {
    method: "POST",
    headers: { "CF-Connecting-IP": ip },
  });
}

function wsRequest(query: string, ip = freshIp(), extra: Record<string, string> = {}): Request {
  return new Request(`http://hub.test/v1/ws?${query}`, {
    headers: { Upgrade: "websocket", "CF-Connecting-IP": ip, ...extra },
  });
}

async function freshServerId(): Promise<string> {
  return (await createChallenge(env.AUTH_SECRET, Math.floor(Date.now() / 1000))).serverId;
}

afterEach(() => {
  setOutboundFetch(null);
  setTrustedPlayerCertificateKeys(null);
  resetKeysCache();
});

describe("POST /v1/auth/challenge", () => {
  it("returns a challenge", async () => {
    const response = await worker.fetch(challengeRequest(freshIp()), env);
    expect(response.status).toBe(200);
    const body = (await response.json()) as { serverId: string; expiresAt: number };
    expect(body.serverId).toMatch(/^[0-9a-f]{40}$/);
    expect(body.expiresAt).toBeGreaterThan(Date.now() / 1000);
  });

  it("answers 429 with Retry-After after 10 requests from one IP", async () => {
    const ip = freshIp();
    for (let i = 0; i < 10; i++) {
      expect((await worker.fetch(challengeRequest(ip), env)).status).toBe(200);
    }
    const limited = await worker.fetch(challengeRequest(ip), env);
    expect(limited.status).toBe(429);
    const retryAfter = Number(limited.headers.get("Retry-After"));
    expect(retryAfter).toBeGreaterThanOrEqual(1);
    expect(retryAfter).toBeLessThanOrEqual(60);
    expect(((await limited.json()) as { retryAfter: number }).retryAfter).toBe(retryAfter);
    // Another IP is unaffected.
    expect((await worker.fetch(challengeRequest(freshIp()), env)).status).toBe(200);
  });

  it("rejects other methods and unknown paths", async () => {
    expect((await worker.fetch(new Request("http://hub.test/v1/auth/challenge"), env)).status).toBe(405);
    expect((await worker.fetch(new Request("http://hub.test/nope"), env)).status).toBe(404);
  });
});

describe("GET /v1/ws", () => {
  it("needs a websocket upgrade and valid parameters", async () => {
    expect((await worker.fetch(new Request("http://hub.test/v1/ws?name=A"), env)).status).toBe(426);
    expect((await worker.fetch(wsRequest("name=bad-name!&serverId=00"), env)).status).toBe(400);
    expect((await worker.fetch(wsRequest("name=Steve&serverId=XYZ"), env)).status).toBe(400);
  });

  it("rejects an expired or forged serverId before looking at the certificate", async () => {
    const calls: string[] = [];
    setOutboundFetch(async (url) => {
      calls.push(url);
      return new Response(null, { status: 500 });
    });
    const expired = (await createChallenge(env.AUTH_SECRET, Math.floor(Date.now() / 1000) - 1000))
      .serverId;
    expect(
      (await worker.fetch(wsRequest(`name=Steve&serverId=${expired}`, freshIp(), await proofHeaders(expired)), env))
        .status,
    ).toBe(401);
    const forged = (await createChallenge("wrong", Math.floor(Date.now() / 1000))).serverId;
    expect(
      (await worker.fetch(wsRequest(`name=Steve&serverId=${forged}`, freshIp(), await proofHeaders(forged)), env))
        .status,
    ).toBe(401);
    expect(calls).toEqual([]);
  });

  it("authenticates a Mojang-signed certificate and forwards its UUID", async () => {
    setTrustedPlayerCertificateKeys([(await testIdentity()).mojangPublic]);
    const serverId = await freshServerId();
    const calls: string[] = [];
    setOutboundFetch(async (url) => {
      calls.push(url);
      return new Response("unexpected", { status: 500 });
    });
    const response = await worker.fetch(
      wsRequest(`name=Steve&serverId=${serverId}`, freshIp(), await proofHeaders(serverId)),
      env,
    );
    expect(response.status).toBe(101);
    const client = new TestClient(response.webSocket!);
    expect(await client.next("welcome")).toEqual({ t: "welcome", uuid: PROFILE_UUID, name: "Steve" });
    expect(calls).toEqual([]); // nothing asked to Mojang at login
    client.close();
  });

  it("refuses wrong Mojang signatures, expired certificates, bad challenge signatures and UUID swaps", async () => {
    const id = await testIdentity();
    setTrustedPlayerCertificateKeys([id.mojangPublic]);
    const other = await rsa("SHA-1");
    const otherPlayer = await rsa("SHA-256");
    const cases: ProofOptions[] = [
      { mojangKey: other.privateKey },
      { expiresAt: Date.now() - 1000 },
      { playerKey: otherPlayer.privateKey },
      { challengeFor: "ffffffffffffffffffffffffffffffffffffffff" },
      { signedUuid: "11111111-2222-3333-4444-555555555555" },
    ];
    for (const options of cases) {
      const serverId = await freshServerId();
      const response = await worker.fetch(
        wsRequest(`name=Steve&serverId=${serverId}`, freshIp(), await proofHeaders(serverId, options)),
        env,
      );
      expect(response.status, JSON.stringify(Object.keys(options))).toBe(403);
    }
  });

  it("refuses missing or malformed proofs", async () => {
    setTrustedPlayerCertificateKeys([(await testIdentity()).mojangPublic]);
    const serverId = await freshServerId();
    const good = await proofHeaders(serverId);
    const broken: Record<string, string>[] = [
      {},
      { ...good, [HEADERS.profile]: "not-a-uuid" },
      { ...good, [HEADERS.key]: "%%%" },
      { ...good, [HEADERS.expiry]: "-5" },
      { ...good, [HEADERS.keySignature]: "A".repeat(5000) },
      { ...good, [HEADERS.signature]: "" },
      { ...good, [HEADERS.key]: btoa("not a key") },
    ];
    for (const headers of broken) {
      const response = await worker.fetch(
        wsRequest(`name=Steve&serverId=${serverId}`, freshIp(), headers),
        env,
      );
      expect(response.status).toBe(403);
    }
  });

  it("trusts the embedded Mojang keys by default and the cron's refreshed keys once stored", async () => {
    for (const key of EMBEDDED_PLAYER_CERTIFICATE_KEYS) {
      await expect(
        crypto.subtle.importKey(
          "spki",
          Uint8Array.from(atob(key), (c) => c.charCodeAt(0)),
          { name: "RSASSA-PKCS1-v1_5", hash: "SHA-1" },
          false,
          ["verify"],
        ),
      ).resolves.toBeDefined();
    }
    // The test identity is not trusted with the embedded keys...
    let serverId = await freshServerId();
    expect(
      (await worker.fetch(wsRequest(`name=Steve&serverId=${serverId}`, freshIp(), await proofHeaders(serverId)), env))
        .status,
    ).toBe(403);
    // ...until the cron stores a key set that contains it.
    const id = await testIdentity();
    setOutboundFetch(async (url) =>
      url === PUBLIC_KEYS_URL
        ? Response.json({ playerCertificateKeys: [{ publicKey: id.mojangPublic }] })
        : new Response("x", { status: 500 }),
    );
    const ctx = createExecutionContext();
    await worker.scheduled(createScheduledController({ scheduledTime: Date.now(), cron: "*/15 * * * *" }), env, ctx);
    await waitOnExecutionContext(ctx);
    resetKeysCache();
    serverId = await freshServerId();
    const response = await worker.fetch(
      wsRequest(`name=Steve&serverId=${serverId}`, freshIp(), await proofHeaders(serverId)),
      env,
    );
    expect(response.status).toBe(101);
    new TestClient(response.webSocket!).close();
    // A broken key list from the service is ignored (the stored set stays).
    setOutboundFetch(async () => Response.json({ playerCertificateKeys: [{ publicKey: "bad" }] }));
    const again = createExecutionContext();
    await worker.scheduled(createScheduledController({ scheduledTime: Date.now(), cron: "*/15 * * * *" }), env, again);
    await waitOnExecutionContext(again);
    resetKeysCache();
    serverId = await freshServerId();
    expect(
      (await worker.fetch(wsRequest(`name=Steve&serverId=${serverId}`, freshIp(), await proofHeaders(serverId)), env))
        .status,
    ).toBe(101);
  });

  it("accepts offline identities only with DEV_AUTH=1", async () => {
    const name = freshName("Dev");
    expect((await worker.fetch(wsRequest(`dev=1&name=${name}`), env)).status).toBe(400);
    const response = await worker.fetch(wsRequest(`dev=1&name=Alice`), devEnv());
    expect(response.status).toBe(101);
    const client = new TestClient(response.webSocket!);
    expect(await client.next("welcome")).toEqual({
      t: "welcome",
      uuid: "10920508-d5d8-3eed-93d2-92f193afe7d7",
      name: "Alice",
    });
    client.close();
  });

  it("ignores identity headers sent by the client", async () => {
    const request = new Request("http://hub.test/v1/ws?dev=1&name=Bob", {
      headers: {
        Upgrade: "websocket",
        "CF-Connecting-IP": freshIp(),
        "X-OD-UUID": "00000000-0000-0000-0000-000000000000",
      },
    });
    const client = new TestClient((await worker.fetch(request, devEnv())).webSocket!);
    expect((await client.next("welcome")).uuid).toBe("faa5dca3-c3d4-354b-ae1b-dde9e5a14b3b");
    client.close();
  });
});
