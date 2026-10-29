package com.einsteinmods.opendoors.core.signaling;

import java.util.List;

/**
 * Messages exchanged with the signaling hub (backend {@code src/hub.ts}). Every inbound record is
 * built only by {@link HubCodec#decode} after validation, so holders can trust its fields.
 */
public sealed interface HubMessage {
  /** Messages the client sends. */
  sealed interface Outbound extends HubMessage {}

  /** Messages the hub sends. */
  sealed interface Inbound extends HubMessage {}

  /** Opens (or, with {@code update}, changes) hosting; {@code allow} may contact this host. */
  record HostOpen(boolean update, HostMode mode, List<String> allow) implements Outbound {
    public HostOpen {
      allow = List.copyOf(allow);
    }
  }

  record HostClose() implements Outbound {}

  record DirectoryQuery(List<String> uuids) implements Outbound {
    public DirectoryQuery {
      uuids = List.copyOf(uuids);
    }
  }

  record InviteSend(String to) implements Outbound {}

  record JoinRequest(String host, IceParameters ice) implements Outbound {}

  record JoinAnswer(String peer, long conv, IceParameters ice) implements Outbound {}

  record JoinReject(String peer, JoinRejectReason reason) implements Outbound {}

  record TurnRequest() implements Outbound {}

  record Welcome(String uuid, String name) implements Inbound {}

  record DirectoryResult(List<String> joinable) implements Inbound {}

  /** Pushed when {@code host}'s world becomes joinable for this player, or stops being. */
  record DirectoryUpdate(String host, boolean joinable) implements Inbound {}

  record InviteReceived(String from, String name) implements Inbound {}

  /** To the host: a player wants to join; {@code conv} identifies the session. */
  record JoinOffer(String peer, String name, long conv, IceParameters ice) implements Inbound {}

  /** To the joiner: the host accepted. */
  record JoinAccepted(String host, long conv, IceParameters ice) implements Inbound {}

  /** To the joiner: the host (or the hub) refused. */
  record JoinRejected(String host, JoinRejectReason reason) implements Inbound {}

  /** TURN relay credentials; each url is {@code turn:host:port?transport=udp}. */
  record TurnCredentials(List<String> urls, String username, String credential, int ttlSeconds)
      implements Inbound {
    public TurnCredentials {
      urls = List.copyOf(urls);
    }
  }

  /** No relay: {@code monthlyLimit} when the free monthly relay budget is used up. */
  record TurnDenied(boolean monthlyLimit) implements Inbound {}

  record TurnRevoked(boolean monthlyLimit) implements Inbound {}

  /** Hub error; {@code retryAfterSeconds} is 0 when absent. */
  record ErrorMessage(String code, int retryAfterSeconds) implements Inbound {}
}
