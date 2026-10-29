package com.einsteinmods.opendoors.client.screen;

import com.einsteinmods.opendoors.client.HostingDirectory;
import com.einsteinmods.opendoors.client.OpenDoorsClient;
import com.einsteinmods.opendoors.client.host.FriendsHost;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/**
 * What Open Doors adds to the vanilla Friends List ({@code FriendsOverlayScreen}): the host's row
 * at the top (in their own world only), the Invite action of each friend, and joinable worlds
 * sorted first.
 */
public final class FriendsListHosting {
  /** Space between the host row and the tab content below it. */
  public static final int HOST_ROW_GAP = 4;

  /** Height the host row takes from the lists below it (button plus gap). */
  public static final int HOST_ROW_HEIGHT = 20 + HOST_ROW_GAP;

  private static final int ROW_WIDTH = 220;
  private static final int PLAYERS_WIDTH = 66;
  private static final int GAP = 4;

  /** Whether the Invite button can act, and why not. */
  public enum InviteState {
    AVAILABLE,
    /** Title screen or someone else's server: there is no world of this player's to invite to. */
    NO_WORLD,
    /** In their own world, but the Open Doors server is not reachable. */
    OFFLINE
  }

  private FriendsListHosting() {}

  /**
   * The host row ("My world: …" and "Players…"), or null outside this player's own world (title
   * screen, someone else's server).
   */
  public static @Nullable LinearLayout hostRow(Minecraft minecraft, Screen friendsScreen) {
    IntegratedServer server = minecraft.getSingleplayerServer();
    if (server == null) {
      return null;
    }
    LinearLayout row = LinearLayout.horizontal().spacing(GAP);
    row.addChild(new HostModeButton(ROW_WIDTH - PLAYERS_WIDTH - GAP, server));
    row.addChild(
        Button.builder(
                Component.translatable("opendoors.players.button"),
                pressed -> minecraft.gui.setScreen(new HostPlayersScreen(friendsScreen)))
            .width(PLAYERS_WIDTH)
            .build());
    return row;
  }

  /** Render thread, per frame for each row: no allocation. */
  public static InviteState inviteState(Minecraft minecraft) {
    if (minecraft.getSingleplayerServer() == null) {
      return InviteState.NO_WORLD;
    }
    return OpenDoorsClient.online().canHost() ? InviteState.AVAILABLE : InviteState.OFFLINE;
  }

  /** Invites a friend to this world, opening it to invited players first when it is closed. */
  public static void invite(Minecraft minecraft, UUID friend, String name) {
    IntegratedServer server = minecraft.getSingleplayerServer();
    if (server != null && OpenDoorsClient.online().canHost()) {
      FriendsHost.get().inviteOpening(server, friend, name);
    }
  }

  /** Friends whose world is joinable first, each group keeping vanilla's presence order. */
  public static <T> List<T> joinableFirst(List<T> entries, Function<T, UUID> playerId) {
    HostingDirectory directory = OpenDoorsClient.online().directory();
    List<T> sorted = new ArrayList<>(entries.size());
    List<T> others = new ArrayList<>(entries.size());
    for (T entry : entries) {
      (directory.isJoinable(playerId.apply(entry)) ? sorted : others).add(entry);
    }
    sorted.addAll(others);
    return sorted;
  }
}
