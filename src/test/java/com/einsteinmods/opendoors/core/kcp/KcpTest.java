package com.einsteinmods.opendoors.core.kcp;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.PriorityQueue;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** KCP over a simulated impaired link: every byte sent arrives once, in order. */
final class KcpTest {
  private static final int CONV = 0x5A17C0DE;
  private static final int PAYLOAD_BYTES = 4 * 1024 * 1024;
  private static final int MTU = 1200;
  private static final int WINDOW = 256;
  private static final int SIMULATION_LIMIT_MS = 600_000;

  /** One direction of a lossy, duplicating, jittery (hence reordering) datagram link. */
  private static final class ImpairedLink {
    private record InFlight(int deliverAt, long order, byte[] data) {}

    private final Random random;
    private final double loss;
    private final double duplication;
    private final int baseDelay;
    private final int jitter;
    private final PriorityQueue<InFlight> queue =
        new PriorityQueue<>(
            (a, b) ->
                a.deliverAt != b.deliverAt
                    ? Integer.compare(a.deliverAt, b.deliverAt)
                    : Long.compare(a.order, b.order));
    private long order;
    private int now;
    int dropped;
    int duplicated;

    ImpairedLink(long seed, double loss, double duplication, int baseDelay, int jitter) {
      this.random = new Random(seed);
      this.loss = loss;
      this.duplication = duplication;
      this.baseDelay = baseDelay;
      this.jitter = jitter;
    }

    void send(byte[] buffer, int length) {
      if (random.nextDouble() < loss) {
        dropped++;
        return;
      }
      byte[] copy = Arrays.copyOf(buffer, length);
      enqueue(copy);
      if (random.nextDouble() < duplication) {
        duplicated++;
        enqueue(copy);
      }
    }

    private void enqueue(byte[] data) {
      int delay = baseDelay + random.nextInt(jitter + 1);
      queue.add(new InFlight(now + delay, order++, data));
    }

    void deliver(int time, Kcp target) {
      now = time;
      while (!queue.isEmpty() && queue.peek().deliverAt - time <= 0) {
        byte[] data = queue.poll().data;
        assertEquals(0, target.input(data, 0, data.length));
      }
    }
  }

  /** Writes a deterministic byte stream in chunks and checks what the other side reads. */
  private static final class Endpoint {
    final Kcp kcp;
    final byte[] payload;
    final Random chunks;
    final ByteArrayOutputStream received = new ByteArrayOutputStream();
    final byte[] receiveBuffer = new byte[256 * 1024];
    int written;

    Endpoint(Kcp kcp, byte[] payload, long seed) {
      this.kcp = kcp;
      this.payload = payload;
      this.chunks = new Random(seed);
    }

