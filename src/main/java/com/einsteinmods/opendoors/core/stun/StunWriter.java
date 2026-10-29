package com.einsteinmods.opendoors.core.stun;

import java.util.zip.CRC32;

/**
 * Reusable STUN message encoder over a caller-owned buffer (RFC 8489 §5, §14). Writes in place and
 * keeps the header length current after each attribute, so nothing is allocated per message. Not
 * thread safe.
 *
 * <p>Order rules (RFC 8489 §14.5, §14.7): add MESSAGE-INTEGRITY after every covered attribute and
 * FINGERPRINT last.
 */
public final class StunWriter {
  private static final int ERROR_CODE_HEADER = 4;
  private static final int ERROR_CLASS_DIVISOR = 100;
  private static final int CHANNEL_NUMBER_SIZE = 4;
  private static final int REQUESTED_TRANSPORT_SIZE = 4;
  private static final int FINGERPRINT_SIZE = 4;

  private final CRC32 crc = new CRC32();
  private byte[] buffer = new byte[0];
  private int start;
  private int position;
  private int limit;
  private byte padding;

  /** Padding byte value (default 0); RFC 8489 §14 lets it be anything. */
  public StunWriter paddingByte(int value) {
    padding = (byte) value;
    return this;
  }

  /** Starts a message at {@code offset}; the buffer may be written up to its end. */
  public StunWriter begin(
      byte[] target, int offset, int messageType, byte[] transactionId, int transactionOffset) {
    buffer = target;
    start = offset;
    limit = target.length;
    position = offset;
    ensure(StunMessage.HEADER_SIZE);
    writeShort(messageType & 0x3FFF);
    writeShort(0);
    writeInt(StunMessage.MAGIC_COOKIE);
    System.arraycopy(
        transactionId, transactionOffset, buffer, position, StunMessage.TRANSACTION_ID_SIZE);
    position += StunMessage.TRANSACTION_ID_SIZE;
    return this;
  }

  /** Total bytes written so far (header included). */
  public int length() {
    return position - start;
  }

  public StunWriter addBytes(int type, byte[] value, int offset, int length) {
    beginAttribute(type, length);
    System.arraycopy(value, offset, buffer, position, length);
    position += length;
    return endAttribute(length);
  }

  /** UTF-8 text attribute (USERNAME, REALM, NONCE, SOFTWARE), encoded without allocating. */
  public StunWriter addUtf8(int type, CharSequence text) {
    int length = utf8Length(text);
    beginAttribute(type, length);
    for (int i = 0; i < text.length(); i++) {
      int codePoint = Character.codePointAt(text, i);
      if (Character.isSupplementaryCodePoint(codePoint)) {
        i++;
      }
      writeUtf8(codePoint);
    }
    return endAttribute(length);
  }

  public StunWriter addU32(int type, int value) {
    beginAttribute(type, Integer.BYTES);
    writeInt(value);
    return endAttribute(Integer.BYTES);
  }

  /** 64-bit value, e.g. ICE-CONTROLLING / ICE-CONTROLLED tie breaker (RFC 8445 §16.1). */
  public StunWriter addU64(int type, long value) {
    beginAttribute(type, Long.BYTES);
    writeInt((int) (value >>> 32));
    writeInt((int) value);
    return endAttribute(Long.BYTES);
  }

  /** Zero-length attribute, e.g. USE-CANDIDATE (RFC 8445 §7.1.2). */
  public StunWriter addEmpty(int type) {
    beginAttribute(type, 0);
    return endAttribute(0);
  }

  /** MAPPED-ADDRESS style plain address (RFC 8489 §14.1). */
  public StunWriter addAddress(int type, StunAddress address) {
    return writeAddress(type, address, false);
  }

  /** XOR-MAPPED-ADDRESS, XOR-PEER-ADDRESS, XOR-RELAYED-ADDRESS (RFC 8489 §14.2). */
  public StunWriter addXorAddress(int type, StunAddress address) {
    return writeAddress(type, address, true);
  }

  /** ERROR-CODE (RFC 8489 §14.8): class, number and an ASCII/UTF-8 reason phrase. */
  public StunWriter addErrorCode(int code, CharSequence reason) {
    int reasonLength = utf8Length(reason);
    int length = ERROR_CODE_HEADER + reasonLength;
    beginAttribute(StunMessage.ATTR_ERROR_CODE, length);
    writeShort(0);
    buffer[position++] = (byte) (code / ERROR_CLASS_DIVISOR);
    buffer[position++] = (byte) (code % ERROR_CLASS_DIVISOR);
    for (int i = 0; i < reason.length(); i++) {
      int codePoint = Character.codePointAt(reason, i);
      if (Character.isSupplementaryCodePoint(codePoint)) {
        i++;
      }
      writeUtf8(codePoint);
    }
    return endAttribute(length);
  }

  /** REQUESTED-TRANSPORT UDP (RFC 8656 §18.7). */
  public StunWriter addRequestedTransportUdp() {
    beginAttribute(StunMessage.ATTR_REQUESTED_TRANSPORT, REQUESTED_TRANSPORT_SIZE);
    buffer[position++] = (byte) StunMessage.TRANSPORT_UDP;
    buffer[position++] = 0;
    writeShort(0);
    return endAttribute(REQUESTED_TRANSPORT_SIZE);
  }

