package com.einsteinmods.opendoors.core.access;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.einsteinmods.opendoors.core.access.AccessPolicy.Verdict;
import com.einsteinmods.opendoors.core.online.OnlineMode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AccessPolicyTest {
  private static final UUID FRIEND = UUID.fromString("faa5dca3-c3d4-354b-ae1b-dde9e5a14b3b");
  private static final UUID GUEST = UUID.fromString("3c2c4b6e-1f0a-3d4b-9e8f-112233445566");
  private static final UUID STRANGER = UUID.fromString("4d3d5c7f-2a1b-3e5c-8f90-223344556677");

  private final AccessPolicy friendsMode =
      new AccessPolicy(OnlineMode.FRIENDS, Set.of(FRIEND), Map.of(GUEST, "Guest"), Map.of());

  @Test
  void friendsModeLetsFriendsAndInvitedIn() {
    assertEquals(Verdict.ALLOW, friendsMode.decide(FRIEND));
    assertEquals(Verdict.ALLOW, friendsMode.decide(GUEST));
    assertEquals(Verdict.DENY, friendsMode.decide(STRANGER));
  }

  @Test
  void invitedOnlyModeMakesFriendsAsk() {
    AccessPolicy invitedOnly = friendsMode.withMode(OnlineMode.INVITED);
    assertEquals(Verdict.DENY, invitedOnly.decide(FRIEND));
    assertEquals(Verdict.ALLOW, invitedOnly.decide(GUEST));
    assertEquals(Verdict.ALLOW, invitedOnly.invite(FRIEND, "Friend").decide(FRIEND));
  }

  @Test
  void blockingWinsAndDropsTheInvite() {
    AccessPolicy blocked = friendsMode.block(GUEST, "Guest");
    assertEquals(Verdict.BLOCKED, blocked.decide(GUEST));
    assertFalse(blocked.invited().containsKey(GUEST));
    assertEquals(Verdict.BLOCKED, blocked.block(FRIEND, "Friend").decide(FRIEND));
    assertEquals(Verdict.ALLOW, blocked.unblock(GUEST).invite(GUEST, "Guest").decide(GUEST));
  }

  @Test
  void closedWorldsLetNobodyIn() {
    assertEquals(Verdict.DENY, friendsMode.withMode(OnlineMode.OFF).decide(GUEST));
    assertEquals(Verdict.DENY, AccessPolicy.CLOSED.decide(FRIEND));
  }

  @Test
  void loginMustMatchTheAuthenticatedAccount() {
    assertTrue(friendsMode.mayLogIn(FRIEND, FRIEND));
    // Someone using the friend's stream with another name/profile (offline fallback, host name).
    assertFalse(friendsMode.mayLogIn(STRANGER, FRIEND));
    assertFalse(friendsMode.block(FRIEND, "Friend").mayLogIn(FRIEND, FRIEND));
    assertFalse(friendsMode.withMode(OnlineMode.INVITED).mayLogIn(FRIEND, FRIEND));
  }

  @Test
  void listsSurviveSaveAndLoad(@TempDir Path world) throws IOException {
    AccessListsFile.Lists lists =
        new AccessListsFile.Lists(Map.of(GUEST, "Guest"), Map.of(STRANGER, "Stranger"));
    AccessListsFile.save(world, lists);
    assertEquals(lists, AccessListsFile.load(world));
  }

  @Test
  void brokenOrHostileFilesLoadAsEmptyOrFiltered(@TempDir Path world) throws IOException {
    Path file = world.resolve(AccessListsFile.FILE_NAME);
    Files.writeString(file, "{ not json", StandardCharsets.UTF_8);
    assertEquals(AccessListsFile.Lists.EMPTY, AccessListsFile.load(world));
    Files.writeString(
        file,
        "{\"schemaVersion\":1,\"invited\":[{\"id\":\"nope\",\"name\":\"X\"},"
            + "{\"id\":\""
            + GUEST
            + "\",\"name\":\"§cEvil\"},{\"id\":\""
            + FRIEND
            + "\",\"name\":\"Friend\"}],\"blocked\":null}",
        StandardCharsets.UTF_8);
    assertEquals(
        new AccessListsFile.Lists(Map.of(FRIEND, "Friend"), Map.of()), AccessListsFile.load(world));
    assertEquals(AccessListsFile.Lists.EMPTY, AccessListsFile.load(world.resolve("missing")));
  }
}
