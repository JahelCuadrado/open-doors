package com.einsteinmods.opendoors.core.stun;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;

/** RFC 5769 test vectors plus TURN/ICE attribute round trips. */
final class StunCodecTest {
  private static final String PASSWORD = "VOkJxbRl1RmTxUk/WvJxBt";
  private static final byte[] TRANSACTION_ID = hex("b7e7a701bc34d686fa87dfae");

  /** RFC 5769 §2.1. */
  private static final byte[] SAMPLE_REQUEST =
      hex(
          "000100582112a442b7e7a701bc34d686fa87dfae"
              + "80220010"
              + "5354554e207465737420636c69656e74"
              + "00240004"
              + "6e0001ff"
              + "80290008"
              + "932ff9b151263b36"
              + "00060009"
              + "6576746a3a68367659202020"
              + "00080014"
              + "9aeaa70cbfd8cb56781ef2b5b2d3f249c1b571a2"
              + "80280004"
              + "e57a3bcf");

  /** RFC 5769 §2.2. */
  private static final byte[] IPV4_RESPONSE =
      hex(
          "0101003c2112a442b7e7a701bc34d686fa87dfae"
              + "8022000b"
              + "7465737420766563746f7220"
              + "00200008"
              + "0001a147e112a643"
              + "00080014"
              + "2b91f599fd9e90c38c7489f92af9ba53f06be7d7"
              + "80280004"
              + "c07d4c96");

  /** RFC 5769 §2.3. */
  private static final byte[] IPV6_RESPONSE =
      hex(
          "010100482112a442b7e7a701bc34d686fa87dfae"
              + "8022000b"
              + "7465737420766563746f7220"
              + "00200014"
              + "0002a1470113a9faa5d3f179bc25f4b5bed2b9d9"
              + "00080014"
              + "a382954e4be67bf11784c97c8292c275bfe3ed41"
              + "80280004"
              + "c8fb0b4c");

  /** RFC 5769 §2.4. */
  private static final byte[] LONG_TERM_REQUEST =
      hex(
          "000100602112a44278ad3433c6ad72c029da412e"
              + "00060012"
              + "e3839ee38388e383aae38383e382afe382b90000"
              + "0015001c"
              + "662f2f3439396b39353464364f4c33346f4c39465354767936347341"
              + "0014000b"
              + "6578616d706c652e6f726700"
              + "00080014"
              + "f67024656dd64a3e02b8e0712e85c9a28ca89666");

  private static final String LONG_TERM_USERNAME = "マトリックス";
  private static final String LONG_TERM_NONCE = "f//499k954d6OL34oL9FSTvy64sA";
  private static final String LONG_TERM_REALM = "example.org";
  private static final String LONG_TERM_PASSWORD = "TheMatrIX";

  @Test
  void sampleRequestVerifiesAndDecodes() {
    StunReader reader = new StunReader();
    assertTrue(reader.wrap(SAMPLE_REQUEST, 0, SAMPLE_REQUEST.length));
    assertEquals(StunMessage.METHOD_BINDING, reader.method());
    assertEquals(StunMessage.CLASS_REQUEST, reader.messageClass());
    assertTrue(reader.transactionIdEquals(TRANSACTION_ID, 0));
    assertTrue(reader.verifyFingerprint());
    assertTrue(reader.verifyIntegrity(StunIntegrity.shortTerm(PASSWORD)));
    assertFalse(reader.verifyIntegrity(StunIntegrity.shortTerm("wrong")));
    assertTrue(reader.find(StunMessage.ATTR_USERNAME));
    assertTrue(reader.valueEqualsUtf8("evtj:h6vY"));
    assertFalse(reader.valueEqualsUtf8("evtj:h6vZ"));
    assertTrue(reader.find(StunMessage.ATTR_PRIORITY));
    assertEquals(0x6e0001ff, reader.readU32());
    assertTrue(reader.find(StunMessage.ATTR_ICE_CONTROLLED));
    assertEquals(0x932ff9b151263b36L, reader.readU64());
    assertTrue(reader.find(StunMessage.ATTR_SOFTWARE));
    assertEquals("STUN test client", reader.valueAsString());
  }

