package com.einsteinmods.opendoors.gametest;

import com.einsteinmods.opendoors.client.host.FriendsHost;
import com.einsteinmods.opendoors.core.access.AccessListsFile;
import com.einsteinmods.opendoors.core.online.OnlineMode;
import com.einsteinmods.opendoors.host.PeerAddress;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.UUID;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.server.players.NameAndId;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.level.storage.LevelResource;

/**
 * Login checks for friends coming through Open Doors ({@code PlayerList#canPlayerLogin} with a
 * {@link PeerAddress}): the login profile must be the stream's authenticated account, blocked and
 * uninvited players are refused, LAN logins are untouched, and the lists are saved with the world.
 */
public final class AccessClientGameTest implements FabricClientGameTest {
  private static final UUID GUEST = UUID.fromString("faa5dca3-c3d4-354b-ae1b-dde9e5a14b3b");
  private static final UUID STRANGER = UUID.fromString("4d3d5c7f-2a1b-3e5c-8f90-223344556677");
  private static final int SAVE_TICKS = 40;

  @Override
  public void runTest(ClientGameTestContext context) {
    if (E2eFriends.active()) {
      // The two-client run plays only its own scenario: hosting here would reach the other client.
      return;
    }
    try (TestSingleplayerContext world = context.worldBuilder().create()) {
      world.getConnection().waitForChunksDownload();
      context.runOnClient(
          minecraft -> {
            FriendsHost.get().setMode(minecraft.getSingleplayerServer(), OnlineMode.INVITED);
            FriendsHost.get().invite(GUEST, "Guest");
          });
      PeerAddress guestStream = new PeerAddress(GUEST, "Guest");
      world
          .getServer()
          .runOnServer(
              server -> {
                PlayerList players = server.getPlayerList();
                expect(players.canPlayerLogin(guestStream, new NameAndId(GUEST, "Guest")), null);
                expect(
                    players.canPlayerLogin(guestStream, new NameAndId(STRANGER, "Guest")),
                    "multiplayer.disconnect.unverified_username");
                expect(
                    players.canPlayerLogin(
                        new PeerAddress(STRANGER, "Stranger"), new NameAndId(STRANGER, "Stranger")),
                    "opendoors.disconnect.not_allowed");
                // A LAN login is vanilla's business.
                expect(
                    players.canPlayerLogin(
                        new InetSocketAddress("192.168.1.20", 50000),
                        new NameAndId(STRANGER, "Stranger")),
                    null);
              });
      context.runOnClient(minecraft -> FriendsHost.get().block(GUEST, "Guest"));
      world
          .getServer()
          .runOnServer(
              server ->
                  expect(
                      server
                          .getPlayerList()
                          .canPlayerLogin(guestStream, new NameAndId(GUEST, "Guest")),
                      "opendoors.join.failed.blocked"));
      context.waitTicks(SAVE_TICKS);
      Path folder =
          world.getServer().computeOnServer(server -> server.getWorldPath(LevelResource.ROOT));
      AccessListsFile.Lists saved = AccessListsFile.load(folder);
      check(saved.blocked().containsKey(GUEST), "blocked player saved with the world");
      check(!saved.invited().containsKey(GUEST), "blocking drops the invite");
      context.runOnClient(
          minecraft ->
              FriendsHost.get().setMode(minecraft.getSingleplayerServer(), OnlineMode.OFF));
    }
  }

  private static void expect(Component refusal, String key) {
    if (key == null) {
      check(
          refusal == null,
          "login should pass, got " + (refusal == null ? "" : refusal.getString()));
      return;
    }
    check(
        refusal != null
            && refusal.getContents() instanceof TranslatableContents contents
            && contents.getKey().equals(key),
        "expected refusal " + key + ", got " + (refusal == null ? "none" : refusal.getString()));
  }

  private static void check(boolean condition, String what) {
    if (!condition) {
      throw new AssertionError(what);
    }
  }
}
