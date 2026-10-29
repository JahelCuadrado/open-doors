package com.einsteinmods.opendoors.core.online;

import com.einsteinmods.opendoors.core.net.NetLoop;
import com.einsteinmods.opendoors.core.net.portmap.PortMapService;
import com.einsteinmods.opendoors.core.net.portmap.PortMappers;
import com.einsteinmods.opendoors.core.signaling.HostMode;
import com.einsteinmods.opendoors.core.signaling.HubLink;
import com.einsteinmods.opendoors.core.signaling.HubMessage.DirectoryQuery;
import com.einsteinmods.opendoors.core.signaling.HubMessage.DirectoryResult;
import com.einsteinmods.opendoors.core.signaling.HubMessage.DirectoryUpdate;
import com.einsteinmods.opendoors.core.signaling.HubMessage.ErrorMessage;
import com.einsteinmods.opendoors.core.signaling.HubMessage.Inbound;
import com.einsteinmods.opendoors.core.signaling.HubMessage.InviteReceived;
import com.einsteinmods.opendoors.core.signaling.HubMessage.JoinAccepted;
import com.einsteinmods.opendoors.core.signaling.HubMessage.JoinOffer;
import com.einsteinmods.opendoors.core.signaling.HubMessage.JoinRejected;
import com.einsteinmods.opendoors.core.signaling.HubMessage.TurnCredentials;
import com.einsteinmods.opendoors.core.signaling.HubMessage.TurnDenied;
import com.einsteinmods.opendoors.core.signaling.HubMessage.TurnRevoked;
import com.einsteinmods.opendoors.core.signaling.HubMessage.Welcome;
import com.einsteinmods.opendoors.core.signaling.SignalingClient;
import java.io.IOException;
import java.util.List;
import java.util.function.Supplier;

/**
 * Everything online in one place: routes hub messages to the host coordinator, the current join
 * attempt or the UI, and owns the network thread, opened on first use and closed once nothing needs
 * it. All state lives on the hub link's control thread; public methods only queue work there.
 */
public final class OnlineService implements SignalingClient.Listener {
  /** UI-facing events, on the control thread. */
  public interface Events {
    void onConnected(String uuid, String name);

    void onDisconnected();

    void onDirectory(List<String> joinable);

    /** Pushed by the hub: {@code host}'s world opened to this player, or closed. */
    void onDirectoryUpdate(String host, boolean joinable);

    void onInvite(String from, String name);

    /** Relay revoked or a hub error (rate limited, not allowed...). */
    void onNotice(String code);
  }

  private final Supplier<NetLoop.Options> networkOptions;
  private final Events events;
  private final HostCoordinator.Policy policy;
  private final HostCoordinator.Listener hostListener;
  private volatile HubLink link;
  private HostCoordinator host;
  private JoinAttempt join;
  private NetLoop loop;
  private volatile boolean connected;
  private final PortMapService portMap;

  public OnlineService(
      Supplier<NetLoop.Options> networkOptions,
      HostCoordinator.Policy policy,
      HostCoordinator.Listener hostListener,
      Events events) {
    this(networkOptions, null, policy, hostListener, events);
  }

  /**
   * @param portMapping router port mapping settings for hosting, or null to never map
   */
  public OnlineService(
      Supplier<NetLoop.Options> networkOptions,
      Supplier<PortMappers.Config> portMapping,
      HostCoordinator.Policy policy,
      HostCoordinator.Listener hostListener,
      Events events) {
    this.networkOptions = networkOptions;
    this.portMap = new PortMapService(portMapping);
    this.policy = policy;
    this.hostListener = hostListener;
    this.events = events;
  }

  /** Connects the service to its hub link (once, before anything else). */
  public void bind(HubLink hubLink) {
    link = hubLink;
    host = new HostCoordinator(link, policy, hostListener);
  }

  public boolean isConnected() {
    return connected;
  }

  /** Opens or updates hosting; {@code allow} are the players that may contact this world. */
  public void openHost(HostMode mode, List<String> allow) {
    onControlThread(
        () -> {
          NetLoop network = network();
          if (network != null) {
            host.open(network, mode, allow);
            portMap.start(network);
          }
        });
  }

  /** Stops accepting joins; friends already playing stay connected. */
  public void closeHost() {
    onControlThread(
        () -> {
          host.close();
          portMap.stop();
        });
  }

  public void invite(String player) {
    onControlThread(() -> host.invite(player));
  }

  public void queryDirectory(List<String> players) {
    onControlThread(
        () -> {
          if (connected) {
            link.send(new DirectoryQuery(players));
          }
        });
  }

  /** Starts joining {@code hostUuid}; any previous attempt is cancelled. */
  public void join(String hostUuid, JoinAttempt.Listener listener) {
    if (link == null) {
      listener.onFailed(JoinAttempt.Failure.OFFLINE);
      return;
    }
    onControlThread(
        () -> {
          if (join != null && !join.isFinished()) {
            join.cancel(JoinAttempt.Failure.CLOSED);
          }
          NetLoop network = connected ? network() : null;
          if (network == null) {
            listener.onFailed(JoinAttempt.Failure.OFFLINE);
            return;
          }
          join = new JoinAttempt(hostUuid, link, network, listener);
          join.start();
        });
  }

  public void cancelJoin() {
    onControlThread(
        () -> {
          if (join != null) {
            join.cancel(JoinAttempt.Failure.CLOSED);
          }
        });
  }

  /** Closes every stream and the network thread (leaving a world). Hosting must be closed first. */
  public void releaseNetwork() {
    onControlThread(
        () -> {
          if (loop != null && !host.isOpen() && (join == null || join.isFinished())) {
            portMap.stop();
            loop.close();
            loop = null;
          }
        });
  }

  @Override
  public void onWelcome(Welcome welcome) {
    connected = true;
    host.onWelcome();
    events.onConnected(welcome.uuid(), welcome.name());
  }

  @Override
  public void onMessage(Inbound message) {
    switch (message) {
      case DirectoryResult result -> events.onDirectory(result.joinable());
      case DirectoryUpdate update -> events.onDirectoryUpdate(update.host(), update.joinable());
      case InviteReceived invite -> events.onInvite(invite.from(), invite.name());
      case JoinOffer offer -> host.onOffer(offer);
      case JoinAccepted accepted -> {
        if (join != null) {
          join.onAccepted(accepted);
        }
      }
      case JoinRejected rejected -> {
        if (join != null) {
          join.onRejected(rejected);
        }
      }
      case TurnCredentials credentials -> {
        if (join != null) {
          join.onTurnCredentials(credentials);
        }
      }
      case TurnDenied denied -> {
        if (join != null) {
          join.onTurnDenied(denied);
        }
      }
      case TurnRevoked _ -> events.onNotice("turn_revoked");
      case ErrorMessage error -> events.onNotice(error.code());
      case Welcome welcome -> onWelcome(welcome);
    }
  }

  @Override
  public void onDisconnected(boolean retrying) {
    connected = false;
    if (join != null && !join.isFinished()) {
      join.cancel(JoinAttempt.Failure.OFFLINE);
    }
    events.onDisconnected();
  }

  /** Queues {@code task} on the control thread; nothing happens before {@link #bind}. */
  private void onControlThread(Runnable task) {
    HubLink current = link;
    if (current != null) {
      current.execute(task);
    }
  }

  private NetLoop network() {
    if (loop == null) {
      try {
        loop = NetLoop.open(networkOptions.get());
      } catch (IOException exception) {
        events.onNotice("network_unavailable");
        return null;
      }
    }
    return loop;
  }
}
