package com.einsteinmods.opendoors.core.signaling;

import java.util.regex.Pattern;

/**
 * Whitelist validators shared by every message the backend sends or receives. Mirrors {@code
 * backend/src/validate.ts}: change both together.
 */
public final class Validation {
  /** Dashed lowercase UUID, as the hub writes it. */
  private static final Pattern UUID =
      Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");

  /** Vanilla player names (cosmetic: the hub relays the name each client gave; never trusted). */
  private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_]{1,16}");

  /** ICE ufrag/pwd alphabet (RFC 8445 ice-char), lengths as RFC 8839. */
  private static final Pattern UFRAG = Pattern.compile("[A-Za-z0-9+/]{4,32}");

  private static final Pattern PASSWORD = Pattern.compile("[A-Za-z0-9+/]{22,64}");

  /** Challenge id: 40 lowercase hex characters (nonce, expiry and truncated HMAC). */
  private static final Pattern SERVER_ID = Pattern.compile("[0-9a-f]{40}");

  /** TURN url as minted by Cloudflare: {@code turn:host:port?transport=udp}. */
  private static final Pattern TURN_URL =
      Pattern.compile("turn:([a-z0-9.-]{1,253}):([0-9]{1,5})\\?transport=udp");

  private static final Pattern TURN_SECRET = Pattern.compile("[A-Za-z0-9+/=_:.-]{1,256}");

  public static final int MAX_PORT = 65_535;
  public static final int MAX_CANDIDATES = 8;
  public static final int MAX_UUID_LIST = 1_000;
  public static final int MAX_TURN_URLS = 8;
  public static final int MAX_MESSAGE_CHARS = 4_096;
  public static final long MAX_CONV = 0xFFFF_FFFFL;
  public static final int MAX_RETRY_AFTER_SECONDS = 86_400;

  /**
   * KCP writes conv little-endian, so its low byte is the first byte of every data datagram; the
   * hub keeps it in 0x80-0xBF, clear of STUN and ChannelData (RFC 7983).
   */
  private static final int CONV_LOW_BYTE_MASK = 0xC0;

  private static final int CONV_LOW_BYTE_TAG = 0x80;

  private Validation() {}

  public static boolean isUuid(String value) {
    return value != null && UUID.matcher(value).matches();
  }

  public static boolean isName(String value) {
    return value != null && NAME.matcher(value).matches();
  }

  public static boolean isUfrag(String value) {
    return value != null && UFRAG.matcher(value).matches();
  }

  public static boolean isPassword(String value) {
    return value != null && PASSWORD.matcher(value).matches();
  }

  public static boolean isServerId(String value) {
    return value != null && SERVER_ID.matcher(value).matches();
  }

  public static boolean isTurnUrl(String value) {
    if (value == null) {
      return false;
    }
    var matcher = TURN_URL.matcher(value);
    return matcher.matches() && isPort(Integer.parseInt(matcher.group(2)));
  }

  public static boolean isTurnSecret(String value) {
    return value != null && TURN_SECRET.matcher(value).matches();
  }

  public static boolean isPort(long value) {
    return value >= 1 && value <= MAX_PORT;
  }

  public static boolean isConv(long value) {
    return value >= 1 && value <= MAX_CONV && (value & CONV_LOW_BYTE_MASK) == CONV_LOW_BYTE_TAG;
  }

  /**
   * IPv4 dotted quad or IPv6 literal, checked without DNS: {@code InetAddress.getByName} would
   * resolve hostnames, so only literal characters are accepted before parsing.
   */
  public static boolean isIpLiteral(String value) {
    if (value == null || value.isEmpty() || value.length() > 45) {
      return false;
    }
    return value.indexOf(':') >= 0 ? isIpv6(value) : isIpv4(value);
  }

  private static boolean isIpv4(String value) {
    String[] parts = value.split("\\.", -1);
    if (parts.length != 4) {
      return false;
    }
    for (String part : parts) {
      if (part.isEmpty() || part.length() > 3 || !part.chars().allMatch(Character::isDigit)) {
        return false;
      }
      if (Integer.parseInt(part) > 255) {
        return false;
      }
    }
    return true;
  }

  private static boolean isIpv6(String value) {
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      boolean hex = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
      if (!hex && c != ':' && c != '.') {
        return false;
      }
    }
    try {
      // Literal-only input: no lookup happens for an IPv6 literal.
      return java.net.InetAddress.getByName("[" + value + "]") instanceof java.net.Inet6Address;
    } catch (java.net.UnknownHostException exception) {
      return false;
    }
  }
}