  @Test
  void sampleRequestEncodesByteForByte() {
    byte[] out = new byte[256];
    StunWriter writer = new StunWriter().paddingByte(' ');
    writer
        .begin(out, 0, StunMessage.messageType(StunMessage.METHOD_BINDING, 0), TRANSACTION_ID, 0)
        .addUtf8(StunMessage.ATTR_SOFTWARE, "STUN test client")
        .addU32(StunMessage.ATTR_PRIORITY, 0x6e0001ff)
        .addU64(StunMessage.ATTR_ICE_CONTROLLED, 0x932ff9b151263b36L)
        .addUtf8(StunMessage.ATTR_USERNAME, "evtj:h6vY")
        .addMessageIntegrity(StunIntegrity.shortTerm(PASSWORD))
        .addFingerprint();
    assertArrayEquals(SAMPLE_REQUEST, Arrays.copyOf(out, writer.length()));
  }

  @Test
  void ipv4ResponseDecodesAndEncodes() throws UnknownHostException {
    StunReader reader = new StunReader();
    assertTrue(reader.wrap(IPV4_RESPONSE, 0, IPV4_RESPONSE.length));
    assertEquals(StunMessage.CLASS_SUCCESS, reader.messageClass());
    assertTrue(reader.verifyFingerprint());
    assertTrue(reader.verifyIntegrity(StunIntegrity.shortTerm(PASSWORD)));
    assertTrue(reader.find(StunMessage.ATTR_XOR_MAPPED_ADDRESS));
    StunAddress mapped = new StunAddress();
    assertTrue(reader.readXorAddress(mapped));
    InetSocketAddress expected = new InetSocketAddress(InetAddress.getByName("192.0.2.1"), 32853);
    assertEquals(expected, mapped.toInetSocketAddress());

    byte[] out = new byte[256];
    StunWriter writer = new StunWriter().paddingByte(' ');
    writer
        .begin(
            out,
            0,
            StunMessage.messageType(StunMessage.METHOD_BINDING, StunMessage.CLASS_SUCCESS),
            TRANSACTION_ID,
            0)
        .addUtf8(StunMessage.ATTR_SOFTWARE, "test vector")
        .addXorAddress(StunMessage.ATTR_XOR_MAPPED_ADDRESS, mapped)
        .addMessageIntegrity(StunIntegrity.shortTerm(PASSWORD))
        .addFingerprint();
    assertArrayEquals(IPV4_RESPONSE, Arrays.copyOf(out, writer.length()));
  }

  @Test
  void ipv6ResponseDecodesAndEncodes() throws UnknownHostException {
    StunReader reader = new StunReader();
    assertTrue(reader.wrap(IPV6_RESPONSE, 0, IPV6_RESPONSE.length));
    assertTrue(reader.verifyFingerprint());
    assertTrue(reader.verifyIntegrity(StunIntegrity.shortTerm(PASSWORD)));
    assertTrue(reader.find(StunMessage.ATTR_XOR_MAPPED_ADDRESS));
    StunAddress mapped = new StunAddress();
    assertTrue(reader.readXorAddress(mapped));
    InetSocketAddress expected =
        new InetSocketAddress(InetAddress.getByName("2001:db8:1234:5678:11:2233:4455:6677"), 32853);
    assertEquals(expected, mapped.toInetSocketAddress());

    byte[] out = new byte[256];
    StunWriter writer = new StunWriter().paddingByte(' ');
    writer
        .begin(
            out,
            0,
            StunMessage.messageType(StunMessage.METHOD_BINDING, StunMessage.CLASS_SUCCESS),
            TRANSACTION_ID,
            0)
        .addUtf8(StunMessage.ATTR_SOFTWARE, "test vector")
        .addXorAddress(StunMessage.ATTR_XOR_MAPPED_ADDRESS, mapped)
        .addMessageIntegrity(StunIntegrity.shortTerm(PASSWORD))
        .addFingerprint();
    assertArrayEquals(IPV6_RESPONSE, Arrays.copyOf(out, writer.length()));
  }