  /** CHANNEL-NUMBER (RFC 8656 §18.1): number then two reserved bytes. */
  public StunWriter addChannelNumber(int channel) {
    beginAttribute(StunMessage.ATTR_CHANNEL_NUMBER, CHANNEL_NUMBER_SIZE);
    writeShort(channel);
    writeShort(0);
    return endAttribute(CHANNEL_NUMBER_SIZE);
  }

  /** MESSAGE-INTEGRITY over everything written so far (RFC 8489 §14.5). */
  public StunWriter addMessageIntegrity(StunIntegrity key) {
    int attributeStart = position;
    ensure(StunMessage.ATTRIBUTE_HEADER_SIZE + StunMessage.INTEGRITY_SIZE);
    int adjustedLength =
        attributeStart
            + StunMessage.ATTRIBUTE_HEADER_SIZE
            + StunMessage.INTEGRITY_SIZE
            - start
            - StunMessage.HEADER_SIZE;
    key.compute(buffer, start, adjustedLength, attributeStart);
    beginAttribute(StunMessage.ATTR_MESSAGE_INTEGRITY, StunMessage.INTEGRITY_SIZE);
    System.arraycopy(key.digest(), 0, buffer, position, StunMessage.INTEGRITY_SIZE);
    position += StunMessage.INTEGRITY_SIZE;
    return endAttribute(StunMessage.INTEGRITY_SIZE);
  }

  /** FINGERPRINT, always the last attribute (RFC 8489 §14.7). */
  public StunWriter addFingerprint() {
    beginAttribute(StunMessage.ATTR_FINGERPRINT, FINGERPRINT_SIZE);
    int valuePosition = position;
    position += FINGERPRINT_SIZE;
    endAttribute(FINGERPRINT_SIZE);
    crc.reset();
    crc.update(buffer, start, valuePosition - StunMessage.ATTRIBUTE_HEADER_SIZE - start);
    int value = (int) crc.getValue() ^ StunMessage.FINGERPRINT_XOR;
    int saved = position;
    position = valuePosition;
    writeInt(value);
    position = saved;
    return this;
  }

  private StunWriter writeAddress(int type, StunAddress address, boolean xor) {
    int addressLength = address.addressLength();
    if (addressLength == 0) {
      throw new IllegalArgumentException("Empty STUN address");
    }
    int length = 4 + addressLength;
    beginAttribute(type, length);
    buffer[position++] = 0;
    buffer[position++] = (byte) address.family();
    int port = xor ? address.port() ^ (StunMessage.MAGIC_COOKIE >>> 16) : address.port();
    writeShort(port);
    byte[] raw = address.raw();
    for (int i = 0; i < addressLength; i++) {
      byte mask = xor ? buffer[start + 4 + i] : 0;
      buffer[position++] = (byte) (raw[i] ^ mask);
    }
    return endAttribute(length);
  }

  private void beginAttribute(int type, int length) {
    ensure(StunMessage.ATTRIBUTE_HEADER_SIZE + StunMessage.padded(length));
    writeShort(type);
    writeShort(length);
  }

  private StunWriter endAttribute(int length) {
    int pad = StunMessage.padded(length) - length;
    for (int i = 0; i < pad; i++) {
      buffer[position++] = padding;
    }
    int bodyLength = position - start - StunMessage.HEADER_SIZE;
    buffer[start + 2] = (byte) (bodyLength >>> 8);
    buffer[start + 3] = (byte) bodyLength;
    return this;
  }

  private void ensure(int bytes) {
    if (position + bytes > limit) {
      throw new IllegalStateException("STUN message exceeds its buffer");
    }
  }

  private void writeShort(int value) {
    buffer[position++] = (byte) (value >>> 8);
    buffer[position++] = (byte) value;
  }

  private void writeInt(int value) {
    buffer[position++] = (byte) (value >>> 24);
    buffer[position++] = (byte) (value >>> 16);
    buffer[position++] = (byte) (value >>> 8);
    buffer[position++] = (byte) value;
  }

  private void writeUtf8(int codePoint) {
    if (codePoint < 0x80) {
      buffer[position++] = (byte) codePoint;
    } else if (codePoint < 0x800) {
      buffer[position++] = (byte) (0xC0 | codePoint >>> 6);
      buffer[position++] = (byte) (0x80 | codePoint & 0x3F);
    } else if (codePoint < 0x10000) {
      buffer[position++] = (byte) (0xE0 | codePoint >>> 12);
      buffer[position++] = (byte) (0x80 | codePoint >>> 6 & 0x3F);
      buffer[position++] = (byte) (0x80 | codePoint & 0x3F);
    } else {
      buffer[position++] = (byte) (0xF0 | codePoint >>> 18);
      buffer[position++] = (byte) (0x80 | codePoint >>> 12 & 0x3F);
      buffer[position++] = (byte) (0x80 | codePoint >>> 6 & 0x3F);
      buffer[position++] = (byte) (0x80 | codePoint & 0x3F);
    }
  }

  /** UTF-8 byte length of {@code text}, without allocating. */
  static int utf8Length(CharSequence text) {
    int length = 0;
    for (int i = 0; i < text.length(); i++) {
      int codePoint = Character.codePointAt(text, i);
      if (Character.isSupplementaryCodePoint(codePoint)) {
        i++;
        length += 4;
      } else if (codePoint < 0x80) {
        length += 1;
      } else if (codePoint < 0x800) {
        length += 2;
      } else {
        length += 3;
      }
    }
    return length;
  }
}
