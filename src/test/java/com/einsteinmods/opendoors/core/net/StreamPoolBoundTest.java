package com.einsteinmods.opendoors.core.net;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.einsteinmods.opendoors.core.kcp.Kcp;
import java.lang.management.ManagementFactory;
import org.junit.jupiter.api.Test;

/**
 * The pools reserved when a stream opens cover the most it can ever hold, so a late network thread
 * (loaded machine) never makes it allocate: a full send side (the game wrote faster than the peer
 * acks) and a full receive side (a window out of order plus a window the game has not read yet) at
 * the same time.
 */
class StreamPoolBoundTest {
  private static final int CONV = 0x1234_5680;
  private static final int SEGMENT_DATA = PeerSession.MTU - Kcp.OVERHEAD;
  private static final int PUSH = 81;

  private final com.sun.management.ThreadMXBean threads =
      (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();

  @Test
  void worstCaseQueuesFitTheReservedPools() {
    // Datagrams from a peer: two full windows, prepared before measuring.
    int segments = 2 * PeerSession.KCP_WINDOW;
    byte[][] fromPeer = new byte[segments][];
    for (int sn = 0; sn < segments; sn++) {
      fromPeer[sn] = push(sn);
    }
    byte[] gameWrite = new byte[PeerSession.BUFFER_SIZE];
    int[] sent = new int[1];
    Kcp stream = PeerSession.newStream(CONV, (buffer, length) -> sent[0]++);
    int now = 0;
    stream.update(now);

    long before = threads.getCurrentThreadAllocatedBytes();
    // Send side: exactly what PeerSession.pump does with a peer that never acks.
    while (stream.waitSnd() < PeerSession.SEND_QUEUE_LIMIT) {
      stream.send(gameWrite, 0, gameWrite.length);
    }
    // Receive side: a window out of order (sn 0 missing), then sn 0 moves it all to the receive
    // queue, which the game does not read; then another full window out of order.
    for (int sn = 1; sn < PeerSession.KCP_WINDOW; sn++) {
      stream.input(fromPeer[sn], 0, fromPeer[sn].length);
    }
    stream.input(fromPeer[0], 0, fromPeer[0].length);
    for (int sn = PeerSession.KCP_WINDOW + 1; sn < segments; sn++) {
      stream.input(fromPeer[sn], 0, fromPeer[sn].length);
    }
    for (int tick = 0; tick < 100; tick++) {
      now += PeerSession.KCP_INTERVAL_MILLIS;
      stream.update(now);
    }
    long allocated = threads.getCurrentThreadAllocatedBytes() - before;

    assertTrue(stream.waitSnd() >= PeerSession.SEND_QUEUE_LIMIT, "send side full");
    assertTrue(stream.peekSize() > 0, "receive queue full and unread");
    assertTrue(sent[0] > 0, "the stream kept transmitting");
    assertEquals(0, allocated, "a reserved stream must not allocate when its queues are full");
  }

  /** One full-size KCP data segment from the peer (ikcp header, little-endian). */
  private static byte[] push(int sn) {
    byte[] datagram = new byte[Kcp.OVERHEAD + SEGMENT_DATA];
    writeInt(datagram, 0, CONV);
    datagram[4] = (byte) PUSH;
    datagram[5] = 0;
    datagram[6] = (byte) PeerSession.KCP_WINDOW;
    datagram[7] = (byte) (PeerSession.KCP_WINDOW >>> 8);
    writeInt(datagram, 8, 0);
    writeInt(datagram, 12, sn);
    writeInt(datagram, 16, 0);
    writeInt(datagram, 20, SEGMENT_DATA);
    return datagram;
  }

  private static void writeInt(byte[] data, int offset, int value) {
    data[offset] = (byte) value;
    data[offset + 1] = (byte) (value >>> 8);
    data[offset + 2] = (byte) (value >>> 16);
    data[offset + 3] = (byte) (value >>> 24);
  }
}
