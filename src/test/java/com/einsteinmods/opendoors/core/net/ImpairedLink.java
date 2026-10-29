package com.einsteinmods.opendoors.core.net;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Random;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * UDP middlebox for tests and the latency harness: everything between a joiner and a host goes
 * through two sockets (A faces the joiner, B faces the host) and each datagram may be dropped,
 * duplicated or delayed by a random jitter (which also reorders).
 */
public final class ImpairedLink implements AutoCloseable {
  /** Per-direction impairment. */
  public record Impairment(double loss, double duplicate, int baseDelayMillis, int jitterMillis) {
    public static final Impairment NONE = new Impairment(0, 0, 0, 0);
  }

  private final DatagramSocket joinerSide;
  private final DatagramSocket hostSide;
  private final InetSocketAddress joiner;
  private final InetSocketAddress host;
  private final Impairment impairment;
  private final Random random;
  private final ScheduledExecutorService delays =
      Executors.newSingleThreadScheduledExecutor(
          runnable -> {
            Thread thread = new Thread(runnable, "ImpairedLink delay");
            thread.setDaemon(true);
            return thread;
          });
  private volatile boolean open = true;

  /** Datagrams the joiner sent towards the host, with their arrival time (before impairment). */
  public final java.util.concurrent.ConcurrentLinkedQueue<Long> fromJoiner =
      new java.util.concurrent.ConcurrentLinkedQueue<>();

  /** Datagrams the host sent towards the joiner. */
  public final java.util.concurrent.ConcurrentLinkedQueue<Long> fromHost =
      new java.util.concurrent.ConcurrentLinkedQueue<>();

  public ImpairedLink(int joinerPort, int hostPort, Impairment impairment, long seed)
      throws IOException {
    InetAddress loopback = InetAddress.getLoopbackAddress();
    joinerSide = new DatagramSocket(0, loopback);
    hostSide = new DatagramSocket(0, loopback);
    joinerSide.setReceiveBufferSize(1 << 20);
    hostSide.setReceiveBufferSize(1 << 20);
    joiner = new InetSocketAddress(loopback, joinerPort);
    host = new InetSocketAddress(loopback, hostPort);
    this.impairment = impairment;
    this.random = new Random(seed);
    start(joinerSide, hostSide, host, fromJoiner);
    start(hostSide, joinerSide, joiner, fromHost);
  }

  /** Port the joiner must use to reach the host. */
  public int joinerFacingPort() {
    return joinerSide.getLocalPort();
  }

  /** Port the host must use to reach the joiner. */
  public int hostFacingPort() {
    return hostSide.getLocalPort();
  }

  private void start(
      DatagramSocket in,
      DatagramSocket out,
      InetSocketAddress target,
      java.util.Queue<Long> arrivals) {
    Thread thread =
        new Thread(
            () -> {
              byte[] buffer = new byte[2048];
              while (open) {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                try {
                  in.receive(packet);
                } catch (IOException exception) {
                  return;
                }
                arrivals.add(System.nanoTime());
                byte[] copy = java.util.Arrays.copyOf(packet.getData(), packet.getLength());
                forward(out, target, copy);
              }
            },
            "ImpairedLink");
    thread.setDaemon(true);
    thread.start();
  }

  private void forward(DatagramSocket out, InetSocketAddress target, byte[] data) {
    int copies;
    int delay;
    synchronized (random) {
      if (random.nextDouble() < impairment.loss()) {
        return;
      }
      copies = random.nextDouble() < impairment.duplicate() ? 2 : 1;
      delay =
          impairment.baseDelayMillis()
              + (impairment.jitterMillis() == 0 ? 0 : random.nextInt(impairment.jitterMillis()));
    }
    for (int i = 0; i < copies; i++) {
      Runnable send =
          () -> {
            try {
              out.send(new DatagramPacket(data, data.length, target));
            } catch (IOException exception) {
              // Closed.
            }
          };
      if (delay == 0) {
        send.run();
      } else {
        delays.schedule(send, delay, TimeUnit.MILLISECONDS);
      }
    }
  }

  @Override
  public void close() {
    open = false;
    joinerSide.close();
    hostSide.close();
    delays.shutdownNow();
  }
}
