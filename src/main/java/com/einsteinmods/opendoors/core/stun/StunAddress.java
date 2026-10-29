package com.einsteinmods.opendoors.core.stun;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.Arrays;

/**
 * Mutable transport address holder, reused by the STUN reader and writer so that decoding an
 * address allocates nothing (RFC 8489 §14.1 MAPPED-ADDRESS / §14.2 XOR-MAPPED-ADDRESS families).
 */
public final class StunAddress {
  public static final int FAMILY_IPV4 = 0x01;
  public static final int FAMILY_IPV6 = 0x02;
  public static final int IPV4_LENGTH = 4;
  public static final int IPV6_LENGTH = 16;

  private int family;
  private final byte[] address = new byte[IPV6_LENGTH];
  private int port;

  public int family() {
    return family;
  }

  public int port() {
    return port;
  }

  /** 4 for IPv4, 16 for IPv6, 0 when empty. */
  public int addressLength() {
    return switch (family) {
      case FAMILY_IPV4 -> IPV4_LENGTH;
      case FAMILY_IPV6 -> IPV6_LENGTH;
      default -> 0;
    };
  }

  /** Address byte {@code index} in network order. */
  public int addressByte(int index) {
    return address[index] & 0xff;
  }

  public boolean isEmpty() {
    return family == 0;
  }

  public void clear() {
    family = 0;
    port = 0;
    Arrays.fill(address, (byte) 0);
  }

  /** Sets family, address bytes (4 or 16, network order) and port. */
  public void set(int newFamily, byte[] bytes, int offset, int newPort) {
    if (newFamily != FAMILY_IPV4 && newFamily != FAMILY_IPV6) {
      throw new IllegalArgumentException("Unknown address family");
    }
    int length = newFamily == FAMILY_IPV4 ? IPV4_LENGTH : IPV6_LENGTH;
    family = newFamily;
    Arrays.fill(address, (byte) 0);
    System.arraycopy(bytes, offset, address, 0, length);
    port = newPort & 0xffff;
  }

  public void set(InetSocketAddress socketAddress) {
    byte[] bytes = socketAddress.getAddress().getAddress();
    set(bytes.length == IPV4_LENGTH ? FAMILY_IPV4 : FAMILY_IPV6, bytes, 0, socketAddress.getPort());
  }

  public void copyFrom(StunAddress other) {
    family = other.family;
    port = other.port;
    System.arraycopy(other.address, 0, address, 0, IPV6_LENGTH);
  }

  /** Allocates a socket address; for candidate gathering, not per-packet paths. */
  public InetSocketAddress toInetSocketAddress() {
    try {
      return new InetSocketAddress(
          InetAddress.getByAddress(Arrays.copyOf(address, addressLength())), port);
    } catch (UnknownHostException e) {
      throw new IllegalStateException("Empty STUN address", e);
    }
  }

  void setRaw(int newFamily, int newPort) {
    family = newFamily;
    port = newPort & 0xffff;
  }

  byte[] raw() {
    return address;
  }

  @Override
  public boolean equals(Object other) {
    return other instanceof StunAddress that
        && family == that.family
        && port == that.port
        && Arrays.equals(address, that.address);
  }

  @Override
  public int hashCode() {
    return (Arrays.hashCode(address) * 31 + port) * 31 + family;
  }

  @Override
  public String toString() {
    return isEmpty() ? "<empty>" : toInetSocketAddress().toString();
  }
}
