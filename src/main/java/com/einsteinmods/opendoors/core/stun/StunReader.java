package com.einsteinmods.opendoors.core.stun;

import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;

/**
 * Zero-copy STUN message reader (RFC 8489 §5, §14): validates the framing once in {@link #wrap},
 * then walks attributes in place. Reuse one instance per thread; nothing is allocated except by
 * {@link #valueAsString()}.
 */
public final class StunReader {
  private static final int ERROR_CODE_HEADER = 4;
  private static final int ERROR_CLASS_MULTIPLIER = 100;
  private static final int ERROR_CLASS_MASK = 0x07;
  private static final int FINGERPRINT_ATTRIBUTE_SIZE = 8;
  private static final int TOP_BITS_MASK = 0xC0;

  private final CRC32 crc = new CRC32();
  private byte[] buffer = new byte[0];
  private int start;
  private int end;
  private int cursor;
  private int attributeType;
  private int valueOffset;
  private int valueLength;

  /**
   * Checks the STUN framing of {@code length} bytes at {@code offset} (RFC 8489 §6.3: top bits
   * zero, magic cookie, length a multiple of 4 that fits, well-formed attributes) and positions
   * before the first attribute. Returns false if this is not a well-formed STUN message.
   */
  public boolean wrap(byte[] data, int offset, int length) {
    if (length < StunMessage.HEADER_SIZE || (data[offset] & TOP_BITS_MASK) != 0) {
      return false;
    }
    int bodyLength = readShort(data, offset + 2);
    if ((bodyLength & 3) != 0
        || StunMessage.HEADER_SIZE + bodyLength > length
        || readInt(data, offset + 4) != StunMessage.MAGIC_COOKIE) {
      return false;
    }
    int limit = offset + StunMessage.HEADER_SIZE + bodyLength;
    for (int p = offset + StunMessage.HEADER_SIZE; p < limit; ) {
      if (p + StunMessage.ATTRIBUTE_HEADER_SIZE > limit) {
        return false;
      }
      int next = p + StunMessage.ATTRIBUTE_HEADER_SIZE + StunMessage.padded(readShort(data, p + 2));
      if (next > limit) {
        return false;
      }
      p = next;
    }
    buffer = data;
    start = offset;
    end = limit;
    rewind();
    return true;
  }

  public byte[] buffer() {
    return buffer;
  }

  public int offset() {
    return start;
  }

  /** Message size in bytes, header included. */
  public int length() {
    return end - start;
  }

  public int messageType() {
    return readShort(buffer, start);
  }

  public int method() {
    return StunMessage.method(messageType());
  }

  public int messageClass() {
    return StunMessage.messageClass(messageType());
  }

  public boolean transactionIdEquals(byte[] id, int offset) {
    for (int i = 0; i < StunMessage.TRANSACTION_ID_SIZE; i++) {
      if (buffer[start + 8 + i] != id[offset + i]) {
        return false;
      }
    }
    return true;
  }

  public void copyTransactionId(byte[] target, int offset) {
    System.arraycopy(buffer, start + 8, target, offset, StunMessage.TRANSACTION_ID_SIZE);
  }

  /** Back to before the first attribute. */
  public void rewind() {
    cursor = start + StunMessage.HEADER_SIZE;
    attributeType = -1;
    valueOffset = -1;
    valueLength = 0;
  }

  /** Advances to the next attribute; false at the end. */
  public boolean next() {
    if (cursor >= end) {
      return false;
    }
    attributeType = readShort(buffer, cursor);
    valueLength = readShort(buffer, cursor + 2);
    valueOffset = cursor + StunMessage.ATTRIBUTE_HEADER_SIZE;
    cursor = valueOffset + StunMessage.padded(valueLength);
    return true;
  }

  /** Positions on the first attribute of {@code type}; false if absent. */
  public boolean find(int type) {
    rewind();
    while (next()) {
      if (attributeType == type) {
        return true;
      }
    }
    return false;
  }

  public int attributeType() {
    return attributeType;
  }

  public int valueOffset() {
    return valueOffset;
  }

  public int valueLength() {
    return valueLength;
  }

  public int readU32() {
    return valueLength < Integer.BYTES ? 0 : readInt(buffer, valueOffset);
  }

  public long readU64() {
    if (valueLength < Long.BYTES) {
      return 0;
    }
    return (long) readInt(buffer, valueOffset) << 32
        | (readInt(buffer, valueOffset + 4) & 0xffffffffL);
  }

  /** First two bytes of the value: CHANNEL-NUMBER (RFC 8656 §18.1). */
  public int readU16() {
    return valueLength < 2 ? 0 : readShort(buffer, valueOffset);
  }

  /** ERROR-CODE number (RFC 8489 §14.8), or 0 if malformed. */
  public int readErrorCode() {
    if (valueLength < ERROR_CODE_HEADER) {
      return 0;
    }
    int errorClass = buffer[valueOffset + 2] & ERROR_CLASS_MASK;
    int number = buffer[valueOffset + 3] & 0xff;
    return errorClass * ERROR_CLASS_MULTIPLIER + number;
  }

  /** Current attribute as a plain address (MAPPED-ADDRESS); false if malformed. */
  public boolean readAddress(StunAddress target) {
    return decodeAddress(target, false);
  }

  /** Current attribute as an XOR address (XOR-MAPPED/PEER/RELAYED-ADDRESS); false if malformed. */
  public boolean readXorAddress(StunAddress target) {
    return decodeAddress(target, true);
  }

