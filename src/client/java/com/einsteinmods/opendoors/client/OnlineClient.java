package com.einsteinmods.opendoors.client;

import com.einsteinmods.opendoors.client.host.FriendsHost;
import com.einsteinmods.opendoors.client.screen.JoinProgressScreen;
import com.einsteinmods.opendoors.core.net.NetLoop;
import com.einsteinmods.opendoors.core.net.portmap.PortMappers;
import com.einsteinmods.opendoors.core.online.OnlineService;
import com.einsteinmods.opendoors.core.online.RelayResolver;
import com.einsteinmods.opendoors.core.signaling.BackendEndpoint;
import com.einsteinmods.opendoors.core.signaling.SignalingClient;
import com.einsteinmods.opendoors.core.signaling.Validation;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.FriendToast;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.component.ResolvableProfile;
import org.jspecify.annotations.Nullable;

/**
 * The game's side of online play: starts the signaling connection when the player may play
 * multiplayer and a backend is configured (title screen included), feeds the {@link
 * HostingDirectory} the Friends List and Friends button read, and shows toasts. Hub events arrive
 * on the signaling thread and are handed to the render thread.
 */
public final class OnlineClient implements OnlineService.Events {
  private static final String STUN_HOST = "stun.cloudflare.com";
  private static final int STUN_PORT = 3478;

  /** Cloudflare's STUN service also answers on port 53. */
  private static final int STUN_ALTERNATE_PORT = 53;

  private static final long DIRECTORY_REFRESH_MILLIS = 5_000;
  private static final long TOAST_MILLIS = 10_000;

  /** Vanilla only has OFF and LAN; a third scope means Mojang shipped online hosting itself. */
  private static final boolean NATIVE_ONLINE_HOSTING =
      MinecraftServer.MultiplayerScope.values().length > 2;

  private final Minecraft minecraft;
  private final OnlineService service;
  private @Nullable SignalingClient signaling;
  private boolean started;

  // Render thread.
  private final HostingDirectory directory = new HostingDirectory();
  private long lastDirectoryQuery;
  private Set<UUID> knownFriends = Set.of();

  OnlineClient(Minecraft minecraft) {
    this.minecraft = minecraft;
    this.service =
        new OnlineService(
            OnlineClient::networkOptions,
            PortMappers.Config::defaults,
            FriendsHost.get(),
            FriendsHost.get(),
            this);
  }

  public OnlineService service() {
    return service;
  }

  /** Render thread: connects to the backend when allowed; idempotent. */
  void startIfEligible() {
    if (started || !isEligible()) {
      return;
    }
    BackendEndpoint endpoint = OpenDoorsClient.backend().orElse(null);
    if (endpoint == null) {
      return;
    }
    started = true;
    SignalingClient client =
        new SignalingClient(
            endpoint,
            minecraft.getUser().getName(),
            OpenDoorsClient.devAuth() ? null : new CertificateAuthenticator(minecraft),
            service);
    service.bind(client);
    signaling = client;
    client.start();
    minecraft.getPlayerSocialManager().addFriendListUpdateListener(this::onFriendListUpdate);
  }

  /**
   * Render thread: vanilla notifies on every Friends List change, and on every failed poll; only a
   * changed set of friends re-sends who may join this world and asks whose worlds are open.
   */
  private void onFriendListUpdate() {
    Set<UUID> friends = FriendSource.friends(minecraft).keySet();
    if (friends.equals(knownFriends)) {
      return;
    }
    knownFriends = Set.copyOf(friends);
    FriendsHost.get().publish();
    queryDirectory(false);
  }

  /** Whether this game can host online (the Friends List enables "My world" and Invite). */
  public boolean canHost() {
    return started && !NATIVE_ONLINE_HOSTING && service.isConnected();
  }

  /** Whether online play can work at all for this player. */
  public boolean isEligible() {
    if (minecraft.isDemo() || !minecraft.allowsMultiplayer()) {
      return false;
    }
    return OpenDoorsClient.devAuth() || minecraft.getPlayerSocialManager().isFriendListEnabled();
  }

  /** Render thread: joinable friends' worlds and pending invites. */
  public HostingDirectory directory() {
    return directory;
  }

  /** Render thread, per frame: whether the Friends button shows its dot. */
  public boolean hasFriendNotification() {
    return directory.hasNotification(minecraft, service.isConnected());
  }

