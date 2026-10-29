package com.einsteinmods.opendoors.core.net;

import com.einsteinmods.opendoors.core.stun.Demux;
import com.einsteinmods.opendoors.core.stun.StunAddress;
import com.einsteinmods.opendoors.core.stun.StunIntegrity;
import com.einsteinmods.opendoors.core.stun.StunMessage;
import com.einsteinmods.opendoors.core.stun.StunReader;
import com.einsteinmods.opendoors.core.stun.StunWriter;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Minimal single-allocation TURN server (RFC 8656 over UDP) for tests and the latency harness:
 * long-term credentials with a 401 challenge, CreatePermission (IP based), ChannelBind, Refresh,
 * Send/Data indications and ChannelData.
 */
public final class FakeTurnServer implements AutoCloseable {
  public static final String USERNAME = "user";
  public static final String PASSWORD = "secret";
  private static final String REALM = "test.realm";
  private static final String NONCE = "abcdef0123456789";

  private final DatagramSocket control;
  private final DatagramSocket relay;
  private final StunIntegrity key = StunIntegrity.longTerm(USERNAME, REALM, PASSWORD);
  private final Set<InetAddress> permissions = new HashSet<>();
  private volatile InetSocketAddress client;
  private volatile InetSocketAddress channelPeer;
  private volatile int channel = -1;
  public final AtomicInteger relayedDatagrams = new AtomicInteger();
  private volatile boolean open = true;

  public FakeTurnServer() throws IOException {
    InetAddress loopback = InetAddress.getLoopbackAddress();
    control = new DatagramSocket(0, loopback);
    relay = new DatagramSocket(0, loopback);
    control.setReceiveBufferSize(1 << 20);
    relay.setReceiveBufferSize(1 << 20);
    daemon(this::controlLoop);
    daemon(this::relayLoop);
  }

  public RelayCredentials credentials() {
    return new RelayCredentials(
        new InetSocketAddress(InetAddress.getLoopbackAddress(), control.getLocalPort()),
        USERNAME,
        PASSWORD);
  }

  private static void daemon(Runnable body) {
    Thread thread = new Thread(body, "FakeTurnServer");
    thread.setDaemon(true);
    thread.start();
  }

