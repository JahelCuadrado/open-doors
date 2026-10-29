package com.einsteinmods.opendoors.client.host;

import com.einsteinmods.opendoors.client.FriendSource;
import com.einsteinmods.opendoors.client.OnlineClient;
import com.einsteinmods.opendoors.client.OpenDoorsClient;
import com.einsteinmods.opendoors.core.access.AccessListsFile;
import com.einsteinmods.opendoors.core.access.AccessPolicy;
import com.einsteinmods.opendoors.core.net.NetLoop;
import com.einsteinmods.opendoors.core.net.PeerSession;
import com.einsteinmods.opendoors.core.online.HostCoordinator;
import com.einsteinmods.opendoors.core.online.OnlineMode;
import com.einsteinmods.opendoors.core.signaling.Validation;
import com.einsteinmods.opendoors.host.PeerAddress;
import com.einsteinmods.opendoors.host.PeerChannel;
import com.einsteinmods.opendoors.host.PeerConnections;
import com.mojang.logging.LogUtils;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.PresenceSharing;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Util;
import net.minecraft.world.level.storage.LevelResource;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

/**
 * The open world's online state ("My world: Closed / Open to friends / Invited only" in the Friends
 * List), independent from LAN, and its access rules ({@link AccessPolicy}): invited and blocked
 * players are saved with the world ({@link AccessListsFile}). Opening sends the hub who may contact
 * this world; join offers and logins are decided by the policy, and accepted friends are handed to
 * the integrated server as {@link PeerChannel}s. Written on the render thread; read from the
 * server, network and control threads through one volatile immutable policy.
 */
public final class FriendsHost implements HostCoordinator.Policy, HostCoordinator.Listener {
  private static final Logger LOGGER = LogUtils.getLogger();
  private static final FriendsHost INSTANCE = new FriendsHost();

  private volatile AccessPolicy policy = AccessPolicy.CLOSED;
  private volatile boolean advertising;
  private volatile @Nullable IntegratedServer server;
  private @Nullable Path worldFolder;

  private FriendsHost() {}

  public static FriendsHost get() {
    return INSTANCE;
  }

  /** Any thread: whether friends can currently join this world online. */
  public static boolean isOpen() {
    return INSTANCE.policy.mode() != OnlineMode.OFF;
  }

  public OnlineMode mode() {
    return policy.mode();
  }

  public AccessPolicy policy() {
    return policy;
  }

  /** Whether the world shows as joinable in friends' lists (presence shared with everyone). */
  public boolean isAdvertising() {
    return advertising && isOpen();
  }

  /**
   * Render thread: the host changed who may join from the Friends List; applies it and tells the
   * player (chat, narrator, window title, presence) as vanilla does when opening to LAN.
   */
  public void changeMode(IntegratedServer integratedServer, OnlineMode wanted) {
    if (wanted == policy.mode()) {
      return;
    }
    setMode(integratedServer, wanted);
    Minecraft minecraft = Minecraft.getInstance();
    Component message =
        wanted == OnlineMode.OFF
            ? Component.translatable("opendoors.online.stopped")
            : Component.translatable("menu.multiplayerOptions.publish.started.online");
    minecraft.gui.hud.getChat().addClientSystemMessage(message);
    minecraft.getNarrator().saySystemQueued(message);
    minecraft.updateTitle();
    minecraft.getPlayerSocialManager().getPresenceHandler().tryUpdatePresence();
  }

  /** Render thread: invites a friend, first opening a closed world to invited players only. */
  public void inviteOpening(IntegratedServer integratedServer, UUID player, String name) {
    if (!isOpen()) {
      changeMode(integratedServer, OnlineMode.INVITED);
    }
    invite(player, name);
  }

  /** Render thread: applies a new mode. Turning it off kicks online friends. */
  public void setMode(IntegratedServer integratedServer, OnlineMode wanted) {
    if (wanted == policy.mode()) {
      return;
    }
    if (wanted == OnlineMode.OFF) {
      policy = policy.withMode(OnlineMode.OFF);
      OpenDoorsClient.online().service().closeHost();
      kickOnlinePlayers(integratedServer, null);
      return;
    }
    if (server != integratedServer) {
      server = integratedServer;
      worldFolder = integratedServer.getWorldPath(LevelResource.ROOT);
      AccessListsFile.Lists lists = AccessListsFile.load(worldFolder);
      policy = new AccessPolicy(OnlineMode.OFF, policy.friends(), lists.invited(), lists.blocked());
    }
    policy = policy.withMode(wanted);
    publish();
  }

  /** The world closed (server thread). */
  public void onServerStopping(IntegratedServer stopping) {
    if (stopping == server) {
      boolean wasOpen = isOpen();
      policy = AccessPolicy.CLOSED;
      server = null;
      if (wasOpen) {
        OpenDoorsClient.online().service().closeHost();
      }
    }
  }

