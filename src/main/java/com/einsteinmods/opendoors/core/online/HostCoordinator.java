package com.einsteinmods.opendoors.core.online;

import com.einsteinmods.opendoors.core.net.ConnectionLog;
import com.einsteinmods.opendoors.core.net.NetLoop;
import com.einsteinmods.opendoors.core.net.PeerSession;
import com.einsteinmods.opendoors.core.signaling.HostMode;
import com.einsteinmods.opendoors.core.signaling.HubLink;
import com.einsteinmods.opendoors.core.signaling.HubMessage.HostClose;
import com.einsteinmods.opendoors.core.signaling.HubMessage.HostOpen;
import com.einsteinmods.opendoors.core.signaling.HubMessage.InviteSend;
import com.einsteinmods.opendoors.core.signaling.HubMessage.JoinAnswer;
import com.einsteinmods.opendoors.core.signaling.HubMessage.JoinOffer;
import com.einsteinmods.opendoors.core.signaling.HubMessage.JoinReject;
import com.einsteinmods.opendoors.core.signaling.IceParameters;
import com.einsteinmods.opendoors.core.signaling.JoinRejectReason;
import java.util.List;
import java.util.UUID;

/**
 * The host side: keeps the hub's view of this world (open, mode, who may contact it) and turns each
 * join offer into a peer session, after asking the access policy. Control thread only, except the
 * session callbacks, which run on the network thread.
 */
public final class HostCoordinator {
  /** What the access policy says about a join offer. */
  public enum Decision {
    ACCEPT,
    DENY,
    BLOCKED
  }

  /** Decides who may join; called on the control thread. */
  @FunctionalInterface
  public interface Policy {
    Decision decide(UUID peer, String name);
  }

  /** Host events. */
  public interface Listener {
    /**
     * Network thread: a friend's stream is up; attach the game side with {@link NetLoop#attach}
     * before returning.
     */
    void onPeerConnected(
        NetLoop loop, PeerSession session, UUID peer, String name, boolean relayed);
  }

  /**
   * Friends that may connect at the same time (vanilla IntegratedServer.MAX_PLAYERS minus host).
   */
  public static final int MAX_PEERS = 7;

  private final HubLink link;
  private final Policy policy;
  private final Listener listener;
  private int liveSessions;
  private HostOpen state;
  private NetLoop loop;

  HostCoordinator(HubLink link, Policy policy, Listener listener) {
    this.link = link;
    this.policy = policy;
    this.listener = listener;
  }

  boolean isOpen() {
    return state != null;
  }

  int liveSessions() {
    return liveSessions;
  }

  /** Control thread: opens or updates hosting. */
  void open(NetLoop netLoop, HostMode mode, List<String> allow) {
    loop = netLoop;
    state = new HostOpen(state != null, mode, allow);
    link.send(state);
  }

  /** Control thread: stops accepting joins; connected friends stay (vanilla kicks them). */
  void close() {
    if (state != null) {
      state = null;
      link.send(new HostClose());
    }
  }

  /** Control thread: after a reconnect the hub forgot this host. */
  void onWelcome() {
    if (state != null) {
      state = new HostOpen(false, state.mode(), state.allow());
      link.send(state);
    }
  }

  void invite(String player) {
    if (state != null) {
      link.send(new InviteSend(player));
    }
  }

  void onOffer(JoinOffer offer) {
    if (ConnectionLog.on()) {
      ConnectionLog.log(
          "[host] join offer from "
              + offer.peer()
              + " with candidates "
              + offer.ice().candidates());
    }
    if (state == null) {
      link.send(new JoinReject(offer.peer(), JoinRejectReason.CLOSED));
      return;
    }
    if (liveSessions >= MAX_PEERS) {
      link.send(new JoinReject(offer.peer(), JoinRejectReason.FULL));
      return;
    }
    UUID peer = UUID.fromString(offer.peer());
    switch (policy.decide(peer, offer.name())) {
      case ACCEPT -> accept(offer);
      case DENY -> link.send(new JoinReject(offer.peer(), JoinRejectReason.DENIED));
      case BLOCKED -> link.send(new JoinReject(offer.peer(), JoinRejectReason.BLOCKED));
    }
  }

  private void accept(JoinOffer offer) {
    liveSessions++;
    UUID peer = UUID.fromString(offer.peer());
    NetLoop current = loop;
    PeerSession.Listener events =
        new PeerSession.Listener() {
          @Override
          public void onGathered(PeerSession session, IceParameters local) {
            if (ConnectionLog.on()) {
              ConnectionLog.log("[host] answering with candidates " + local.candidates());
            }
            link.send(new JoinAnswer(offer.peer(), offer.conv(), local));
          }

          @Override
          public void onConnected(PeerSession session, boolean relayed) {
            listener.onPeerConnected(current, session, peer, offer.name(), relayed);
          }

          @Override
          public void onEnded(PeerSession session, PeerSession.End end) {
            link.execute(() -> liveSessions--);
          }
        };
    current.execute(
        () -> {
          PeerSession session = current.startSession(PeerSession.Role.HOST, events, null);
          current.setRemote(session, offer.ice(), offer.conv());
        });
  }
}
