package com.einsteinmods.opendoors.core.signaling;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.einsteinmods.opendoors.core.signaling.HubMessage.Inbound;
import com.einsteinmods.opendoors.core.signaling.HubMessage.InviteReceived;
import com.einsteinmods.opendoors.core.signaling.HubMessage.InviteSend;
import com.einsteinmods.opendoors.core.signaling.HubMessage.Welcome;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class SignalingClientTest {
  private static final String BOB = "faa5dca3-c3d4-354b-ae1b-dde9e5a14b3b";

  private final BlockingQueue<Object> events = new LinkedBlockingQueue<>();
  private final BlockingQueue<String> joinedServerIds = new LinkedBlockingQueue<>();
  private static final java.util.UUID PROFILE =
      java.util.UUID.fromString("10920508-d5d8-3eed-93d2-92f193afe7d7");

  /** Records the challenge and answers with a proof signed by a throwaway key. */
  private final SignalingClient.Authenticator prover =
      serverId -> {
        joinedServerIds.add(serverId);
        java.security.KeyPair key =
            java.security.KeyPairGenerator.getInstance("RSA").generateKeyPair();
        return CertificateProof.sign(
            key.getPrivate(), PROFILE, key.getPublic().getEncoded(), 1L, new byte[] {7}, serverId);
      };

  private final SignalingClient.Listener listener =
      new SignalingClient.Listener() {
        @Override
        public void onWelcome(Welcome welcome) {
          events.add(welcome);
        }

        @Override
        public void onMessage(Inbound message) {
          events.add(message);
        }

        @Override
        public void onDisconnected(boolean retrying) {
          events.add(retrying ? "retrying" : "stopped");
        }
      };

  @Test
  void sendsTheCertificateProofThenRelaysMessages() throws Exception {
    try (FakeHub hub = new FakeHub()) {
      SignalingClient client = new SignalingClient(hub.endpoint(), "Alice", prover, listener);
      client.start();
      assertEquals(FakeHub.SERVER_ID, joinedServerIds.poll(5, TimeUnit.SECONDS));
      assertEquals(
          "name=Alice&serverId=" + FakeHub.SERVER_ID, hub.socketQueries.poll(5, TimeUnit.SECONDS));
      java.util.Map<String, String> headers = hub.socketHeaders.poll(5, TimeUnit.SECONDS);
      assertEquals(PROFILE.toString(), headers.get("x-opendoors-profile"));
      assertEquals("Bw==", headers.get("x-opendoors-key-signature"));
      assertTrue(events.poll(5, TimeUnit.SECONDS) instanceof Welcome);
      assertEquals(SignalingClient.State.CONNECTED, client.state());

      assertTrue(client.send(new InviteSend(BOB)).get(5, TimeUnit.SECONDS));
      assertEquals(
          "{\"t\":\"invite.send\",\"to\":\"" + BOB + "\"}", hub.received.poll(5, TimeUnit.SECONDS));

      hub.sendText("{\"t\":\"invite.recv\",\"from\":\"" + BOB + "\",\"name\":\"Bob\"}");
      hub.sendText("{\"t\":\"bogus\"}");
      assertEquals(new InviteReceived(BOB, "Bob"), events.poll(5, TimeUnit.SECONDS));
      client.close();
    }
  }

  @Test
  void devIdentitySkipsTheChallenge() throws Exception {
    try (FakeHub hub = new FakeHub()) {
      SignalingClient client = new SignalingClient(hub.endpoint(), "Bob", null, listener);
      client.start();
      assertEquals("dev=1&name=Bob", hub.socketQueries.poll(5, TimeUnit.SECONDS));
      assertTrue(events.poll(5, TimeUnit.SECONDS) instanceof Welcome);
      assertEquals(0, hub.challengeCount);
      client.close();
    }
  }

  @Test
  void waitsForRetryAfterWhenRateLimited() throws Exception {
    try (FakeHub hub = new FakeHub()) {
      hub.challengeStatus = 429;
      hub.retryAfterSeconds = 2;
      SignalingClient client = new SignalingClient(hub.endpoint(), "Alice", prover, listener);
      long start = System.nanoTime();
      client.start();
      assertEquals("retrying", events.poll(5, TimeUnit.SECONDS));
      hub.challengeStatus = 200;
      assertTrue(events.poll(10, TimeUnit.SECONDS) instanceof Welcome);
      long waitedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
      assertTrue(waitedMillis >= 2_000, "reconnected after " + waitedMillis + " ms");
      assertEquals(2, hub.challengeCount);
      client.close();
    }
  }

  @Test
  void reconnectsAfterADropButStopsWhenReplaced() throws Exception {
    try (FakeHub hub = new FakeHub()) {
      SignalingClient client = new SignalingClient(hub.endpoint(), "Bob", null, listener);
      client.start();
      assertTrue(events.poll(5, TimeUnit.SECONDS) instanceof Welcome);
      hub.closeWith(1001);
      assertEquals("retrying", events.poll(5, TimeUnit.SECONDS));
      assertTrue(events.poll(5, TimeUnit.SECONDS) instanceof Welcome);
      hub.closeWith(4000);
      assertEquals("stopped", events.poll(5, TimeUnit.SECONDS));
      assertEquals(SignalingClient.State.REPLACED, client.state());
      client.close();
    }
  }
}
