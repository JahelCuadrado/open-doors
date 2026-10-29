package com.einsteinmods.opendoors.core.online;

import com.einsteinmods.opendoors.core.net.ConnectionLog;
import com.einsteinmods.opendoors.core.net.LoopbackProxy;
import com.einsteinmods.opendoors.core.net.NetLoop;
import com.einsteinmods.opendoors.core.net.PeerSession;
import com.einsteinmods.opendoors.core.net.RelayCredentials;
import com.einsteinmods.opendoors.core.signaling.HubLink;
import com.einsteinmods.opendoors.core.signaling.HubMessage.JoinAccepted;
import com.einsteinmods.opendoors.core.signaling.HubMessage.JoinRejected;
import com.einsteinmods.opendoors.core.signaling.HubMessage.JoinRequest;
import com.einsteinmods.opendoors.core.signaling.HubMessage.TurnCredentials;
import com.einsteinmods.opendoors.core.signaling.HubMessage.TurnDenied;
import com.einsteinmods.opendoors.core.signaling.HubMessage.TurnRequest;
import com.einsteinmods.opendoors.core.signaling.IceParameters;
import java.io.IOException;
import java.util.concurrent.Future;

/**
 * One attempt to join a friend's world: get relay credentials (if the monthly relay budget allows),
 * gather candidates, ask the host through the hub, run the connectivity checks, and open the local
 * loopback port the game then connects to. Control-thread state; network-thread events hop back
 * through {@link HubLink#execute}.
 */
public final class JoinAttempt implements PeerSession.Listener {
  /** What the player sees while waiting. */
  public enum Stage {
    WAITING_FOR_HOST,
    CONNECTING_DIRECTLY,
    USING_RELAY
  }

  /** Why the attempt failed. */
  public enum Failure {
    /** The world is closed, offline, or not open to this player. */
    CLOSED,
    DENIED,
    BLOCKED,
    FULL,
    BUSY,
    /** No path worked. */
    UNREACHABLE,
    /** No direct path, and the free monthly relay budget is used up. */
    MONTHLY_LIMIT,
    /** The host did not answer in time. */
    TIMED_OUT,
    /** Not connected to the backend. */
    OFFLINE
  }

  /** Attempt events, on the control thread. */
  public interface Listener {
    void onStage(Stage stage);

    /** The game can connect to 127.0.0.1:{@code port}. */
    void onReady(int port, boolean relayed);

    void onFailed(Failure failure);
  }

  static final long RELAY_REQUEST_TIMEOUT_MILLIS = 3_000;
  static final long HOST_ANSWER_TIMEOUT_MILLIS = 60_000;

  private final String host;
  private final HubLink link;
  private final NetLoop loop;
  private final Listener listener;
  private boolean finished;
  private boolean waitingForRelay = true;
  private boolean monthlyLimit;
  private boolean ready;
  private boolean relayOffered;
  private PeerSession session;
  private Future<?> timeout;

  JoinAttempt(String host, HubLink link, NetLoop loop, Listener listener) {
    this.host = host;
    this.link = link;
    this.loop = loop;
    this.listener = listener;
  }

  public String host() {
    return host;
  }

  /** Control thread. */
  void start() {
    listener.onStage(Stage.WAITING_FOR_HOST);
    link.send(new TurnRequest())
        .thenAccept(
            sent ->
                link.execute(
                    () -> {
                      if (!sent) {
                        fail(Failure.OFFLINE);
                      }
                    }));
    timeout = link.schedule(() -> onRelay(null, false), RELAY_REQUEST_TIMEOUT_MILLIS);
  }

  /** Control thread: the hub's TURN answer. */
  void onTurnCredentials(TurnCredentials credentials) {
    if (waitingForRelay) {
      if (ConnectionLog.on()) {
        ConnectionLog.log("[join] relay credentials received for " + credentials.urls());
      }
      onRelay(RelayResolver.resolve(credentials), false);
    }
  }