  /**
   * Render thread: re-sends who may join this world (friends list or lists changed): invited
   * players always, other friends only in Friends mode with presence shared. Only they ever learn
   * that the world is open (the hub pushes it to them).
   */
  public void publish() {
    if (!isOpen()) {
      return;
    }
    Minecraft minecraft = Minecraft.getInstance();
    policy = policy.withFriends(FriendSource.friends(minecraft).keySet());
    advertising = minecraft.options.sharePresence().get() == PresenceSharing.ALL;
    List<String> allow = new ArrayList<>();
    for (UUID player : policy.invited().keySet()) {
      allow.add(player.toString());
    }
    if (advertising && policy.mode() == OnlineMode.FRIENDS) {
      // Without shared presence nothing is advertised: only explicit invites reach the world.
      for (UUID friend : policy.friends()) {
        if (!policy.blocked().containsKey(friend)) {
          allow.add(friend.toString());
        }
      }
    }
    if (allow.size() > Validation.MAX_UUID_LIST) {
      allow = allow.subList(0, Validation.MAX_UUID_LIST);
    }
    OpenDoorsClient.online().service().openHost(policy.mode().hostMode(), List.copyOf(allow));
  }

  /** Render thread: invites a friend; they get a toast and may join directly. */
  public void invite(UUID player, String name) {
    if (!isOpen()) {
      return;
    }
    changeLists(policy.invite(player, name));
    OpenDoorsClient.online().service().invite(player.toString());
    OnlineClient.friendToast(
        Minecraft.getInstance(),
        player,
        Component.translatable("gui.friends.toast.friend_invited.message", name));
  }

  public void uninvite(UUID player) {
    changeLists(policy.uninvite(player));
  }

  /** Render thread: blocks a player from this world and kicks them if they are in. */
  public void block(UUID player, String name) {
    changeLists(policy.block(player, name));
    IntegratedServer current = server;
    if (current != null) {
      kickOnlinePlayers(current, player);
    }
  }

  public void unblock(UUID player) {
    changeLists(policy.unblock(player));
  }

  /** Server thread (login): whether this Open Doors login may continue. */
  public @Nullable Component checkLogin(UUID loginProfile, PeerAddress stream) {
    AccessPolicy current = policy;
    if (!loginProfile.equals(stream.profileId())) {
      return Component.translatable("multiplayer.disconnect.unverified_username");
    }
    return switch (current.decide(stream.profileId())) {
      case ALLOW -> null;
      case BLOCKED -> Component.translatable("opendoors.join.failed.blocked");
      case DENY -> Component.translatable("opendoors.disconnect.not_allowed");
    };
  }

  @Override
  public HostCoordinator.Decision decide(UUID peer, String name) {
    return switch (policy.decide(peer)) {
      case ALLOW -> HostCoordinator.Decision.ACCEPT;
      case DENY -> HostCoordinator.Decision.DENY;
      case BLOCKED -> HostCoordinator.Decision.BLOCKED;
    };
  }

  @Override
  public void onPeerConnected(
      NetLoop loop, PeerSession session, UUID peer, String name, boolean relayed) {
    IntegratedServer current = server;
    if (current == null || !isOpen()) {
      loop.closeSession(session);
      return;
    }
    // Display only; the login itself must match the backend-verified UUID (checkLogin).
    PeerChannel channel = new PeerChannel(loop, new PeerAddress(peer, name));
    loop.attach(session, channel.endpoint());
    PeerConnections.accept(current, channel);
  }

  private void changeLists(AccessPolicy updated) {
    policy = updated;
    Path folder = worldFolder;
    if (folder != null) {
      AccessListsFile.Lists lists = new AccessListsFile.Lists(updated.invited(), updated.blocked());
      Util.ioPool()
          .execute(
              () -> {
                try {
                  AccessListsFile.save(folder, lists);
                } catch (IOException exception) {
                  LOGGER.warn("Could not save Open Doors access lists in {}", folder, exception);
                }
              });
    }
    publish();
  }

  /** Kicks online friends: everyone ({@code only} null) or one player. */
  private static void kickOnlinePlayers(IntegratedServer integratedServer, @Nullable UUID only) {
    Component reason =
        Component.translatable(
            only == null
                ? "multiplayer.disconnect.server_shutdown"
                : "multiplayer.disconnect.kicked");
    integratedServer.execute(
        () -> {
          for (ServerPlayer player : List.copyOf(integratedServer.getPlayerList().getPlayers())) {
            boolean online = player.connection.getRemoteAddress() instanceof PeerAddress;
            if (online && (only == null || only.equals(player.getUUID()))) {
              player.connection.disconnect(reason);
            }
          }
        });
  }
}
