package com.einsteinmods.opendoors.core.signaling;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.einsteinmods.opendoors.core.signaling.HubMessage.DirectoryQuery;
import com.einsteinmods.opendoors.core.signaling.HubMessage.DirectoryResult;
import com.einsteinmods.opendoors.core.signaling.HubMessage.DirectoryUpdate;
import com.einsteinmods.opendoors.core.signaling.HubMessage.HostOpen;
import com.einsteinmods.opendoors.core.signaling.HubMessage.Inbound;
import com.einsteinmods.opendoors.core.signaling.HubMessage.InviteReceived;
import com.einsteinmods.opendoors.core.signaling.HubMessage.InviteSend;
import com.einsteinmods.opendoors.core.signaling.HubMessage.JoinAccepted;
import com.einsteinmods.opendoors.core.signaling.HubMessage.JoinAnswer;
import com.einsteinmods.opendoors.core.signaling.HubMessage.JoinOffer;
import com.einsteinmods.opendoors.core.signaling.HubMessage.JoinRequest;
import com.einsteinmods.opendoors.core.signaling.HubMessage.Welcome;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * Dev check against a local {@code wrangler dev} started with DEV_AUTH=1 (run with {@code ./gradlew
 * test -PdevBackendCheck}): two offline identities see the directory push, then exchange an invite
 * and a join.
 */
@EnabledIfSystemProperty(named = "opendoors.devBackend", matches = ".+")
class DevBackendCheckTest {
  private static final IceParameters ICE =
      new IceParameters(
          "abcd",
          "abcdefghijklmnopqrstuvwx",
          List.of(new Candidate(Candidate.Kind.HOST, "127.0.0.1", 50000)));

  @Test
  void twoDevIdentitiesExchangeAnInviteAndAJoin() throws Exception {
    BackendEndpoint endpoint =
        BackendEndpoint.parse(System.getProperty("opendoors.devBackend")).orElseThrow();
    Peer alice = new Peer(endpoint, "Alice");
    Peer bob = new Peer(endpoint, "Bob");
    Welcome aliceWelcome = alice.next(Welcome.class);
    Welcome bobWelcome = bob.next(Welcome.class);

    assertTrue(
        alice.client.send(new HostOpen(false, HostMode.FRIENDS, List.of(bobWelcome.uuid()))).get());
    // Round trip on the host before Bob depends on it: the hub orders messages per socket only.
    alice.client.send(new DirectoryQuery(List.of(aliceWelcome.uuid())));
    alice.next(DirectoryResult.class);

    // Bob is in Alice's allow list: the hub pushes that her world opened to him.
    assertEquals(new DirectoryUpdate(aliceWelcome.uuid(), true), bob.next(DirectoryUpdate.class));
    bob.client.send(new DirectoryQuery(List.of(aliceWelcome.uuid())));
    assertEquals(List.of(aliceWelcome.uuid()), bob.next(DirectoryResult.class).joinable());

    alice.client.send(new InviteSend(bobWelcome.uuid()));
    assertEquals(aliceWelcome.uuid(), bob.next(InviteReceived.class).from());

    bob.client.send(new JoinRequest(aliceWelcome.uuid(), ICE));
    JoinOffer offer = alice.next(JoinOffer.class);
    assertEquals("Bob", offer.name());
    alice.client.send(new JoinAnswer(offer.peer(), offer.conv(), ICE));
    assertEquals(offer.conv(), bob.next(JoinAccepted.class).conv());

    alice.client.close();
    bob.client.close();
  }

  private static final class Peer implements SignalingClient.Listener {
    private final BlockingQueue<Inbound> inbox = new LinkedBlockingQueue<>();
    private final SignalingClient client;

    Peer(BackendEndpoint endpoint, String name) {
      client = new SignalingClient(endpoint, name, null, this);
      client.start();
    }

    <T extends Inbound> T next(Class<T> type) throws InterruptedException {
      Inbound message = inbox.poll(10, TimeUnit.SECONDS);
      return assertInstanceOf(type, message);
    }

    @Override
    public void onWelcome(Welcome welcome) {
      inbox.add(welcome);
    }

    @Override
    public void onMessage(Inbound message) {
      inbox.add(message);
    }

    @Override
    public void onDisconnected(boolean retrying) {}
  }
}
