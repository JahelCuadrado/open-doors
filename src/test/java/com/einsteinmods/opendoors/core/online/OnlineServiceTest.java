package com.einsteinmods.opendoors.core.online;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.einsteinmods.opendoors.core.net.NetLoop;
import com.einsteinmods.opendoors.core.net.PeerSession;
import com.einsteinmods.opendoors.core.net.TransportPair;
import com.einsteinmods.opendoors.core.signaling.HostMode;
import java.net.InetAddress;
import java.net.Socket;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Host and joiners wired through an in-memory hub, with real network threads on loopback. */
class OnlineServiceTest {
  private static final String ALICE = "10920508-d5d8-3eed-93d2-92f193afe7d7";
  private static final String BOB = "faa5dca3-c3d4-354b-ae1b-dde9e5a14b3b";
  private static final String CAROL = "3c2c4b6e-1f0a-3d4b-9e8f-112233445566";
  private static final String DAVE = "4d3d5c7f-2a1b-3e5c-8f90-223344556677";

  private final FakeHubRouter hub = new FakeHubRouter();
  private final Map<UUID, HostCoordinator.Decision> decisions = new ConcurrentHashMap<>();
  private final BlockingQueue<String> hostEvents = new LinkedBlockingQueue<>();

  private static NetLoop.Options loopbackOnly() {
    return new NetLoop.Options(List.of(InetAddress.getLoopbackAddress()), null);
  }

  private OnlineService service(BlockingQueue<Object> events, HostCoordinator.Listener host) {
    return new OnlineService(
        OnlineServiceTest::loopbackOnly,
        (peer, name) -> decisions.getOrDefault(peer, HostCoordinator.Decision.ACCEPT),
        host,
        new OnlineService.Events() {
          @Override
          public void onConnected(String uuid, String name) {
            events.add("connected");
          }

          @Override
          public void onDisconnected() {}

          @Override
          public void onDirectory(List<String> joinable) {
            events.add(joinable);
          }

          @Override
          public void onDirectoryUpdate(String host, boolean joinable) {
            events.add("directory:" + host + "=" + joinable);
          }

          @Override
          public void onInvite(String from, String name) {
            events.add("invite:" + name);
          }

          @Override
          public void onNotice(String code) {
            events.add("notice:" + code);
          }
        });
  }

  private final HostCoordinator.Listener echoHost =
      new HostCoordinator.Listener() {
        @Override
        public void onPeerConnected(
            NetLoop loop, PeerSession session, UUID peer, String name, boolean relayed) {
          loop.attach(session, new TransportPair.EchoEndpoint(loop));
          hostEvents.add("connected:" + name);
        }
      };

  private static final HostCoordinator.Listener NO_HOST =
      new HostCoordinator.Listener() {
        @Override
        public void onPeerConnected(
            NetLoop loop, PeerSession session, UUID peer, String name, boolean relayed) {}
      };

  @Test
  @Timeout(60)
  void friendSeesTheWorldGetsInvitedJoinsAndPlays() throws Exception {
    BlockingQueue<Object> aliceEvents = new LinkedBlockingQueue<>();
    BlockingQueue<Object> bobEvents = new LinkedBlockingQueue<>();
    OnlineService alice = service(aliceEvents, echoHost);
    OnlineService bob = service(bobEvents, NO_HOST);
    hub.connect(ALICE, "Alice", alice);
    hub.connect(BOB, "Bob", bob);
    assertEquals("connected", aliceEvents.poll(5, TimeUnit.SECONDS));
    assertEquals("connected", bobEvents.poll(5, TimeUnit.SECONDS));

    alice.openHost(HostMode.FRIENDS, List.of(BOB));
    Thread.sleep(200);
    bob.queryDirectory(List.of(ALICE));
    assertEquals(List.of(ALICE), bobEvents.poll(5, TimeUnit.SECONDS));
    alice.invite(BOB);
    assertEquals("invite:Alice", bobEvents.poll(5, TimeUnit.SECONDS));

    int port = joinAndWait(bob, ALICE).get(20, TimeUnit.SECONDS);
    assertEquals("connected:Bob", hostEvents.poll(5, TimeUnit.SECONDS));
    assertEchoes(port);
  }

  @Test
  @Timeout(60)
  void invitedOnlyWorldLetsInvitedPlayersInAndRefusesBlockedOnes() throws Exception {
    OnlineService alice = service(new LinkedBlockingQueue<>(), echoHost);
    OnlineService carol = service(new LinkedBlockingQueue<>(), NO_HOST);
    OnlineService dave = service(new LinkedBlockingQueue<>(), NO_HOST);
    hub.connect(ALICE, "Alice", alice);
    hub.connect(CAROL, "Carol", carol);
    hub.connect(DAVE, "Dave", dave);
    decisions.put(UUID.fromString(DAVE), HostCoordinator.Decision.BLOCKED);
    alice.openHost(HostMode.INVITED, List.of(CAROL, DAVE));
    Thread.sleep(200);

    CompletableFuture<Integer> carolJoin = joinAndWait(carol, ALICE);
    assertEchoes(carolJoin.get(20, TimeUnit.SECONDS));

    CompletableFuture<JoinAttempt.Failure> daveFailure = new CompletableFuture<>();
    dave.join(ALICE, failureListener(daveFailure));
    assertEquals(JoinAttempt.Failure.BLOCKED, daveFailure.get(10, TimeUnit.SECONDS));

    // A world closed to you looks exactly like an offline one.
    CompletableFuture<JoinAttempt.Failure> strangerFailure = new CompletableFuture<>();
    OnlineService stranger = service(new LinkedBlockingQueue<>(), NO_HOST);
    hub.connect("5e4e6d80-3b2c-3f6d-9a01-334455667788", "Eve", stranger);
    stranger.join(ALICE, failureListener(strangerFailure));
    assertEquals(JoinAttempt.Failure.CLOSED, strangerFailure.get(10, TimeUnit.SECONDS));
  }

  private static CompletableFuture<Integer> joinAndWait(OnlineService service, String host) {
    CompletableFuture<Integer> ready = new CompletableFuture<>();
    service.join(
        host,
        new JoinAttempt.Listener() {
          @Override
          public void onStage(JoinAttempt.Stage stage) {}

          @Override
          public void onReady(int port, boolean relayed) {
            ready.complete(port);
          }

          @Override
          public void onFailed(JoinAttempt.Failure failure) {
            ready.completeExceptionally(new AssertionError("join failed: " + failure));
          }
        });
    return ready;
  }

  private static JoinAttempt.Listener failureListener(CompletableFuture<JoinAttempt.Failure> out) {
    return new JoinAttempt.Listener() {
      @Override
      public void onStage(JoinAttempt.Stage stage) {}

      @Override
      public void onReady(int port, boolean relayed) {
        out.completeExceptionally(new AssertionError("should not connect"));
      }

      @Override
      public void onFailed(JoinAttempt.Failure failure) {
        out.complete(failure);
      }
    };
  }

  private static void assertEchoes(int port) throws Exception {
    try (Socket socket = new Socket(InetAddress.getLoopbackAddress(), port)) {
      byte[] sent = new byte[64 * 1024];
      new Random(port).nextBytes(sent);
      socket.getOutputStream().write(sent);
      byte[] received = new byte[sent.length];
      socket.getInputStream().readNBytes(received, 0, received.length);
      assertArrayEquals(sent, received);
    }
  }
}