    void pump() {
      while (written < payload.length && kcp.waitSnd() < WINDOW * 2) {
        int chunk = Math.min(1 + chunks.nextInt(3000), payload.length - written);
        int queued = kcp.send(payload, written, chunk);
        assertTrue(queued > 0, "send rejected");
        written += queued;
      }
      int size;
      while ((size = kcp.recv(receiveBuffer, 0, receiveBuffer.length)) > 0) {
        received.write(receiveBuffer, 0, size);
      }
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void impairedLinkDeliversEveryByteInOrderBothWays(boolean streamMode) {
    ImpairedLink toB = new ImpairedLink(1, 0.10, 0.05, 20, 40);
    ImpairedLink toA = new ImpairedLink(2, 0.10, 0.05, 20, 40);
    Kcp a = configured(new Kcp(CONV, toB::send), streamMode);
    Kcp b = configured(new Kcp(CONV, toA::send), streamMode);
    byte[] payloadA = randomBytes(11);
    byte[] payloadB = randomBytes(12);
    Endpoint endpointA = new Endpoint(a, payloadA, 21);
    Endpoint endpointB = new Endpoint(b, payloadB, 22);

    // Start just before the 32-bit clock wraps to exercise wrap-around arithmetic.
    int start = Integer.MAX_VALUE - 5_000;
    int elapsed = 0;
    while (endpointA.received.size() < payloadB.length
        || endpointB.received.size() < payloadA.length) {
      assertTrue(elapsed < SIMULATION_LIMIT_MS, "transfer did not finish");
      int now = start + elapsed;
      toB.deliver(now, b);
      toA.deliver(now, a);
      endpointA.pump();
      endpointB.pump();
      a.update(now);
      b.update(now);
      elapsed++;
    }
    assertArrayEquals(payloadA, endpointB.received.toByteArray());
    assertArrayEquals(payloadB, endpointA.received.toByteArray());
    assertEquals(0, a.state());
    assertEquals(0, b.state());
    assertTrue(toB.dropped > 0 && toB.duplicated > 0, "impairments were not exercised");
  }

  @Test
  void messageModeKeepsBoundaries() {
    List<byte[]> wire = new ArrayList<>();
    Kcp a = configured(new Kcp(CONV, (buffer, length) -> wire.add(copy(buffer, length))), false);
    Kcp b = configured(new Kcp(CONV, (buffer, length) -> {}), false);
    byte[] message = Arrays.copyOf(randomBytes(3), 5000);
    assertEquals(5000, a.send(message, 0, message.length));
    assertEquals(3, a.send(message, 0, 3));
    a.update(0);
    for (byte[] datagram : wire) {
      assertEquals(0, b.input(datagram, 0, datagram.length));
    }
    assertEquals(5000, b.peekSize());
    byte[] out = new byte[6000];
    assertEquals(-3, b.recv(out, 0, 100));
    assertEquals(5000, b.recv(out, 0, out.length));
    assertArrayEquals(message, Arrays.copyOf(out, 5000));
    assertEquals(3, b.recv(out, 0, out.length));
    assertEquals(-1, b.recv(out, 0, out.length));
  }

  @Test
  void rejectsOtherConversationAndGarbage() {
    List<byte[]> wire = new ArrayList<>();
    Kcp a = configured(new Kcp(CONV, (buffer, length) -> wire.add(copy(buffer, length))), true);
    Kcp other = configured(new Kcp(CONV + 1, (buffer, length) -> {}), true);
    a.send(new byte[] {1, 2, 3}, 0, 3);
    a.update(0);
    byte[] datagram = wire.getFirst();
    assertEquals(-1, other.input(datagram, 0, datagram.length));
    assertEquals(-1, other.input(datagram, 0, Kcp.OVERHEAD - 1));
    Kcp b = configured(new Kcp(CONV, (buffer, length) -> {}), true);
    assertEquals(-2, b.input(datagram, 0, datagram.length - 1));
    byte[] badCommand = datagram.clone();
    badCommand[4] = 99;
    assertEquals(-3, b.input(badCommand, 0, badCommand.length));
    assertEquals(-1, b.recv(new byte[16], 0, 16));
  }

  @Test
  void steadyStateAllocatesNothing() {
    for (boolean streamMode : new boolean[] {true, false}) {
      long allocated = steadyStateAllocation(streamMode);
      assertTrue(
          allocated >= 0 && allocated < 4096,
          "allocated " + allocated + " bytes in steady state (stream mode " + streamMode + ")");
    }
  }

  /** A datagram queue between two endpoints, reused every round. */
  private static final class Wire {
    final byte[][] data = new byte[512][MTU + Kcp.OVERHEAD];
    final int[] lengths = new int[512];
    int count;

    void accept(byte[] buffer, int length) {
      System.arraycopy(buffer, 0, data[count], 0, length);
      lengths[count++] = length;
    }

    void deliverTo(Kcp kcp) {
      for (int i = 0; i < count; i++) {
        kcp.input(data[i], 0, lengths[i]);
      }
      count = 0;
    }
  }

  /** Bytes allocated by 20,000 rounds of 4 x 200-byte sends each way, after warm-up. */
  private static long steadyStateAllocation(boolean streamMode) {
    Wire toB = new Wire();
    Wire toA = new Wire();
    Kcp a = configured(new Kcp(CONV, toB::accept), streamMode);
    Kcp b = configured(new Kcp(CONV, toA::accept), streamMode);
    byte[] message = new byte[200];
    byte[] sink = new byte[64 * 1024];
    int clock = 0;
    com.sun.management.ThreadMXBean threads =
        (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
    long before = 0;
    for (int round = 0; round < 70_000; round++) {
      if (round == 50_000) {
        before = threads.getCurrentThreadAllocatedBytes();
      }
      for (int i = 0; i < 4; i++) {
        a.send(message, 0, message.length);
        b.send(message, 0, message.length);
      }
      clock += 10;
      a.update(clock);
      b.update(clock);
      toB.deliverTo(b);
      toA.deliverTo(a);
      while (a.recv(sink, 0, sink.length) > 0) {
        // Drain.
      }
      while (b.recv(sink, 0, sink.length) > 0) {
        // Drain.
      }
    }
    return threads.getCurrentThreadAllocatedBytes() - before;
  }

  private static Kcp configured(Kcp kcp, boolean streamMode) {
    kcp.nodelay(1, 10, 2, 1);
    kcp.wndsize(WINDOW, WINDOW);
    kcp.setMtu(MTU);
    kcp.setStreamMode(streamMode);
    return kcp;
  }

  private static byte[] randomBytes(long seed) {
    byte[] bytes = new byte[PAYLOAD_BYTES];
    new Random(seed).nextBytes(bytes);
    return bytes;
  }

  private static byte[] copy(byte[] buffer, int length) {
    return Arrays.copyOf(buffer, length);
  }
}
