package com.einsteinmods.opendoors.core.net;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.einsteinmods.opendoors.core.signaling.Candidate;
import com.einsteinmods.opendoors.core.signaling.IceParameters;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Both sides send connectivity checks at once (simultaneous open), the host from the moment the
 * offer arrives (before its own STUN gathering ends): every 50 ms for 5 s, then every 250 ms until
 * the 10 s timeout. A link that drops everything shows what each side sent; diagnostics report
 * every step.
 */
class CheckSchedulingTest {
  private static final InetAddress LOOPBACK = InetAddress.getLoopbackAddress();

  @Test
  @Timeout(30)
  void bothSidesCheckAggressivelyThenSlowDownAndDiagnosticsTellWhy() throws Exception {
    List<String> diagnostics = new CopyOnWriteArrayList<>();
    ConnectionLog.setSink(diagnostics::add);
    // A STUN server that never answers: the host stays in gathering for the full 2 s.
    try (DatagramSocket silentStun = new DatagramSocket(0, LOOPBACK);
        NetLoop host =
            NetLoop.open(
                new NetLoop.Options(
                    List.of(LOOPBACK),
                    new InetSocketAddress(LOOPBACK, silentStun.getLocalPort()),
                    null));
        NetLoop joiner = NetLoop.open(new NetLoop.Options(List.of(LOOPBACK), null))) {
      ImpairedLink link =
          new ImpairedLink(
              joiner.port(), host.port(), new ImpairedLink.Impairment(1.0, 0, 0, 0), 1);
      try (link) {
        CompletableFuture<IceParameters> joinerIce = new CompletableFuture<>();
        CompletableFuture<PeerSession.End> hostEnd = new CompletableFuture<>();
        CompletableFuture<PeerSession.End> joinerEnd = new CompletableFuture<>();
        joiner.execute(
            () ->
                joiner.startSession(PeerSession.Role.JOINER, listener(joinerIce, joinerEnd), null));
        IceParameters offer = retarget(joinerIce.get(5, TimeUnit.SECONDS), link.hostFacingPort());
        long offerAt = System.nanoTime();
        CompletableFuture<IceParameters> hostIce = new CompletableFuture<>();
        host.execute(
            () -> {
              PeerSession session =
                  host.startSession(PeerSession.Role.HOST, listener(hostIce, hostEnd), null);
              host.setRemote(session, offer, 0x1234_5680L);
            });
        // The host checks while still gathering: its answer only exists after the 2 s STUN wait.
        IceParameters answer = hostIce.get(5, TimeUnit.SECONDS);
        long answerAt = System.nanoTime();
        assertTrue(millis(answerAt - offerAt) >= 1_500, "host gathered for the STUN timeout");
        assertTrue(
            countBetween(link.fromHost, offerAt, offerAt + ms(1_000)) >= 15,
            "host checks start with the offer, not after gathering");
        IceParameters retargeted = retarget(answer, link.joinerFacingPort());
        long joinerStart = System.nanoTime();
        joiner.execute(
            () ->
                joinerSession(joiner)
                    .ifPresent(s -> joiner.setRemote(s, retargeted, 0x1234_5680L)));
        assertEquals(PeerSession.End.UNREACHABLE, joinerEnd.get(15, TimeUnit.SECONDS));
        assertEquals(PeerSession.End.UNREACHABLE, hostEnd.get(15, TimeUnit.SECONDS));

        // Joiner: ~20 checks/s for 5 s, then ~4/s (one remote candidate).
        int joinerFast = countBetween(link.fromJoiner, joinerStart, joinerStart + ms(4_800));
        int joinerSlow =
            countBetween(link.fromJoiner, joinerStart + ms(5_300), joinerStart + ms(9_700));
        assertTrue(joinerFast >= 70 && joinerFast <= 110, "joiner fast checks " + joinerFast);
        assertTrue(joinerSlow >= 12 && joinerSlow <= 22, "joiner slow checks " + joinerSlow);
        // Host: same pattern from the offer.
        int hostFast = countBetween(link.fromHost, offerAt, offerAt + ms(4_800));
        assertTrue(hostFast >= 70, "host fast checks " + hostFast);
        System.out.println(
            "CHECKS host first 1 s: "
                + countBetween(link.fromHost, offerAt, offerAt + ms(1_000))
                + ", host 0-4.8 s: "
                + hostFast
                + ", joiner 0-4.8 s: "
                + joinerFast
                + ", joiner 5.3-9.7 s: "
                + joinerSlow
                + ", host answer after "
                + millis(answerAt - offerAt)
                + " ms");
      }
      String all = String.join("\n", diagnostics);
      assertTrue(all.contains("first check sent to"), all);
      assertTrue(all.contains("remote candidates"), all);
      assertTrue(all.contains("ended: UNREACHABLE"), all);
      assertTrue(all.contains("STUN ") && all.contains("no answer"), all);
    } finally {
      ConnectionLog.setSink(null);
    }
  }

  @Test
  void offMeansNothingIsWritten() {
    List<String> lines = new ArrayList<>();
    ConnectionLog.setSink(null);
    ConnectionLog.log("ignored");
    assertTrue(lines.isEmpty());
    assertTrue(!ConnectionLog.on());
  }

  private static java.util.Optional<PeerSession> joinerSession(NetLoop loop) {
    return java.util.Optional.ofNullable(JOINER.get());
  }

  private static final java.util.concurrent.atomic.AtomicReference<PeerSession> JOINER =
      new java.util.concurrent.atomic.AtomicReference<>();

  private static PeerSession.Listener listener(
      CompletableFuture<IceParameters> gathered, CompletableFuture<PeerSession.End> ended) {
    return new PeerSession.Listener() {
      @Override
      public void onGathered(PeerSession session, IceParameters local) {
        if (session.role() == PeerSession.Role.JOINER) {
          JOINER.set(session);
        }
        gathered.complete(local);
      }

      @Override
      public void onConnected(PeerSession session, boolean relayed) {}

      @Override
      public void onEnded(PeerSession session, PeerSession.End end) {
        ended.complete(end);
      }
    };
  }

  private static IceParameters retarget(IceParameters ice, int port) {
    return new IceParameters(
        ice.ufrag(),
        ice.password(),
        List.of(new Candidate(Candidate.Kind.HOST, "127.0.0.1", port)));
  }

  private static int countBetween(java.util.Queue<Long> times, long from, long to) {
    int count = 0;
    for (long time : times) {
      if (time >= from && time < to) {
        count++;
      }
    }
    return count;
  }

  private static long ms(long millis) {
    return millis * 1_000_000L;
  }

  private static long millis(long nanos) {
    return nanos / 1_000_000L;
  }
}
