package com.einsteinmods.opendoors.harness;

import com.einsteinmods.opendoors.core.net.FakeTurnServer;
import com.einsteinmods.opendoors.core.net.ImpairedLink;
import com.einsteinmods.opendoors.core.net.TransportPair;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.management.ManagementFactory;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Arrays;
import java.util.Locale;
import java.util.Random;

/**
 * Dev-only latency harness (never packaged): round trips of 200-byte echoes at 20 Hz and a 4 MB
 * burst through the real transport (NetLoop, ICE, KCP, loopback proxy) on this machine.
 *
 * <p>Modes: {@code tcp} (plain loopback TCP: the vanilla LAN baseline), {@code direct} (UDP through
 * an impairment shim), {@code relay} (through a local TURN server, direct path hidden). {@code
 * peer} (two machines) is not automated: it needs a second network.
 *
 * <p>Usage: {@code ./gradlew runLatencyHarness -Pmode=direct -Ploss=0.01 -Pdelay=20 -Pjitter=5}
 */
public final class LatencyHarness {
  private static final int ECHO_BYTES = 200;
  private static final int ECHO_HZ = 20;
  private static final int BURST_BYTES = 4 * 1024 * 1024;

  private LatencyHarness() {}

  public static void main(String[] args) throws Exception {
    String mode = args.length > 0 ? args[0] : "direct";
    double loss = args.length > 1 ? Double.parseDouble(args[1]) : 0;
    int delay = args.length > 2 ? Integer.parseInt(args[2]) : 0;
    int jitter = args.length > 3 ? Integer.parseInt(args[3]) : 0;
    int seconds = args.length > 4 ? Integer.parseInt(args[4]) : 15;
    ImpairedLink.Impairment impairment = new ImpairedLink.Impairment(loss, 0, delay, jitter);
    System.out.printf(
        Locale.ROOT,
        "mode=%s loss=%.3f oneWayDelay=%dms jitter=%dms samples=%d%n",
        mode,
        loss,
        delay,
        jitter,
        seconds * ECHO_HZ);
    switch (mode) {
      case "tcp" -> runTcp(seconds);
      case "direct" -> {
        try (TransportPair pair = new TransportPair(impairment, null, 1)) {
          measure(pair.client, seconds);
        }
      }
      case "relay" -> {
        try (FakeTurnServer turn = new FakeTurnServer();
            TransportPair pair = new TransportPair(null, turn.credentials(), 1, true)) {
          measure(pair.client, seconds);
        }
      }
      case "nat" -> NatReport.run();
      default -> System.out.println("peer mode needs two machines on different networks");
    }
  }

  private static void runTcp(int seconds) throws Exception {
    try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
      Thread echo =
          new Thread(
              () -> {
                try (Socket socket = server.accept()) {
                  socket.setTcpNoDelay(true);
                  socket.getInputStream().transferTo(socket.getOutputStream());
                } catch (IOException exception) {
                  // Harness ends.
                }
              });
      echo.setDaemon(true);
      echo.start();
      try (Socket client = new Socket(InetAddress.getLoopbackAddress(), server.getLocalPort())) {
        client.setTcpNoDelay(true);
        measure(client, seconds);
      }
    }
  }

  private static void measure(Socket client, int seconds) throws Exception {
    OutputStream out = client.getOutputStream();
    DataInputStream in = new DataInputStream(client.getInputStream());
    byte[] message = new byte[ECHO_BYTES];
    byte[] reply = new byte[ECHO_BYTES];
    int samples = seconds * ECHO_HZ;
    long[] rtt = new long[samples];
    long periodNanos = 1_000_000_000L / ECHO_HZ;
    long next = System.nanoTime();
    long cpuBefore = processCpuNanos();
    for (int i = 0; i < samples; i++) {
      long start = System.nanoTime();
      out.write(message);
      out.flush();
      in.readFully(reply);
      rtt[i] = System.nanoTime() - start;
      next += periodNanos;
      long sleep = next - System.nanoTime();
      if (sleep > 0) {
        Thread.sleep(sleep / 1_000_000L, (int) (sleep % 1_000_000L));
      }
    }
    long cpuEcho = processCpuNanos() - cpuBefore;
    Arrays.sort(rtt);
    System.out.printf(
        Locale.ROOT,
        "rtt ms p50=%.3f p95=%.3f p99=%.3f max=%.3f (process CPU %.1f ms/s while echoing)%n",
        rtt[samples / 2] / 1e6,
        rtt[samples * 95 / 100] / 1e6,
        rtt[samples * 99 / 100] / 1e6,
        rtt[samples - 1] / 1e6,
        cpuEcho / 1e6 / seconds);
    burst(client.getOutputStream(), client.getInputStream());
  }

  private static void burst(OutputStream out, InputStream in) throws Exception {
    byte[] data = new byte[BURST_BYTES];
    new Random(1).nextBytes(data);
    long start = System.nanoTime();
    Thread writer =
        new Thread(
            () -> {
              try {
                out.write(data);
                out.flush();
              } catch (IOException exception) {
                // Reported by the reader.
              }
            });
    writer.start();
    byte[] back = new byte[BURST_BYTES];
    new DataInputStream(in).readFully(back);
    writer.join();
    double millis = (System.nanoTime() - start) / 1e6;
    System.out.printf(
        Locale.ROOT,
        "burst 4 MB echoed in %.1f ms (%.1f Mbit/s each way), intact=%s%n",
        millis,
        BURST_BYTES * 8 / (millis / 1000) / 1e6,
        Arrays.equals(data, back));
  }

  private static long processCpuNanos() {
    return ((com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean())
        .getProcessCpuTime();
  }
}
