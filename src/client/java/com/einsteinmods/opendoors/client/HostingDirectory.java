package com.einsteinmods.opendoors.client;

import com.mojang.authlib.services.response.PresenceResponse;
import com.mojang.authlib.services.response.PresenceStatus;
import com.mojang.authlib.services.response.PresenceStatusDto;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.friends.FriendsOverlayScreen;
import org.jspecify.annotations.Nullable;

/**
 * Which friends' worlds this player can join right now, and the invites waiting for an answer, as
 * the hub pushes them ({@code directory.update}, {@code invite.recv}; first filled by {@code
 * directory.result}). Render thread only: every change swaps in new immutable collections, so the
 * per-frame reads (Friends button dot, Friends List rows) are field reads without allocation.
 */
public final class HostingDirectory {
  private Set<UUID> joinable = Set.of();
  private Map<UUID, String> invites = Map.of();
  private @Nullable UUID lastInvite;

  /** Fallback when the hub is unreachable: vanilla presence, checked once per presence update. */
  private @Nullable PresenceResponse checkedPresence;

  private boolean friendHostingByPresence;

  /** Whether this friend's world can be joined (open to this player, or they invited them). */
  public boolean isJoinable(UUID host) {
    return joinable.contains(host) || invites.containsKey(host);
  }

  public boolean hasInvite(UUID host) {
    return invites.containsKey(host);
  }

  /** Hosts with a pending invite, for the directory query after a reconnect. */
  public Set<UUID> invitingHosts() {
    return invites.keySet();
  }

  /** The newest invite still pending, for the "Join last invite" key. */
  public @Nullable UUID lastInvite() {
    return lastInvite != null && invites.containsKey(lastInvite) ? lastInvite : null;
  }

  public String inviterName(UUID host) {
    return invites.getOrDefault(host, "");
  }

  /**
   * Whether the Friends button shows its dot: a joinable world or a pending invite; while the hub
   * is unreachable, a friend hosting according to vanilla presence (that includes LAN worlds).
   */
  public boolean hasNotification(Minecraft minecraft, boolean hubConnected) {
    if (!joinable.isEmpty() || !invites.isEmpty()) {
      return true;
    }
    return !hubConnected && isFriendHostingByPresence(minecraft);
  }

  /** The answer to a directory query: the full set of joinable worlds among {@code queried}. */
  void replace(Set<UUID> result) {
    joinable = Set.copyOf(result);
    Map<UUID, String> kept = new HashMap<>(invites);
    kept.keySet().retainAll(result);
    invites = Map.copyOf(kept);
    onChanged();
  }

  void update(UUID host, boolean open) {
    if (open == joinable.contains(host) && (open || !invites.containsKey(host))) {
      return;
    }
    Set<UUID> next = new HashSet<>(joinable);
    if (open) {
      next.add(host);
    } else {
      next.remove(host);
      invites = without(invites, host);
    }
    joinable = Set.copyOf(next);
    onChanged();
  }

  void invited(UUID host, String name) {
    Map<UUID, String> next = new HashMap<>(invites);
    next.put(host, name);
    invites = Map.copyOf(next);
    lastInvite = host;
    onChanged();
  }

  /** Declining hides that world here until the host opens it again or sends a new invite. */
  public void decline(UUID host) {
    invites = without(invites, host);
    if (joinable.contains(host)) {
      Set<UUID> next = new HashSet<>(joinable);
      next.remove(host);
      joinable = Set.copyOf(next);
    }
    onChanged();
  }

  /** Joining answers the invite. */
  void joined(UUID host) {
    if (invites.containsKey(host)) {
      invites = without(invites, host);
      onChanged();
    }
  }

  private boolean isFriendHostingByPresence(Minecraft minecraft) {
    PresenceResponse presence =
        minecraft.getPlayerSocialManager().getPresenceHandler().getLatestPresence();
    if (presence != checkedPresence) {
      checkedPresence = presence;
      friendHostingByPresence = anyHosting(presence.presence());
    }
    return friendHostingByPresence;
  }

  private static boolean anyHosting(List<PresenceStatusDto> statuses) {
    for (int i = 0; i < statuses.size(); i++) {
      if (statuses.get(i).status() == PresenceStatus.PLAYING_HOSTED_SERVER) {
        return true;
      }
    }
    return false;
  }

  /** Re-sorts an open Friends List: joinable worlds go to the top. */
  private static void onChanged() {
    if (Minecraft.getInstance().gui.screen() instanceof FriendsOverlayScreen friends) {
      friends.refreshLists();
    }
  }

  private static Map<UUID, String> without(Map<UUID, String> map, UUID key) {
    if (!map.containsKey(key)) {
      return map;
    }
    Map<UUID, String> next = new HashMap<>(map);
    next.remove(key);
    return Map.copyOf(next);
  }
}
