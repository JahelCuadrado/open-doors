package com.einsteinmods.opendoors.core.net.portmap;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.util.Arrays;

/**
 * PCP client (RFC 6887), MAP opcode for UDP: one request to the gateway (port 5351) gives the
 * public address and port at once. Gateways that only speak NAT-PMP answer with version 0 or an
 * UNSUPP_VERSION result; {@link PortMappers} then falls back to {@link NatPmp}.
 */
public final class Pcp {
  private static final int VERSION = 2;
  private static final int OP_MAP = 1;
  private static final int RESPONSE_BIT = 0x80;
  private static final int RESULT_SUCCESS = 0;
  private static final int PROTOCOL_UDP = 17;
  private static final int HEADER_SIZE = 24;
  private static final int MAP_SIZE = 36;
  private static final int NONCE_SIZE = 12;
  private static final int ADDRESS_SIZE = 16;

  private Pcp() {}

  /** MAP request for UDP {@code internalPort} from {@code client} (IPv4-mapped in 16 bytes). */
  static byte[] mapRequest(
      InetAddress client, byte[] nonce, int internalPort, int suggestedPort, int lifetimeSeconds) {
    ByteBuffer buffer = ByteBuffer.allocate(HEADER_SIZE + MAP_SIZE);
    buffer.put((byte) VERSION).put((byte) OP_MAP).putShort((short) 0).putInt(lifetimeSeconds);
    buffer.put(mapped(client));
    buffer.put(nonce).put((byte) PROTOCOL_UDP).put(new byte[3]);
    buffer.putShort((short) internalPort).putShort((short) suggestedPort);
    buffer.put(new byte[ADDRESS_SIZE]);
    return buffer.array();
  }

  /** The mapping in a successful MAP response matching {@code nonce}, or null. */
  static PortMapping parseMapResponse(byte[] response, int length, byte[] nonce, int internalPort)
      throws IOException {
    if (length < HEADER_SIZE + MAP_SIZE) {
      return null;
    }
    ByteBuffer buffer = ByteBuffer.wrap(response, 0, length);
    if (buffer.get() != VERSION
        || (buffer.get() & 0xFF) != (RESPONSE_BIT | OP_MAP)
        || buffer.get() != 0
        || buffer.get() != RESULT_SUCCESS) {
      return null;
    }
    int lifetime = buffer.getInt();
    buffer.position(HEADER_SIZE);
    byte[] responseNonce = new byte[NONCE_SIZE];
    buffer.get(responseNonce);
    if (!Arrays.equals(responseNonce, nonce) || (buffer.get() & 0xFF) != PROTOCOL_UDP) {
      return null;
    }
    buffer.position(buffer.position() + 3);
    int internal = buffer.getShort() & 0xFFFF;
    int external = buffer.getShort() & 0xFFFF;
    byte[] address = new byte[ADDRESS_SIZE];
    buffer.get(address);
    if (internal != internalPort) {
      return null;
    }
    return new PortMapping("PCP", new InetSocketAddress(unmapped(address), external), lifetime);
  }

  /** Maps {@code internalPort}; null when the gateway does not answer PCP in time. */
  public static PortMapping map(
      InetSocketAddress gateway,
      InetAddress client,
      int internalPort,
      int lifetimeSeconds,
      int timeoutMillis,
      SecureRandom random)
      throws IOException {
    byte[] nonce = new byte[NONCE_SIZE];
    random.nextBytes(nonce);
    byte[] request = mapRequest(client, nonce, internalPort, internalPort, lifetimeSeconds);
    try (DatagramSocket socket = new DatagramSocket()) {
      byte[] buffer = new byte[1100];
      long start = System.nanoTime();
      for (int wait = 250; NatPmp.elapsed(start) < timeoutMillis; wait *= 2) {
        socket.send(new DatagramPacket(request, request.length, gateway));
        int length =
            NatPmp.receive(socket, buffer, Math.min(wait, timeoutMillis - NatPmp.elapsed(start)));
        if (length >= 0) {
          // A version-0 or error answer means no PCP here: stop and let NAT-PMP try.
          return parseMapResponse(buffer, length, nonce, internalPort);
        }
      }
      return null;
    }
  }

  /** Deletes the mapping (lifetime 0), best effort. */
  public static void release(
      InetSocketAddress gateway, InetAddress client, int internalPort, SecureRandom random) {
    byte[] nonce = new byte[NONCE_SIZE];
    random.nextBytes(nonce);
    byte[] request = mapRequest(client, nonce, internalPort, 0, 0);
    try (DatagramSocket socket = new DatagramSocket()) {
      socket.send(new DatagramPacket(request, request.length, gateway));
    } catch (IOException exception) {
      // The lease runs out by itself.
    }
  }

  private static byte[] mapped(InetAddress address) {
    byte[] raw = address.getAddress();
    if (raw.length == ADDRESS_SIZE) {
      return raw;
    }
    byte[] mapped = new byte[ADDRESS_SIZE];
    mapped[10] = (byte) 0xFF;
    mapped[11] = (byte) 0xFF;
    System.arraycopy(raw, 0, mapped, 12, 4);
    return mapped;
  }

  private static InetAddress unmapped(byte[] address) throws IOException {
    boolean v4 = true;
    for (int i = 0; i < 10; i++) {
      v4 &= address[i] == 0;
    }
    v4 &= address[10] == (byte) 0xFF && address[11] == (byte) 0xFF;
    return v4
        ? InetAddress.getByAddress(Arrays.copyOfRange(address, 12, ADDRESS_SIZE))
        : InetAddress.getByAddress(address);
  }
}
