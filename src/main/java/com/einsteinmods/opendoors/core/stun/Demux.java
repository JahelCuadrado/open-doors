package com.einsteinmods.opendoors.core.stun;

/**
 * Datagram demultiplexing by first byte (RFC 7983 §7, as updated for TURN channels by RFC 8656 §12)
 * and TURN ChannelData framing (RFC 8656 §12.4).
 *
 * <p>First byte 0..3 is STUN, 64..79 is ChannelData; everything else is left to the application.
 * Application datagrams must therefore never start with a byte in those two ranges.
 */
public final class Demux {
  public static final int KIND_STUN = 0;
  public static final int KIND_CHANNEL_DATA = 1;
  public static final int KIND_APPLICATION = 2;

  public static final int CHANNEL_DATA_HEADER_SIZE = 4;

  /** Channel numbers a client may bind (RFC 8656 §12). */
  public static final int MIN_CHANNEL = 0x4000;

  public static final int MAX_CHANNEL = 0x4FFF;

  private static final int STUN_LAST_BYTE = 3;
  private static final int CHANNEL_FIRST_BYTE = 64;
  private static final int CHANNEL_LAST_BYTE = 79;

  private Demux() {}

  /** One of the {@code KIND_*} constants for a datagram starting with {@code firstByte}. */
  public static int classify(byte firstByte) {
    int value = firstByte & 0xff;
    if (value <= STUN_LAST_BYTE) {
      return KIND_STUN;
    }
    if (value >= CHANNEL_FIRST_BYTE && value <= CHANNEL_LAST_BYTE) {
      return KIND_CHANNEL_DATA;
    }
    return KIND_APPLICATION;
  }

  /** Writes the 4-byte ChannelData header: channel number then data length (RFC 8656 §12.4). */
  public static void writeChannelDataHeader(byte[] target, int offset, int channel, int length) {
    target[offset] = (byte) (channel >>> 8);
    target[offset + 1] = (byte) channel;
    target[offset + 2] = (byte) (length >>> 8);
    target[offset + 3] = (byte) length;
  }

  public static int channelNumber(byte[] data, int offset) {
    return (data[offset] & 0xff) << 8 | data[offset + 1] & 0xff;
  }

  public static int channelDataLength(byte[] data, int offset) {
    return (data[offset + 2] & 0xff) << 8 | data[offset + 3] & 0xff;
  }

  /**
   * True if {@code length} bytes form a ChannelData message for a valid channel whose declared
   * length fits; trailing padding (TCP framing, RFC 8656 §12.5) is tolerated.
   */
  public static boolean isValidChannelData(byte[] data, int offset, int length) {
    if (length < CHANNEL_DATA_HEADER_SIZE) {
      return false;
    }
    int channel = channelNumber(data, offset);
    return channel >= MIN_CHANNEL
        && channel <= MAX_CHANNEL
        && CHANNEL_DATA_HEADER_SIZE + channelDataLength(data, offset) <= length;
  }
}
