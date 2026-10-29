import { describe, expect, it } from "vitest";
import { HEADERS, verifyCertificate } from "../src/certificate";
import { JAVA_PROOF_VECTOR } from "./java-proof-vector";

function headersOf(overrides: Record<string, string> = {}): Headers {
  const v = JAVA_PROOF_VECTOR;
  return new Headers({
    [HEADERS.profile]: v[HEADERS.profile],
    [HEADERS.key]: v[HEADERS.key],
    [HEADERS.expiry]: v[HEADERS.expiry],
    [HEADERS.keySignature]: v[HEADERS.keySignature],
    [HEADERS.signature]: v[HEADERS.signature],
    ...overrides,
  });
}

describe("certificate proof made by the game (Java)", () => {
  it("verifies", async () => {
    expect(
      await verifyCertificate(headersOf(), JAVA_PROOF_VECTOR.serverId, Date.now(), [JAVA_PROOF_VECTOR.mojangKey]),
    ).toEqual({ uuid: JAVA_PROOF_VECTOR[HEADERS.profile] });
  });

  it("is bound to the challenge, the UUID, the expiry and the Mojang key", async () => {
    const trusted = [JAVA_PROOF_VECTOR.mojangKey];
    const { serverId } = JAVA_PROOF_VECTOR;
    expect(await verifyCertificate(headersOf(), "f".repeat(40), Date.now(), trusted)).toEqual({
      failure: "bad_signature",
    });
    expect(
      await verifyCertificate(
        headersOf({ [HEADERS.profile]: "11111111-2222-3333-4444-555555555555" }),
        serverId,
        Date.now(),
        trusted,
      ),
    ).toEqual({ failure: "untrusted_certificate" });
    expect(
      await verifyCertificate(headersOf({ [HEADERS.expiry]: "4102444800001" }), serverId, Date.now(), trusted),
    ).toEqual({ failure: "untrusted_certificate" });
    expect(await verifyCertificate(headersOf(), serverId, 4102444800000, trusted)).toEqual({
      failure: "expired_certificate",
    });
    expect(await verifyCertificate(headersOf(), serverId, Date.now(), [])).toEqual({
      failure: "untrusted_certificate",
    });
  });
});