  void onTurnDenied(TurnDenied denied) {
    if (waitingForRelay) {
      if (ConnectionLog.on()) {
        ConnectionLog.log(
            "[join] relay denied ("
                + (denied.monthlyLimit() ? "monthly limit" : "unavailable")
                + ")");
      }
      onRelay(null, denied.monthlyLimit());
    }
  }

  void onAccepted(JoinAccepted accepted) {
    if (finished || session == null || !accepted.host().equals(host)) {
      return;
    }
    cancelTimeout();
    if (ConnectionLog.on()) {
      ConnectionLog.log("[join] host answered; checking paths");
    }
    listener.onStage(Stage.CONNECTING_DIRECTLY);
    PeerSession current = session;
    loop.execute(() -> loop.setRemote(current, accepted.ice(), accepted.conv()));
    timeout =
        link.schedule(
            () -> {
              if (!finished && !ready && relayOffered) {
                listener.onStage(Stage.USING_RELAY);
              }
            },
            PeerSession.RELAY_DELAY_MILLIS);
  }

  void onRejected(JoinRejected rejected) {
    if (!rejected.host().equals(host)) {
      return;
    }
    if (ConnectionLog.on()) {
      ConnectionLog.log("[join] refused: " + rejected.reason());
    }
    fail(
        switch (rejected.reason()) {
          case DENIED -> Failure.DENIED;
          case BLOCKED -> Failure.BLOCKED;
          case FULL -> Failure.FULL;
          case BUSY -> Failure.BUSY;
          case CLOSED -> Failure.CLOSED;
        });
  }

  /** Control thread: stops the attempt (the player cancelled or the backend went away). */
  void cancel(Failure failure) {
    fail(failure);
  }

  boolean isFinished() {
    return finished;
  }

  private void onRelay(RelayCredentials relay, boolean limitReached) {
    if (!waitingForRelay || finished) {
      return;
    }
    waitingForRelay = false;
    monthlyLimit = limitReached;
    cancelTimeout();
    relayOffered = relay != null;
    if (ConnectionLog.on() && relay == null) {
      ConnectionLog.log("[join] no relay for this attempt: direct paths only");
    }
    loop.execute(() -> loop.startSession(PeerSession.Role.JOINER, this, relay));
  }

  @Override
  public void onGathered(PeerSession gathered, IceParameters local) {
    link.execute(
        () -> {
          if (finished) {
            loop.execute(() -> loop.closeSession(gathered));
            return;
          }
          session = gathered;
          if (ConnectionLog.on()) {
            ConnectionLog.log(
                "[join] asking host " + host + " with candidates " + local.candidates());
          }
          link.send(new JoinRequest(host, local));
          timeout = link.schedule(() -> fail(Failure.TIMED_OUT), HOST_ANSWER_TIMEOUT_MILLIS);
        });
  }

  @Override
  public void onConnected(PeerSession connected, boolean relayed) {
    LoopbackProxy proxy;
    try {
      proxy = LoopbackProxy.open(loop);
    } catch (IOException exception) {
      loop.closeSession(connected);
      return;
    }
    loop.attach(connected, proxy);
    int port = proxy.port();
    link.execute(
        () -> {
          if (finished) {
            loop.execute(() -> loop.closeSession(connected));
            return;
          }
          ready = true;
          finished = true;
          cancelTimeout();
          listener.onReady(port, relayed);
        });
  }

  @Override
  public void onEnded(PeerSession ended, PeerSession.End end) {
    link.execute(
        () ->
            fail(
                monthlyLimit && end == PeerSession.End.UNREACHABLE
                    ? Failure.MONTHLY_LIMIT
                    : Failure.UNREACHABLE));
  }

  private void fail(Failure failure) {
    if (finished) {
      return;
    }
    finished = true;
    cancelTimeout();
    if (ConnectionLog.on()) {
      ConnectionLog.log("[join] failed: " + failure);
    }
    PeerSession current = session;
    if (current != null) {
      loop.execute(() -> loop.closeSession(current));
    }
    listener.onFailed(failure);
  }

  private void cancelTimeout() {
    if (timeout != null) {
      timeout.cancel(false);
      timeout = null;
    }
  }
}