  /** Compares the current value with the UTF-8 encoding of {@code text}, without allocating. */
  public boolean valueEqualsUtf8(CharSequence text) {
    if (StunWriter.utf8Length(text) != valueLength) {
      return false;
    }
    int p = valueOffset;
    for (int i = 0; i < text.length(); i++) {
      int codePoint = Character.codePointAt(text, i);
      if (Character.isSupplementaryCodePoint(codePoint)) {
        i++;
      }
      if (codePoint < 0x80) {
        if (buffer[p++] != (byte) codePoint) {
          return false;
        }
      } else {
        int bytes = codePoint < 0x800 ? 2 : codePoint < 0x10000 ? 3 : 4;
        int lead = bytes == 2 ? 0xC0 : bytes == 3 ? 0xE0 : 0xF0;
        if ((buffer[p++] & 0xff) != (lead | codePoint >>> (6 * (bytes - 1)))) {
          return false;
        }
        for (int shift = 6 * (bytes - 2); shift >= 0; shift -= 6) {
          if ((buffer[p++] & 0xff) != (0x80 | codePoint >>> shift & 0x3F)) {
            return false;
          }
        }
      }
    }
    return true;
  }

  /** Current value decoded as UTF-8; allocates (use for rare attributes: REALM, NONCE). */
  public String valueAsString() {
    return new String(buffer, valueOffset, valueLength, StandardCharsets.UTF_8);
  }

  /** Copies the current value; returns its length. */
  public int copyValue(byte[] target, int offset) {
    System.arraycopy(buffer, valueOffset, target, offset, valueLength);
    return valueLength;
  }

  /**
   * FINGERPRINT check (RFC 8489 §14.7): it must be the last attribute and match the CRC-32 of the
   * preceding bytes XOR 0x5354554E. False if absent.
   */
  public boolean verifyFingerprint() {
    int attributeStart = end - FINGERPRINT_ATTRIBUTE_SIZE;
    if (attributeStart < start + StunMessage.HEADER_SIZE
        || readShort(buffer, attributeStart) != StunMessage.ATTR_FINGERPRINT
        || readShort(buffer, attributeStart + 2) != Integer.BYTES) {
      return false;
    }
    crc.reset();
    crc.update(buffer, start, attributeStart - start);
    int expected = (int) crc.getValue() ^ StunMessage.FINGERPRINT_XOR;
    return readInt(buffer, attributeStart + StunMessage.ATTRIBUTE_HEADER_SIZE) == expected;
  }

  public boolean hasFingerprint() {
    int attributeStart = end - FINGERPRINT_ATTRIBUTE_SIZE;
    return attributeStart >= start + StunMessage.HEADER_SIZE
        && readShort(buffer, attributeStart) == StunMessage.ATTR_FINGERPRINT;
  }

  /**
   * MESSAGE-INTEGRITY check (RFC 8489 §14.5): HMAC-SHA1 over the message up to the attribute, with
   * the header length adjusted to end at it, compared in constant time. False if absent. Leaves the
   * cursor rewound.
   */
  public boolean verifyIntegrity(StunIntegrity key) {
    if (!find(StunMessage.ATTR_MESSAGE_INTEGRITY) || valueLength != StunMessage.INTEGRITY_SIZE) {
      rewind();
      return false;
    }
    int attributeStart = valueOffset - StunMessage.ATTRIBUTE_HEADER_SIZE;
    int adjustedLength = valueOffset + StunMessage.INTEGRITY_SIZE - start - StunMessage.HEADER_SIZE;
    key.compute(buffer, start, adjustedLength, attributeStart);
    byte[] digest = key.digest();
    int difference = 0;
    for (int i = 0; i < StunMessage.INTEGRITY_SIZE; i++) {
      difference |= digest[i] ^ buffer[valueOffset + i];
    }
    rewind();
    return difference == 0;
  }

  private boolean decodeAddress(StunAddress target, boolean xor) {
    if (valueLength < 4) {
      return false;
    }
    int family = buffer[valueOffset + 1] & 0xff;
    int addressLength =
        family == StunAddress.FAMILY_IPV4
            ? StunAddress.IPV4_LENGTH
            : family == StunAddress.FAMILY_IPV6 ? StunAddress.IPV6_LENGTH : -1;
    if (addressLength < 0 || valueLength != 4 + addressLength) {
      return false;
    }
    int port = readShort(buffer, valueOffset + 2);
    if (xor) {
      port ^= StunMessage.MAGIC_COOKIE >>> 16;
    }
    byte[] raw = target.raw();
    for (int i = 0; i < StunAddress.IPV6_LENGTH; i++) {
      if (i < addressLength) {
        byte mask = xor ? buffer[start + 4 + i] : 0;
        raw[i] = (byte) (buffer[valueOffset + 4 + i] ^ mask);
      } else {
        raw[i] = 0;
      }
    }
    target.setRaw(family, port);
    return true;
  }

  private static int readShort(byte[] data, int offset) {
    return (data[offset] & 0xff) << 8 | data[offset + 1] & 0xff;
  }

  private static int readInt(byte[] data, int offset) {
    return (data[offset] & 0xff) << 24
        | (data[offset + 1] & 0xff) << 16
        | (data[offset + 2] & 0xff) << 8
        | data[offset + 3] & 0xff;
  }
}
