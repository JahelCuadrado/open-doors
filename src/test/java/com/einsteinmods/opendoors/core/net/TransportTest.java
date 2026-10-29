package com.einsteinmods.opendoors.core.net;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class TransportTest {
  private static final int BURST_BYTES = 4 * 1024 * 1024;
  private static final int STALL_MILLIS = 150;

  @Test
  @Timeout(60)
  void directPathEchoesEveryByte() throws Exception {
    try (TransportPair pair = new TransportPair(null, null, 1)) {
      assertFalse(pair.joinerRelayed.get(5, TimeUnit.SECONDS));
      assertEchoes(pair, BURST_BYTES, 2);
    }
  }

  @Test
  @Timeout(120)
  void lossyReorderingDuplicatingLinkStillDeliversEveryByteInOrder() throws Exception {
    ImpairedLink.Impairment impairment = new ImpairedLink.Impairment(0.05, 0.03, 5, 20);
    try (TransportPair pair = new TransportPair(impairment, null, 7)) {
      assertEchoes(pair, BURST_BYTES, 3);
    }
  }

  @Test
  @Timeout(60)
  void relayCarriesTheStreamWhenNoDirectPathExists() throws Exception {
    try (FakeTurnServer turn = new FakeTurnServer();
        TransportPair pair = new TransportPair(null, turn.credentials(), 5, true)) {
      assertTrue(pair.joinerRelayed.get(15, TimeUnit.SECONDS));
      assertEchoes(pair, BURST_BYTES, 6);
      assertTrue(turn.relayedDatagrams.get() > BURST_BYTES / PeerSession.MTU);
    }
  }

  @Test
  @Timeout(30)
  void closingTheGameConnectionEndsBothSessions() throws Exception {
    try (TransportPair pair = new TransportPair(null, null, 3)) {
      assertEchoes(pair, 1024, 4);
      pair.client.close();
      assertEquals(PeerSession.End.CLOSED, pair.joinerEnded.get(5, TimeUnit.SECONDS));
      assertEquals(PeerSession.End.CLOSED, pair.hostEnded.get(5, TimeUnit.SECONDS));
    }
  }

  /**
   * Steady state: after warm-up, a JFR allocation sample of 4 MB echo rounds, with and without
   * network-thread stalls, must attribute nothing to mod code on the network threads. The JDK's own
   * selector boxes file descriptors above 127 (16 bytes per wake-up, in {@code
   * KQueueSelectorImpl}/{@code EPollSelectorImpl}); that is reported separately and not counted.
   */
  @Test
  @Timeout(90)
  void networkThreadAllocatesNothingInSteadyState() throws Exception {
    try (TransportPair pair = new TransportPair(null, null, 8)) {
      for (int round = 0; round < 3; round++) {
        assertEchoes(pair, BURST_BYTES, 9 + round);
      }
      java.nio.file.Path file = java.nio.file.Files.createTempFile("opendoors-alloc", ".jfr");
      try (jdk.jfr.Recording recording = new jdk.jfr.Recording()) {
        recording.enable("jdk.ObjectAllocationSample").with("throttle", "10000/s").withStackTrace();
        recording.start();
        // The same under a loaded machine: both network threads stall now and then, so queues
        // fill far beyond what an undisturbed run reaches (the pools must already hold them).
        Thread staller = stallNetworkThreads(pair);
        assertEchoes(pair, BURST_BYTES, 21);
        assertEchoes(pair, BURST_BYTES, 22);
        staller.interrupt();
        staller.join();
        recording.stop();
        recording.dump(file);
      }
      long modBytes = 0;
      long jdkBytes = 0;
      StringBuilder modSites = new StringBuilder();
      for (jdk.jfr.consumer.RecordedEvent event :
          jdk.jfr.consumer.RecordingFile.readAllEvents(file)) {
        jdk.jfr.consumer.RecordedThread thread = event.getThread();
        if (thread == null || !"OpenDoors Net".equals(thread.getJavaName())) {
          continue;
        }
        long weight = event.getLong("weight");
        if (excludedFromModCount(event.getStackTrace())) {
          jdkBytes += weight;
        } else {
          modBytes += weight;
          modSites
              .append(event.getLong("weight"))
              .append("B ")
              .append(event.getClass("objectClass").getName())
              .append('\n');
          for (jdk.jfr.consumer.RecordedFrame frame : event.getStackTrace().getFrames()) {
            modSites
                .append("   ")
                .append(frame.getMethod().getType().getName())
                .append('.')
                .append(frame.getMethod().getName())
                .append(':')
                .append(frame.getLineNumber())
                .append('\n');
          }
        }
      }
      java.nio.file.Files.delete(file);
      System.out.println(
          "ALLOCATION mod=" + modBytes + " excluded(jdkSelector+testStalls)=" + jdkBytes);
      assertEquals(0, modBytes, "mod-owned allocation on the network thread: " + modSites);
    }
  }

  /** Blocks each network thread for 150 ms every 300 ms until interrupted (scheduler delay). */
  private static Thread stallNetworkThreads(TransportPair pair) {
    Runnable stall =
        () -> {
          try {
            Thread.sleep(STALL_MILLIS);
          } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
          }
        };
    Thread staller =
        new Thread(
            () -> {
              try {
                for (long n = 0; !Thread.currentThread().isInterrupted(); n++) {
                  (n % 3 == 2 ? pair.joinerLoop : pair.hostLoop).execute(stall);
                  Thread.sleep(STALL_MILLIS * 2);
                }
              } catch (InterruptedException exception) {
                // Done.
              }
            },
            "Network stall");
    staller.start();
    return staller;
  }

  private static boolean excludedFromModCount(jdk.jfr.consumer.RecordedStackTrace stack) {
    if (stack == null) {
      return false;
    }
    for (jdk.jfr.consumer.RecordedFrame frame : stack.getFrames()) {
      String type = frame.getMethod().getType().getName();
      if (type.startsWith("com.einsteinmods.") && type.contains("TransportTest")) {
        // The test's own stall task (Thread.sleep's JFR event), not mod code.
        return true;
      }
      if (type.startsWith("com.einsteinmods.")) {
        return false;
      }
      if (type.startsWith("sun.nio.ch.")) {
        return true;
      }
    }
    return false;
  }

  static void assertEchoes(TransportPair pair, int size, long seed) throws Exception {
    byte[] sent = new byte[size];
    new Random(seed).nextBytes(sent);
    OutputStream out = pair.client.getOutputStream();
    CompletableFuture<Void> writer =
        CompletableFuture.runAsync(
            () -> {
              try {
                Random chunks = new Random(seed);
                int offset = 0;
                while (offset < size) {
                  int length = Math.min(size - offset, 1 + chunks.nextInt(20_000));
                  out.write(sent, offset, length);
                  offset += length;
                }
                out.flush();
              } catch (java.io.IOException exception) {
                throw new java.io.UncheckedIOException(exception);
              }
            });
    byte[] received = new byte[size];
    InputStream in = pair.client.getInputStream();
    in.readNBytes(received, 0, size);
    writer.get(10, TimeUnit.SECONDS);
    assertArrayEquals(sent, received);
  }
}
