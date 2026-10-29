package com.einsteinmods.opendoors.core.access;

import com.einsteinmods.opendoors.core.online.OnlineMode;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Who may join an online world: blocked players never; invited players always while the world is
 * open; other friends only in Friends mode; everyone else never. Immutable; a new policy replaces
 * the old one when anything changes.
 *
 * @param mode the world's online mode
 * @param friends the host's friends
 * @param invited players invited to this world, by UUID with their last name
 * @param blocked players blocked from this world, by UUID with their last name
 */
public record AccessPolicy(
    OnlineMode mode, Set<UUID> friends, Map<UUID, String> invited, Map<UUID, String> blocked) {
  /** The answer for one player. */
  public enum Verdict {
    ALLOW,
    DENY,
    BLOCKED
  }

  public static final AccessPolicy CLOSED =
      new AccessPolicy(OnlineMode.OFF, Set.of(), Map.of(), Map.of());

  public AccessPolicy {
    friends = Set.copyOf(friends);
    invited = Map.copyOf(invited);
    blocked = Map.copyOf(blocked);
  }

  public Verdict decide(UUID player) {
    if (blocked.containsKey(player)) {
      return Verdict.BLOCKED;
    }
    if (mode == OnlineMode.OFF) {
      return Verdict.DENY;
    }
    if (invited.containsKey(player)) {
      return Verdict.ALLOW;
    }
    if (friends.contains(player)) {
      return mode == OnlineMode.FRIENDS ? Verdict.ALLOW : Verdict.DENY;
    }
    return Verdict.DENY;
  }

  /**
   * Login check for a connection that came through Open Doors: the profile the login produced must
   * be the account the backend authenticated for that stream, and that account must still be
   * allowed (it may have been blocked or the world closed meanwhile).
   */
  public boolean mayLogIn(UUID loginProfile, UUID streamAccount) {
    return loginProfile.equals(streamAccount) && decide(streamAccount) == Verdict.ALLOW;
  }

  public AccessPolicy withMode(OnlineMode newMode) {
    return new AccessPolicy(newMode, friends, invited, blocked);
  }

  public AccessPolicy withFriends(Set<UUID> newFriends) {
    return new AccessPolicy(mode, newFriends, invited, blocked);
  }

  /** Invites a player; inviting unblocks. */
  public AccessPolicy invite(UUID player, String name) {
    return new AccessPolicy(mode, friends, with(invited, player, name), without(blocked, player));
  }

  public AccessPolicy uninvite(UUID player) {
    return new AccessPolicy(mode, friends, without(invited, player), blocked);
  }

  /** Blocks a player; blocking also drops their invite. */
  public AccessPolicy block(UUID player, String name) {
    return new AccessPolicy(mode, friends, without(invited, player), with(blocked, player, name));
  }

  public AccessPolicy unblock(UUID player) {
    return new AccessPolicy(mode, friends, invited, without(blocked, player));
  }

  private static Map<UUID, String> with(Map<UUID, String> map, UUID player, String name) {
    java.util.HashMap<UUID, String> copy = new java.util.HashMap<>(map);
    copy.put(player, name);
    return copy;
  }

  private static Map<UUID, String> without(Map<UUID, String> map, UUID player) {
    if (!map.containsKey(player)) {
      return map;
    }
    java.util.HashMap<UUID, String> copy = new java.util.HashMap<>(map);
    copy.remove(player);
    return copy;
  }
}
