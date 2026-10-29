package com.einsteinmods.opendoors.client;

import com.einsteinmods.opendoors.core.signaling.Validation;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.social.PlayerSocialManager;

/**
 * The player's friends: the vanilla Friends List ({@link PlayerSocialManager#getFriends}). Dev runs
 * with offline identities have no Friends List, so {@code -Dopendoors.devFriends=Alice,Bob} stands
 * in for it there (only together with dev authentication).
 */
public final class FriendSource {
  private static final String DEV_FRIENDS_PROPERTY = "opendoors.devFriends";
  private static final String OFFLINE_PREFIX = "OfflinePlayer:";

  private FriendSource() {}

  /** Friends by UUID, with their names (render or control thread; reads a vanilla snapshot). */
  public static Map<UUID, String> friends(Minecraft minecraft) {
    if (OpenDoorsClient.devAuth()) {
      return devFriends();
    }
    List<PlayerSocialManager.PlayerData> friends = minecraft.getPlayerSocialManager().getFriends();
    Map<UUID, String> byId = new java.util.HashMap<>(friends.size() * 2);
    for (PlayerSocialManager.PlayerData friend : List.copyOf(friends)) {
      byId.put(friend.id(), friend.name());
    }
    return byId;
  }

  /** Friend UUIDs as the hub writes them (dashed lowercase), at most the hub's list limit. */
  public static List<String> friendIds(Minecraft minecraft) {
    List<String> ids = new ArrayList<>();
    for (UUID id : friends(minecraft).keySet()) {
      if (ids.size() == Validation.MAX_UUID_LIST) {
        break;
      }
      ids.add(id.toString());
    }
    return ids;
  }

  private static Map<UUID, String> devFriends() {
    String names = System.getProperty(DEV_FRIENDS_PROPERTY, "");
    Map<UUID, String> byId = new java.util.HashMap<>();
    for (String name : names.split(",")) {
      String trimmed = name.strip();
      if (Validation.isName(trimmed)) {
        byId.put(offlineId(trimmed), trimmed);
      }
    }
    return byId;
  }

  /** Same as vanilla {@code UUIDUtil.createOfflinePlayerUUID}. */
  private static UUID offlineId(String name) {
    return UUID.nameUUIDFromBytes((OFFLINE_PREFIX + name).getBytes(StandardCharsets.UTF_8));
  }
}
