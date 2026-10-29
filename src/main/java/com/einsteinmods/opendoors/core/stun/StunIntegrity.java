package com.einsteinmods.opendoors.core.stun;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * MESSAGE-INTEGRITY key with its HMAC-SHA1 instance cached (RFC 8489 §14.5). Create one per
 * credential and reuse it; not thread safe.
 *
 * <p>Short-term credentials (ICE, RFC 8489 §9.1.1) use the password as key; long-term credentials
 * (TURN, §9.2.2) use {@code MD5(username ":" realm ":" password)}. SASLprep is not applied: ICE and
 * TURN credentials minted by the backend are ASCII.
 */
public final class StunIntegrity {
  private static final String HMAC_SHA1 = "HmacSHA1";

  private final Mac mac;
  private final byte[] digest = new byte[StunMessage.INTEGRITY_SIZE];
  private final byte[] lengthBytes = new byte[2];

  private StunIntegrity(byte[] key) {
    try {
      mac = Mac.getInstance(HMAC_SHA1);
      mac.init(new SecretKeySpec(key, HMAC_SHA1));
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("HMAC-SHA1 unavailable", e);
    }
  }

  public static StunIntegrity ofKey(byte[] key) {
    return new StunIntegrity(key.clone());
  }

  /** ICE short-term credential: the key is the password (RFC 8445 §7.2.2). */
  public static StunIntegrity shortTerm(String password) {
    return new StunIntegrity(password.getBytes(StandardCharsets.UTF_8));
  }

  /** TURN long-term credential key (RFC 8489 §9.2.2). */
  public static StunIntegrity longTerm(String username, String realm, String password) {
    return new StunIntegrity(longTermKey(username, realm, password));
  }

  public static byte[] longTermKey(String username, String realm, String password) {
    try {
      return MessageDigest.getInstance("MD5")
          .digest((username + ':' + realm + ':' + password).getBytes(StandardCharsets.UTF_8));
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("MD5 unavailable", e);
    }
  }

  /**
   * HMAC over the message header with its length field replaced by {@code adjustedLength}, then the
   * bytes from the end of the header to {@code coveredEnd} (exclusive). Result lands in the
   * internal digest buffer (see {@link #digest()}).
   */
  void compute(byte[] message, int start, int adjustedLength, int coveredEnd) {
    lengthBytes[0] = (byte) (adjustedLength >>> 8);
    lengthBytes[1] = (byte) adjustedLength;
    mac.update(message, start, 2);
    mac.update(lengthBytes, 0, 2);
    mac.update(message, start + 4, coveredEnd - (start + 4));
    try {
      mac.doFinal(digest, 0);
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("HMAC output buffer too small", e);
    }
  }

  byte[] digest() {
    return digest;
  }
}
