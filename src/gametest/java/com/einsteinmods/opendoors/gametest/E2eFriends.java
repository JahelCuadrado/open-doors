package com.einsteinmods.opendoors.gametest;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.client.gui.screens.social.PlayerSocialManager;
import net.minecraft.network.chat.Component;

/**
 * Dev-only stand-in for the vanilla Friends List in the two-client end to end run: offline
 * identities have none, so the names in {@code -Dopendoors.devFriends} (in order) become the
 * friends the Friends screen shows. Active only with {@code -Dopendoors.e2e.role}.
 */
public final class E2eFriends {
  private static final String ROLE_PROPERTY = "opendoors.e2e.role";
  private static final String FRIENDS_PROPERTY = "opendoors.devFriends";
  private static final List<PlayerSocialManager.PlayerData> FRIENDS = parse();

  private E2eFriends() {}

  public static boolean active() {
    return !System.getProperty(ROLE_PROPERTY, "").isEmpty();
  }

  public static List<PlayerSocialManager.PlayerData> friends() {
    return FRIENDS;
  }

  /** Whether {@code text} is one of the stand-in friends' names (a Friends List row title). */
  public static boolean isFriendName(Component text) {
    String name = text.getString();
    for (PlayerSocialManager.PlayerData friend : FRIENDS) {
      if (friend.name().equals(name)) {
        return true;
      }
    }
    return false;
  }

  public static UUID offlineId(String name) {
    return UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8));
  }

  private static List<PlayerSocialManager.PlayerData> parse() {
    List<PlayerSocialManager.PlayerData> friends = new ArrayList<>();
    for (String name : System.getProperty(FRIENDS_PROPERTY, "").split(",")) {
      String trimmed = name.strip();
      if (!trimmed.isEmpty()) {
        friends.add(new PlayerSocialManager.PlayerData(offlineId(trimmed), trimmed));
      }
    }
    return List.copyOf(friends);
  }
}
