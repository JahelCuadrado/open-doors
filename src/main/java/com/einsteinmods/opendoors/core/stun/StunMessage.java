package com.einsteinmods.opendoors.core.stun;

/**
 * STUN message constants and type helpers (RFC 8489, which obsoletes RFC 5389), with the ICE (RFC
 * 8445 §16.1) and TURN (RFC 8656 §18) methods and attributes this mod uses.
 *
 * <p>Header layout (RFC 8489 §5): 2 bytes type (top two bits zero), 2 bytes attribute length, 4
 * bytes magic cookie, 12 bytes transaction id; attributes are TLVs padded to 4 bytes (§14).
 */
public final class StunMessage {
  public static final int HEADER_SIZE = 20;
  public static final int TRANSACTION_ID_SIZE = 12;
  public static final int MAGIC_COOKIE = 0x2112A442;
  public static final int ATTRIBUTE_HEADER_SIZE = 4;

  // Methods (RFC 8489 §18.2, RFC 8656 §17).
  public static final int METHOD_BINDING = 0x001;
  public static final int METHOD_ALLOCATE = 0x003;
  public static final int METHOD_REFRESH = 0x004;
  public static final int METHOD_SEND = 0x006;
  public static final int METHOD_DATA = 0x007;
  public static final int METHOD_CREATE_PERMISSION = 0x008;
  public static final int METHOD_CHANNEL_BIND = 0x009;

  // Classes, already in their message-type bit positions (RFC 8489 §5: C0 = bit 4, C1 = bit 8).
  public static final int CLASS_REQUEST = 0x000;
  public static final int CLASS_INDICATION = 0x010;
  public static final int CLASS_SUCCESS = 0x100;
  public static final int CLASS_ERROR = 0x110;

  // Attributes (RFC 8489 §18.3, RFC 8445 §16.1, RFC 8656 §18).
  public static final int ATTR_MAPPED_ADDRESS = 0x0001;
  public static final int ATTR_USERNAME = 0x0006;
  public static final int ATTR_MESSAGE_INTEGRITY = 0x0008;
  public static final int ATTR_ERROR_CODE = 0x0009;
  public static final int ATTR_CHANNEL_NUMBER = 0x000C;
  public static final int ATTR_LIFETIME = 0x000D;
  public static final int ATTR_XOR_PEER_ADDRESS = 0x0012;
  public static final int ATTR_DATA = 0x0013;
  public static final int ATTR_REALM = 0x0014;
  public static final int ATTR_NONCE = 0x0015;
  public static final int ATTR_XOR_RELAYED_ADDRESS = 0x0016;
  public static final int ATTR_REQUESTED_TRANSPORT = 0x0019;
  public static final int ATTR_XOR_MAPPED_ADDRESS = 0x0020;
  public static final int ATTR_PRIORITY = 0x0024;
  public static final int ATTR_USE_CANDIDATE = 0x0025;
  public static final int ATTR_SOFTWARE = 0x8022;
  public static final int ATTR_FINGERPRINT = 0x8028;
  public static final int ATTR_ICE_CONTROLLED = 0x8029;
  public static final int ATTR_ICE_CONTROLLING = 0x802A;

  /** MESSAGE-INTEGRITY value size: HMAC-SHA1 (RFC 8489 §14.5). */
  public static final int INTEGRITY_SIZE = 20;

  /** FINGERPRINT XOR constant (RFC 8489 §14.7). */
  public static final int FINGERPRINT_XOR = 0x5354554E;

  /** REQUESTED-TRANSPORT protocol number for UDP (RFC 8656 §18.7). */
  public static final int TRANSPORT_UDP = 17;

  // Error codes used by ICE and TURN.
  public static final int ERROR_BAD_REQUEST = 400;
  public static final int ERROR_UNAUTHORIZED = 401;
  public static final int ERROR_ROLE_CONFLICT = 487;
  public static final int ERROR_STALE_NONCE = 438;
  public static final int ERROR_ALLOCATION_MISMATCH = 437;

  private StunMessage() {}

  /** Builds a message type from a method and a class (RFC 8489 §5, figure 3). */
  public static int messageType(int method, int messageClass) {
    return (method & 0x000F) | (method & 0x0070) << 1 | (method & 0x0F80) << 2 | messageClass;
  }

  /** The method encoded in a message type. */
  public static int method(int messageType) {
    return (messageType & 0x000F) | (messageType & 0x00E0) >> 1 | (messageType & 0x3E00) >> 2;
  }

  /** The class bits of a message type, comparable with the {@code CLASS_*} constants. */
  public static int messageClass(int messageType) {
    return messageType & 0x0110;
  }

  /** Attribute value length rounded up to the 4-byte boundary (RFC 8489 §14). */
  public static int padded(int length) {
    return (length + 3) & ~3;
  }
}