  /**
   * Render thread: asks which friends' worlds are open (after connecting, when the Friends List
   * changes, and when it opens; at most once per {@value #DIRECTORY_REFRESH_MILLIS} ms on open).
   * Changes after that are pushed by the hub.
   */
  public void queryDirectory(boolean throttled) {
    long now = System.currentTimeMillis();
    if (!service.isConnected()
        || (throttled && now - lastDirectoryQuery < DIRECTORY_REFRESH_MILLIS)) {
      return;
    }
    lastDirectoryQuery = now;
    List<String> hosts = new ArrayList<>(FriendSource.friendIds(minecraft));
    for (UUID host : directory.invitingHosts()) {
      String id = host.toString();
      if (!hosts.contains(id) && hosts.size() < Validation.MAX_UUID_LIST) {
        hosts.add(id);
      }
    }
    service.queryDirectory(hosts);
  }

  /** Render thread: the "Join last invite" key. */
  public void joinLastInvite(Minecraft game) {
    UUID host = directory.lastInvite();
    if (host != null) {
      join(game, host, directory.inviterName(host));
    }
  }

  /** Render thread: joins a friend's world, asking first when it means leaving this one. */
  public void join(Minecraft game, UUID host, String hostName) {
    directory.joined(host);
    if (game.level == null) {
      game.gui.setScreen(new JoinProgressScreen(host, hostName));
      return;
    }
    game.gui.setScreen(
        new ConfirmScreen(
            confirmed -> {
              if (!confirmed) {
                game.gui.setScreen(null);
                return;
              }
              ClientLevel level = game.level;
              if (level != null) {
                level.disconnect(ClientLevel.DEFAULT_QUIT_MESSAGE);
              }
              game.disconnect(new JoinProgressScreen(host, hostName), false);
            },
            Component.translatable("gui.friends.join"),
            Component.translatable("opendoors.join.leave_world", hostName)));
  }

  /** Render thread: the game left a world; the network thread is not needed for a joiner. */
  void onLeftWorld() {
    service.releaseNetwork();
  }

  @Override
  public void onConnected(String uuid, String name) {
    minecraft.execute(
        () -> {
          FriendsHost.get().publish();
          queryDirectory(false);
        });
  }

  @Override
  public void onDisconnected() {
    // The signaling client reconnects by itself; hosting is re-sent on the next welcome.
  }

  @Override
  public void onDirectory(List<String> uuids) {
    Set<UUID> result = new HashSet<>();
    for (String uuid : uuids) {
      result.add(UUID.fromString(uuid));
    }
    minecraft.execute(() -> directory.replace(result));
  }

  @Override
  public void onDirectoryUpdate(String host, boolean joinable) {
    UUID id = UUID.fromString(host);
    minecraft.execute(() -> directory.update(id, joinable));
  }

  @Override
  public void onInvite(String from, String hubName) {
    UUID host = UUID.fromString(from);
    minecraft.execute(
        () -> {
          // The hub's name is whatever the client claimed: prefer the Friends List's.
          String name = FriendSource.friends(minecraft).getOrDefault(host, hubName);
          directory.invited(host, name);
          // The key only works in a world; on menus the Friends List shows Join and Decline.
          Component message =
              minecraft.level == null
                  ? Component.translatable("opendoors.toast.invite.menu", name)
                  : Component.translatable(
                      "opendoors.toast.invite",
                      name,
                      OpenDoorsClient.keyMappings().joinInvite().getTranslatedKeyMessage());
          friendToast(minecraft, host, message);
        });
  }

  @Override
  public void onNotice(String code) {
    // Rate limits and relay notices only matter to an ongoing join, which reports its own result.
  }

  /** Vanilla friend toast with the player's face. */
  public static void friendToast(Minecraft minecraft, UUID player, Component message) {
    minecraft
        .gui
        .toastManager()
        .addToast(
            new FriendToast(
                minecraft.font, ResolvableProfile.createUnresolved(player), message, TOAST_MILLIS));
  }

  /** Control thread: resolves the STUN server when the network thread opens. */
  private static NetLoop.Options networkOptions() {
    InetSocketAddress stun = RelayResolver.resolveStun(STUN_HOST, STUN_PORT);
    // Same server, another port: a different public port for it reveals a symmetric NAT.
    InetSocketAddress natProbe =
        stun == null ? null : new InetSocketAddress(stun.getAddress(), STUN_ALTERNATE_PORT);
    return new NetLoop.Options(null, stun, natProbe);
  }
}
