package com.einsteinmods.opendoors.core.net;

import java.net.InetSocketAddress;

/** Sends one UDP datagram from the shared socket (network thread only). */
@FunctionalInterface
public interface DatagramSender {
  void send(byte[] data, int offset, int length, InetSocketAddress target);
}
