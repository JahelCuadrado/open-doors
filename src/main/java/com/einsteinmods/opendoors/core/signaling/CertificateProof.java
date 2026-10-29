package com.einsteinmods.opendoors.core.signaling;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.PrivateKey;
import java.security.Signature;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Proof of identity sent to the backend when the socket opens: the account's Mojang-signed player
 * certificate (the chat-signing key pair, vanilla {@code ProfilePublicKey.Data}) plus a signature
 * of the backend's challenge with that certificate's private key. The backend checks Mojang's
 * signature over the certificate (vanilla {@code ProfilePublicKey.Data#signedPayload}), the expiry,
 * and the challenge signature; nothing is sent to Mojang and the access token never leaves the
 * game. Mirrors {@code backend/src/certificate.ts}: change both together.
 *
 * @param profileId the account UUID the certificate is bound to
 * @param publicKeyDer the player public key, X.509 DER
 * @param keyExpiresAtMillis certificate expiry, epoch milliseconds
 * @param keySignature Mojang's signature over the certificate ({@code signature_v2})
 * @param challengeSignature SHA256withRSA over {@link #challengePayload}
 */
public record CertificateProof(
    UUID profileId,
    byte[] publicKeyDer,
    long keyExpiresAtMillis,
    byte[] keySignature,
    byte[] challengeSignature) {
  /**
   * Player-key signature algorithm, as vanilla {@code ProfilePublicKey#createSignatureValidator}.
   */
  public static final String SIGNATURE_ALGORITHM = "SHA256withRSA";

  public static final String PROFILE_HEADER = "X-OpenDoors-Profile";
  public static final String KEY_HEADER = "X-OpenDoors-Key";
  public static final String KEY_EXPIRY_HEADER = "X-OpenDoors-Key-Expiry";
  public static final String KEY_SIGNATURE_HEADER = "X-OpenDoors-Key-Signature";
  public static final String CHALLENGE_SIGNATURE_HEADER = "X-OpenDoors-Signature";

  /**
   * Domain prefix: vanilla chat signatures start with a big-endian int 1, so a signature made for
   * chat can never be replayed as this proof (and the other way round).
   */
  private static final String DOMAIN = "opendoors-auth-v1";

  /** The exact bytes signed for {@code serverId}: {@code opendoors-auth-v1\n<serverId>\n<uuid>}. */
  public static byte[] challengePayload(String serverId, UUID profileId) {
    return (DOMAIN + "\n" + serverId + "\n" + profileId).getBytes(StandardCharsets.US_ASCII);
  }

  /** Signs the challenge with the certificate's private key. */
  public static CertificateProof sign(
      PrivateKey privateKey,
      UUID profileId,
      byte[] publicKeyDer,
      long keyExpiresAtMillis,
      byte[] keySignature,
      String serverId)
      throws GeneralSecurityException {
    if (!Validation.isServerId(serverId)) {
      throw new IllegalArgumentException("invalid challenge");
    }
    Signature signer = Signature.getInstance(SIGNATURE_ALGORITHM);
    signer.initSign(privateKey);
    signer.update(challengePayload(serverId, profileId));
    return new CertificateProof(
        profileId, publicKeyDer, keyExpiresAtMillis, keySignature, signer.sign());
  }

  /** The WebSocket upgrade headers carrying this proof (base64 without line breaks). */
  public Map<String, String> headers() {
    Base64.Encoder base64 = Base64.getEncoder();
    Map<String, String> headers = new LinkedHashMap<>();
    headers.put(PROFILE_HEADER, profileId.toString());
    headers.put(KEY_HEADER, base64.encodeToString(publicKeyDer));
    headers.put(KEY_EXPIRY_HEADER, Long.toString(keyExpiresAtMillis));
    headers.put(KEY_SIGNATURE_HEADER, base64.encodeToString(keySignature));
    headers.put(CHALLENGE_SIGNATURE_HEADER, base64.encodeToString(challengeSignature));
    return headers;
  }
}
