package com.einsteinmods.opendoors.core.net.portmap;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;

/**
 * NAT-PMP client (RFC 6886): asks the gateway (port 5351) for its public address (opcode 0) and a
 * UDP mapping (opcode 1); a mapping with lifetime 0 deletes it.
 */
public final class NatPmp {
  public static final int PORT = 5351;
  private static final int VERSION = 0;
  private static final int OP_EXTERNAL_ADDRESS = 0;
  private static final int OP_MAP_UDP = 1;
  private static final int RESPONSE_BIT = 128;
  private static final int RESULT_SUCCESS = 0;
  private static final int MAP_REQUEST_SIZE = 12;
  private static final int MAP_RESPONSE_SIZE = 16;
  private static final int ADDRESS_RESPONSE_SIZE = 12;

  private NatPmp() {}

  /** Opcode 1 request: map UDP {@code internalPort}, suggesting the same external port. */
  static byte[] mapRequest(int internalPort, int suggestedExternalPort, int lifetimeSeconds) {
    return ByteBuffer.allocate(MAP_REQUEST_SIZE)
        .put((byte) VERSION)
        .put((byte) OP_MAP_UDP)
        .putShort((short) 0)
        .putShort((short) internalPort)
        .putShort((short) suggestedExternalPort)
        .putInt(lifetimeSeconds)
        .array();
  }

  static byte[] addressRequest() {
    return new byte[] {VERSION, OP_EXTERNAL_ADDRESS};
  }

  /** The mapped external port and lifetime, or null if {@code response} is not a success for it. */
  static int[] parseMapResponse(byte[] response, int length, int internalPort) {
    if (length < MAP_RESPONSE_SIZE) {
      return null;
    }
    ByteBuffer buffer = ByteBuffer.wrap(response, 0, length);
    if (buffer.get() != VERSION
        || (buffer.get() & 0xFF) != RESPONSE_BIT + OP_MAP_UDP
        || buffer.getShort() != RESULT_SUCCESS) {
      return null;
    }
    buffer.getInt(); // seconds since start of epoch
    int internal = buffer.getShort() & 0xFFFF;
    int external = buffer.getShort() & 0xFFFF;
    int lifetime = buffer.getInt();
    return internal == internalPort ? new int[] {external, lifetime} : null;
  }

  static InetAddress parseAddressResponse(byte[] response, int length) throws IOException {
    if (length < ADDRESS_RESPONSE_SIZE
        || response[0] != VERSION
        || (response[1] & 0xFF) != RESPONSE_BIT + OP_EXTERNAL_ADDRESS
        || response[2] != 0
        || response[3] != 0) {
      return null;
    }
    byte[] address = new byte[4];
    System.arraycopy(response, 8, address, 0, 4);
    return InetAddress.getByAddress(address);
  }

  /** Maps {@code internalPort}; null when the gateway does not speak NAT-PMP in time. */
  public static PortMapping map(
      InetSocketAddress gateway, int internalPort, int lifetimeSeconds, int timeoutMillis)
      throws IOException {
    try (DatagramSocket socket = new DatagramSocket()) {
      byte[] buffer = new byte[64];
      InetAddress external = null;
      int deadline = timeoutMillis;
      long start = System.nanoTime();
      for (int wait = 250; external == null && elapsed(start) < deadline; wait *= 2) {
        socket.send(new DatagramPacket(addressRequest(), 2, gateway));
        int length = receive(socket, buffer, Math.min(wait, deadline - elapsed(start)));
        external = length < 0 ? null : parseAddressResponse(buffer, length);
      }
      if (external == null) {
        return null;
      }
      byte[] request = mapRequest(internalPort, internalPort, lifetimeSeconds);
      for (int wait = 250; elapsed(start) < deadline; wait *= 2) {
        socket.send(new DatagramPacket(request, request.length, gateway));
        int length = receive(socket, buffer, Math.min(wait, deadline - elapsed(start)));
        int[] mapped = length < 0 ? null : parseMapResponse(buffer, length, internalPort);
        if (mapped != null) {
          return new PortMapping("NAT-PMP", new InetSocketAddress(external, mapped[0]), mapped[1]);
        }
      }
      return null;
    }
  }

  /** Deletes the mapping (lifetime 0), best effort. */
  public static void release(InetSocketAddress gateway, int internalPort) {
    try (DatagramSocket socket = new DatagramSocket()) {
      byte[] request = mapRequest(internalPort, 0, 0);
      socket.send(new DatagramPacket(request, request.length, gateway));
    } catch (IOException exception) {
      // The lease runs out by itself.
    }
  }

  static int elapsed(long startNanos) {
    return (int) ((System.nanoTime() - startNanos) / 1_000_000L);
  }

  /** Bytes received, or -1 on timeout. */
  static int receive(DatagramSocket socket, byte[] buffer, int timeoutMillis) throws IOException {
    if (timeoutMillis <= 0) {
      return -1;
    }
    socket.setSoTimeout(timeoutMillis);
    DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
    try {
      socket.receive(packet);
      return packet.getLength();
    } catch (SocketTimeoutException timeout) {
      return -1;
    }
  }
}
