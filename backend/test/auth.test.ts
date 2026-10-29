import { describe, expect, it } from "vitest";
import {
  CHALLENGE_LIFETIME_SECONDS,
  createChallenge,
  offlineUuid,
  verifyChallenge,
} from "../src/auth";

const SECRET = "unit-secret";
const NOW = 1_800_000_000;

describe("challenge", () => {
  it("is 40 lowercase hex chars and verifies before expiry", async () => {
    const { serverId, expiresAt } = await createChallenge(SECRET, NOW);
    expect(serverId).toMatch(/^[0-9a-f]{40}$/);
    expect(expiresAt).toBe(NOW + CHALLENGE_LIFETIME_SECONDS);
    expect(await verifyChallenge(SECRET, serverId, NOW)).toBe(true);
    expect(await verifyChallenge(SECRET, serverId, expiresAt)).toBe(true);
  });

  it("is rejected after expiry", async () => {
    const { serverId, expiresAt } = await createChallenge(SECRET, NOW);
    expect(await verifyChallenge(SECRET, serverId, expiresAt + 1)).toBe(false);
  });

  it("is rejected with another secret or tampered bytes", async () => {
    const { serverId } = await createChallenge(SECRET, NOW);
    expect(await verifyChallenge("other-secret", serverId, NOW)).toBe(false);
    const flip = (index: number) =>
      serverId.slice(0, index) + (serverId[index] === "0" ? "1" : "0") + serverId.slice(index + 1);
    expect(await verifyChallenge(SECRET, flip(0), NOW)).toBe(false); // nonce
    expect(await verifyChallenge(SECRET, flip(20), NOW)).toBe(false); // expiry
    expect(await verifyChallenge(SECRET, flip(39), NOW)).toBe(false); // mac
  });

  it("rejects a forged far-future expiry and malformed ids", async () => {
    const { serverId } = await createChallenge(SECRET, NOW + 10_000);
    expect(await verifyChallenge(SECRET, serverId, NOW)).toBe(false);
    expect(await verifyChallenge(SECRET, "xyz", NOW)).toBe(false);
    expect(await verifyChallenge(SECRET, serverId.toUpperCase(), NOW)).toBe(false);
  });
});

describe("offline uuid", () => {
  it("matches vanilla UUIDUtil.createOfflinePlayerUUID", async () => {
    expect(await offlineUuid("Alice")).toBe("10920508-d5d8-3eed-93d2-92f193afe7d7");
    expect(await offlineUuid("Bob")).toBe("faa5dca3-c3d4-354b-ae1b-dde9e5a14b3b");
  });
});
