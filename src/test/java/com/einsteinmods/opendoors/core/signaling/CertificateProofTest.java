package com.einsteinmods.opendoors.core.signaling;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CertificateProofTest {
  private static final String SERVER_ID = "00112233445566778899aabbccddeeff00112233";
  private static final UUID PROFILE = UUID.fromString("10920508-d5d8-3eed-93d2-92f193afe7d7");

  @Test
  void signsTheDomainSeparatedChallengeWithThePlayerKey() throws Exception {
    KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
    generator.initialize(2048);
    KeyPair player = generator.generateKeyPair();
    byte[] mojangSignature = {1, 2, 3};
    CertificateProof proof =
        CertificateProof.sign(
            player.getPrivate(),
            PROFILE,
            player.getPublic().getEncoded(),
            1_800_000_000_000L,
            mojangSignature,
            SERVER_ID);

    assertArrayEquals(
        ("opendoors-auth-v1\n" + SERVER_ID + "\n" + PROFILE).getBytes(StandardCharsets.US_ASCII),
        CertificateProof.challengePayload(SERVER_ID, PROFILE));
    Signature verifier = Signature.getInstance("SHA256withRSA");
    verifier.initVerify(player.getPublic());
    verifier.update(CertificateProof.challengePayload(SERVER_ID, PROFILE));
    assertTrue(verifier.verify(proof.challengeSignature()));
    verifier.initVerify(player.getPublic());
    verifier.update(CertificateProof.challengePayload(SERVER_ID, UUID.randomUUID()));
    assertFalse(verifier.verify(proof.challengeSignature()), "bound to the profile UUID");

    Map<String, String> headers = proof.headers();
    assertEquals(PROFILE.toString(), headers.get(CertificateProof.PROFILE_HEADER));
    assertEquals("1800000000000", headers.get(CertificateProof.KEY_EXPIRY_HEADER));
    assertArrayEquals(
        player.getPublic().getEncoded(),
        Base64.getDecoder().decode(headers.get(CertificateProof.KEY_HEADER)));
    assertEquals("AQID", headers.get(CertificateProof.KEY_SIGNATURE_HEADER));
    assertTrue(
        headers.values().stream().mapToInt(String::length).sum() < 4_096,
        "fits comfortably in upgrade headers");
  }

  @Test
  void refusesAnInvalidChallenge() throws Exception {
    KeyPair player = KeyPairGenerator.getInstance("RSA").generateKeyPair();
    assertThrows(
        IllegalArgumentException.class,
        () ->
            CertificateProof.sign(
                player.getPrivate(), PROFILE, new byte[0], 0, new byte[0], "not-hex"));
  }
}