  private void controlLoop() {
    byte[] buffer = new byte[2048];
    byte[] out = new byte[2048];
    StunReader reader = new StunReader();
    StunWriter writer = new StunWriter();
    StunAddress address = new StunAddress();
    while (open) {
      DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
      try {
        control.receive(packet);
      } catch (IOException exception) {
        return;
      }
      InetSocketAddress from = (InetSocketAddress) packet.getSocketAddress();
      int length = packet.getLength();
      if (Demux.classify(buffer[0]) == Demux.KIND_CHANNEL_DATA) {
        if (Demux.isValidChannelData(buffer, 0, length)
            && Demux.channelNumber(buffer, 0) == channel
            && channelPeer != null) {
          relayTo(
              channelPeer, Arrays.copyOfRange(buffer, 4, 4 + Demux.channelDataLength(buffer, 0)));
        }
        continue;
      }
      if (!reader.wrap(buffer, 0, length)) {
        continue;
      }
      int method = reader.method();
      if (reader.messageClass() == StunMessage.CLASS_INDICATION
          && method == StunMessage.METHOD_SEND) {
        if (reader.find(StunMessage.ATTR_XOR_PEER_ADDRESS) && reader.readXorAddress(address)) {
          InetSocketAddress peer = address.toInetSocketAddress();
          reader.rewind();
          if (reader.find(StunMessage.ATTR_DATA)) {
            relayTo(
                peer,
                Arrays.copyOfRange(
                    buffer, reader.valueOffset(), reader.valueOffset() + reader.valueLength()));
          }
        }
        continue;
      }
      if (reader.messageClass() != StunMessage.CLASS_REQUEST) {
        continue;
      }
      byte[] transaction = new byte[12];
      reader.copyTransactionId(transaction, 0);
      reader.rewind();
      if (!reader.find(StunMessage.ATTR_MESSAGE_INTEGRITY)) {
        writer.begin(
            out, 0, StunMessage.messageType(method, StunMessage.CLASS_ERROR), transaction, 0);
        writer.addErrorCode(401, "Unauthorized");
        writer.addUtf8(StunMessage.ATTR_REALM, REALM);
        writer.addUtf8(StunMessage.ATTR_NONCE, NONCE);
        send(out, writer.length(), from);
        continue;
      }
      if (!reader.verifyIntegrity(key)) {
        continue;
      }
      writer.begin(
          out, 0, StunMessage.messageType(method, StunMessage.CLASS_SUCCESS), transaction, 0);
      switch (method) {
        case StunMessage.METHOD_ALLOCATE -> {
          client = from;
          address.set((InetSocketAddress) relay.getLocalSocketAddress());
          writer.addXorAddress(StunMessage.ATTR_XOR_RELAYED_ADDRESS, address);
          address.set(from);
          writer.addXorAddress(StunMessage.ATTR_XOR_MAPPED_ADDRESS, address);
          writer.addU32(StunMessage.ATTR_LIFETIME, 600);
        }
        case StunMessage.METHOD_CREATE_PERMISSION -> {
          reader.rewind();
          while (reader.next()) {
            if (reader.attributeType() == StunMessage.ATTR_XOR_PEER_ADDRESS
                && reader.readXorAddress(address)) {
              synchronized (permissions) {
                permissions.add(address.toInetSocketAddress().getAddress());
              }
            }
          }
        }
        case StunMessage.METHOD_CHANNEL_BIND -> {
          reader.rewind();
          if (reader.find(StunMessage.ATTR_CHANNEL_NUMBER)) {
            channel = reader.readU32() >>> 16;
          }
          reader.rewind();
          if (reader.find(StunMessage.ATTR_XOR_PEER_ADDRESS) && reader.readXorAddress(address)) {
            channelPeer = address.toInetSocketAddress();
          }
        }
        case StunMessage.METHOD_REFRESH -> writer.addU32(StunMessage.ATTR_LIFETIME, 600);
        default -> {
          continue;
        }
      }
      writer.addMessageIntegrity(key);
      send(out, writer.length(), from);
    }
  }

  private void relayLoop() {
    byte[] buffer = new byte[2048];
    byte[] out = new byte[2100];
    StunWriter writer = new StunWriter();
    StunAddress address = new StunAddress();
    byte[] transaction = new byte[12];
    while (open) {
      DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
      try {
        relay.receive(packet);
      } catch (IOException exception) {
        return;
      }
      InetSocketAddress from = (InetSocketAddress) packet.getSocketAddress();
      boolean permitted;
      synchronized (permissions) {
        permitted = permissions.contains(from.getAddress());
      }
      if (!permitted || client == null) {
        continue;
      }
      relayedDatagrams.incrementAndGet();
      if (from.equals(channelPeer)) {
        Demux.writeChannelDataHeader(out, 0, channel, packet.getLength());
        System.arraycopy(buffer, 0, out, 4, packet.getLength());
        send(out, 4 + packet.getLength(), client);
      } else {
        address.set(from);
        writer.begin(
            out,
            0,
            StunMessage.messageType(StunMessage.METHOD_DATA, StunMessage.CLASS_INDICATION),
            transaction,
            0);
        writer.addXorAddress(StunMessage.ATTR_XOR_PEER_ADDRESS, address);
        writer.addBytes(StunMessage.ATTR_DATA, buffer, 0, packet.getLength());
        send(out, writer.length(), client);
      }
    }
  }

  private void relayTo(InetSocketAddress peer, byte[] data) {
    synchronized (permissions) {
      if (!permissions.contains(peer.getAddress())) {
        return;
      }
    }
    relayedDatagrams.incrementAndGet();
    try {
      relay.send(new DatagramPacket(data, data.length, peer));
    } catch (IOException exception) {
      // Closed.
    }
  }

  private void send(byte[] data, int length, InetSocketAddress target) {
    try {
      control.send(new DatagramPacket(data, length, target));
    } catch (IOException exception) {
      // Closed.
    }
  }

  @Override
  public void close() {
    open = false;
    control.close();
    relay.close();
  }
}
