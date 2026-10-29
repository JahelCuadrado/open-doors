package com.einsteinmods.opendoors.core.online;

import com.einsteinmods.opendoors.core.signaling.HubLink;
import com.einsteinmods.opendoors.core.signaling.HubMessage;
import com.einsteinmods.opendoors.core.signaling.HubMessage.DirectoryQuery;
import com.einsteinmods.opendoors.core.signaling.HubMessage.DirectoryResult;
import com.einsteinmods.opendoors.core.signaling.HubMessage.HostClose;
import com.einsteinmods.opendoors.core.signaling.HubMessage.HostOpen;
import com.einsteinmods.opendoors.core.signaling.HubMessage.Inbound;
import com.einsteinmods.opendoors.core.signaling.HubMessage.InviteReceived;
import com.einsteinmods.opendoors.core.signaling.HubMessage.InviteSend;
import com.einsteinmods.opendoors.core.signaling.HubMessage.JoinAccepted;
import com.einsteinmods.opendoors.core.signaling.HubMessage.JoinAnswer;
import com.einsteinmods.opendoors.core.signaling.HubMessage.JoinOffer;
import com.einsteinmods.opendoors.core.signaling.HubMessage.JoinReject;
import com.einsteinmods.opendoors.core.signaling.HubMessage.JoinRejected;
import com.einsteinmods.opendoors.core.signaling.HubMessage.JoinRequest;
import com.einsteinmods.opendoors.core.signaling.HubMessage.TurnDenied;
import com.einsteinmods.opendoors.core.signaling.HubMessage.TurnRequest;
import com.einsteinmods.opendoors.core.signaling.HubMessage.Welcome;
import com.einsteinmods.opendoors.core.signaling.JoinRejectReason;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** In-memory stand-in for the hub (same routing and privacy rules as backend/src/hub.ts). */
final class FakeHubRouter {
  private final Map<String, Client> clients = new HashMap<>();
  private final Map<String, HostOpen> hosts = new HashMap<>();
  private final Map<String, Set<String>> invited = new HashMap<>();
  private long nextConv = 0x0100_0080L;

  /** One connected player: its control thread and its service. */
  final class Client implements HubLink {
    final String uuid;
    final String name;
    final OnlineService service;
    final ScheduledExecutorService thread =
        Executors.newSingleThreadScheduledExecutor(
            runnable -> {
              Thread created = new Thread(runnable, "FakeHub control");
              created.setDaemon(true);
              return created;
            });

    Client(String uuid, String name, OnlineService service) {
      this.uuid = uuid;
      this.name = name;
      this.service = service;
    }

    @Override
    public CompletableFuture<Boolean> send(HubMessage.Outbound message) {
      route(this, message);
      return CompletableFuture.completedFuture(true);
    }

    @Override
    public void execute(Runnable task) {
      thread.execute(task);
    }

    @Override
    public Future<?> schedule(Runnable task, long delayMillis) {
      return thread.schedule(task, delayMillis, TimeUnit.MILLISECONDS);
    }

    void deliver(Inbound message) {
      thread.execute(() -> service.onMessage(message));
    }
  }

  synchronized Client connect(String uuid, String name, OnlineService service) {
    Client client = new Client(uuid, name, service);
    clients.put(uuid, client);
    service.bind(client);
    client.thread.execute(() -> service.onWelcome(new Welcome(uuid, name)));
    return client;
  }

  private synchronized void route(Client from, HubMessage.Outbound message) {
    switch (message) {
      case HostOpen open -> hosts.put(from.uuid, open);
      case HostClose _ -> {
        hosts.remove(from.uuid);
        invited.remove(from.uuid);
      }
      case DirectoryQuery query ->
          from.deliver(
              new DirectoryResult(
                  query.uuids().stream().filter(host -> mayContact(host, from.uuid)).toList()));
      case InviteSend invite -> {
        if (hosts.containsKey(from.uuid) && clients.containsKey(invite.to())) {
          invited.computeIfAbsent(from.uuid, ignored -> new HashSet<>()).add(invite.to());
          clients.get(invite.to()).deliver(new InviteReceived(from.uuid, from.name));
        }
      }
      case JoinRequest request -> {
        if (!mayContact(request.host(), from.uuid)) {
          from.deliver(new JoinRejected(request.host(), JoinRejectReason.CLOSED));
          return;
        }
        long conv = nextConv;
        nextConv += 0x100;
        clients
            .get(request.host())
            .deliver(new JoinOffer(from.uuid, from.name, conv, request.ice()));
      }
      case JoinAnswer answer ->
          clients
              .get(answer.peer())
              .deliver(new JoinAccepted(from.uuid, answer.conv(), answer.ice()));
      case JoinReject reject ->
          clients.get(reject.peer()).deliver(new JoinRejected(from.uuid, reject.reason()));
      case TurnRequest _ -> from.deliver(new TurnDenied(false));
    }
  }

  private boolean mayContact(String host, String player) {
    HostOpen open = hosts.get(host);
    if (open == null || !clients.containsKey(host)) {
      return false;
    }
    boolean listed = open.allow().contains(player);
    boolean wasInvited = invited.getOrDefault(host, Set.of()).contains(player);
    return listed || wasInvited;
  }

  static List<String> list(String... values) {
    return List.of(values);
  }
}