  @Test
  void longTermRequestVerifiesAndEncodes() {
    StunIntegrity key =
        StunIntegrity.longTerm(LONG_TERM_USERNAME, LONG_TERM_REALM, LONG_TERM_PASSWORD);
    StunReader reader = new StunReader();
    assertTrue(reader.wrap(LONG_TERM_REQUEST, 0, LONG_TERM_REQUEST.length));
    assertFalse(reader.hasFingerprint());
    assertFalse(reader.verifyFingerprint());
    assertTrue(reader.verifyIntegrity(key));
    assertTrue(reader.find(StunMessage.ATTR_USERNAME));
    assertTrue(reader.valueEqualsUtf8(LONG_TERM_USERNAME));
    assertTrue(reader.find(StunMessage.ATTR_NONCE));
    assertEquals(LONG_TERM_NONCE, reader.valueAsString());
    assertTrue(reader.find(StunMessage.ATTR_REALM));
    assertEquals(LONG_TERM_REALM, reader.valueAsString());

    byte[] transactionId = hex("78ad3433c6ad72c029da412e");
    byte[] out = new byte[256];
    StunWriter writer = new StunWriter();
    writer
        .begin(out, 0, StunMessage.messageType(StunMessage.METHOD_BINDING, 0), transactionId, 0)
        .addUtf8(StunMessage.ATTR_USERNAME, LONG_TERM_USERNAME)
        .addUtf8(StunMessage.ATTR_NONCE, LONG_TERM_NONCE)
        .addUtf8(StunMessage.ATTR_REALM, LONG_TERM_REALM)
        .addMessageIntegrity(key);
    assertArrayEquals(LONG_TERM_REQUEST, Arrays.copyOf(out, writer.length()));
  }

  @Test
  void tamperedMessageFailsChecks() {
    byte[] copy = SAMPLE_REQUEST.clone();
    copy[30] ^= 1;
    StunReader reader = new StunReader();
    assertTrue(reader.wrap(copy, 0, copy.length));
    assertFalse(reader.verifyFingerprint());
    assertFalse(reader.verifyIntegrity(StunIntegrity.shortTerm(PASSWORD)));
  }

  @Test
  void rejectsMalformedFraming() {
    StunReader reader = new StunReader();
    assertFalse(reader.wrap(SAMPLE_REQUEST, 0, 19));
    assertFalse(reader.wrap(SAMPLE_REQUEST, 0, SAMPLE_REQUEST.length - 4));
    byte[] badCookie = SAMPLE_REQUEST.clone();
    badCookie[4] = 0;
    assertFalse(reader.wrap(badCookie, 0, badCookie.length));
    byte[] badAttribute = SAMPLE_REQUEST.clone();
    badAttribute[22] = 0x7f;
    assertFalse(reader.wrap(badAttribute, 0, badAttribute.length));
    byte[] topBits = SAMPLE_REQUEST.clone();
    topBits[0] = (byte) 0x80;
    assertFalse(reader.wrap(topBits, 0, topBits.length));
  }

  @Test
  void turnAttributesRoundTrip() throws UnknownHostException {
    byte[] out = new byte[512];
    StunAddress peer = new StunAddress();
    peer.set(new InetSocketAddress(InetAddress.getByName("203.0.113.7"), 50000));
    StunIntegrity key = StunIntegrity.longTerm("user", "realm", "pass");
    StunWriter writer = new StunWriter();
    int type = StunMessage.messageType(StunMessage.METHOD_CHANNEL_BIND, 0);
    writer
        .begin(out, 8, type, TRANSACTION_ID, 0)
        .addChannelNumber(0x4001)
        .addXorAddress(StunMessage.ATTR_XOR_PEER_ADDRESS, peer)
        .addU32(StunMessage.ATTR_LIFETIME, 600)
        .addRequestedTransportUdp()
        .addEmpty(StunMessage.ATTR_USE_CANDIDATE)
        .addErrorCode(StunMessage.ERROR_STALE_NONCE, "Stale Nonce")
        .addMessageIntegrity(key)
        .addFingerprint();
    StunReader reader = new StunReader();
    assertTrue(reader.wrap(out, 8, writer.length()));
    assertEquals(type, reader.messageType());
    assertEquals(StunMessage.METHOD_CHANNEL_BIND, reader.method());
    assertTrue(reader.verifyFingerprint());
    assertTrue(reader.verifyIntegrity(key));
    assertTrue(reader.find(StunMessage.ATTR_CHANNEL_NUMBER));
    assertEquals(0x4001, reader.readU16());
    assertTrue(reader.find(StunMessage.ATTR_XOR_PEER_ADDRESS));
    StunAddress decoded = new StunAddress();
    assertTrue(reader.readXorAddress(decoded));
    assertEquals(peer, decoded);
    assertTrue(reader.find(StunMessage.ATTR_LIFETIME));
    assertEquals(600, reader.readU32());
    assertTrue(reader.find(StunMessage.ATTR_REQUESTED_TRANSPORT));
    assertEquals(StunMessage.TRANSPORT_UDP, reader.buffer()[reader.valueOffset()]);
    assertTrue(reader.find(StunMessage.ATTR_USE_CANDIDATE));
    assertEquals(0, reader.valueLength());
    assertTrue(reader.find(StunMessage.ATTR_ERROR_CODE));
    assertEquals(StunMessage.ERROR_STALE_NONCE, reader.readErrorCode());
  }

  @Test
  void messageTypeHelpersMatchRfc() {
    assertEquals(0x0001, StunMessage.messageType(StunMessage.METHOD_BINDING, 0));
    assertEquals(
        0x0101, StunMessage.messageType(StunMessage.METHOD_BINDING, StunMessage.CLASS_SUCCESS));
    assertEquals(
        0x0113, StunMessage.messageType(StunMessage.METHOD_ALLOCATE, StunMessage.CLASS_ERROR));
    assertEquals(
        0x0017, StunMessage.messageType(StunMessage.METHOD_DATA, StunMessage.CLASS_INDICATION));
    for (int method = 0; method < 0x1000; method++) {
      assertEquals(method, StunMessage.method(StunMessage.messageType(method, 0x110)));
    }
  }

  @Test
  void demuxAndChannelData() {
    assertEquals(Demux.KIND_STUN, Demux.classify((byte) 0));
    assertEquals(Demux.KIND_STUN, Demux.classify((byte) 3));
    assertEquals(Demux.KIND_APPLICATION, Demux.classify((byte) 4));
    assertEquals(Demux.KIND_CHANNEL_DATA, Demux.classify((byte) 0x40));
    assertEquals(Demux.KIND_CHANNEL_DATA, Demux.classify((byte) 0x4F));
    assertEquals(Demux.KIND_APPLICATION, Demux.classify((byte) 0x50));
    assertEquals(Demux.KIND_APPLICATION, Demux.classify((byte) 0xFF));
    byte[] frame = new byte[12];
    Demux.writeChannelDataHeader(frame, 0, 0x4002, 5);
    assertEquals(0x4002, Demux.channelNumber(frame, 0));
    assertEquals(5, Demux.channelDataLength(frame, 0));
    assertTrue(Demux.isValidChannelData(frame, 0, 9));
    assertTrue(Demux.isValidChannelData(frame, 0, 12));
    assertFalse(Demux.isValidChannelData(frame, 0, 8));
    Demux.writeChannelDataHeader(frame, 0, 0x5000, 5);
    assertFalse(Demux.isValidChannelData(frame, 0, 12));
  }

  private static byte[] hex(String text) {
    return HexFormat.of().parseHex(text.replace(" ", ""));
  }
}
