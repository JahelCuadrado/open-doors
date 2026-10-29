package com.einsteinmods.opendoors.gametest;

import com.einsteinmods.opendoors.client.OpenDoorsClient;
import com.einsteinmods.opendoors.client.config.OpenDoorsSettingsScreen;
import com.einsteinmods.opendoors.client.host.FriendsHost;
import com.einsteinmods.opendoors.client.screen.HostModeButton;
import com.einsteinmods.opendoors.core.online.OnlineMode;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.events.ContainerEventHandler;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.WorldOptionsScreen;
import net.minecraft.client.gui.screens.friends.FriendsOverlayScreen;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.HttpUtil;

/**
 * The client mixins apply to 26.3 (Friends List host row and rows, Friends button, integrated
 * server, presence), World Options stays vanilla, and hosting online keeps LAN's vanilla meaning:
 * the world counts as published, LAN can still be opened and closed on its own, and closing online
 * play unpublishes again.
 */
public final class OnlineHostingClientGameTest implements FabricClientGameTest {
  private static final String ONLINE_LABEL = "opendoors.online";

  /** Vanilla strings the mod shows; 26.3 strips some unused ones at load (deprecated.json). */
  private static final java.util.List<String> VANILLA_KEYS =
      java.util.List.of(
          "gui.friends.join",
          "gui.friends.toast.friend_invited.message",
          "menu.multiplayerOptions.publish.started.online",
          "multiplayer.disconnect.server_shutdown");

  @Override
  public void runTest(ClientGameTestContext context) {
    if (E2eFriends.active()) {
      // The two-client run plays only its own scenario: hosting here would reach the other client.
      return;
    }
    loadMixinTargets();
    context.runOnClient(
        minecraft -> {
          for (String key : VANILLA_KEYS) {
            check(net.minecraft.locale.Language.getInstance().has(key), "vanilla key " + key);
          }
        });
    // The settings screen (Mod Menu "Configure" / settings key) builds and lays out.
    context.setScreen(
        () -> new OpenDoorsSettingsScreen(new TitleScreen(), OpenDoorsClient.keyMappings()));
    context.waitTick();
    check(
        context.computeOnClient(
            minecraft -> minecraft.gui.screen() instanceof OpenDoorsSettingsScreen),
        "settings screen opens");
    context.setScreen(TitleScreen::new);
    // Outside the player's own world the Friends List has no host row.
    context.setScreen(() -> new FriendsOverlayScreen(new TitleScreen()));
    context.waitTick();
    check(
        !context.computeOnClient(OnlineHostingClientGameTest::hasHostRow), "no host row on menus");
    context.setScreen(TitleScreen::new);
    try (TestSingleplayerContext world = context.worldBuilder().create()) {
      world.getConnection().waitForChunksDownload();

      context.setScreen(() -> new FriendsOverlayScreen(null));
      context.waitTick();
      check(
          context.computeOnClient(OnlineHostingClientGameTest::hasHostRow),
          "host row in own world");
      context.runOnClient(
          minecraft ->
              minecraft.gui.setScreen(
                  new WorldOptionsScreen(minecraft.gui.screen(), minecraft.level)));
      context.waitTick();
      boolean hasOnlineRow =
          context.computeOnClient(
              minecraft ->
                  containsOnlineButton(
                      minecraft.gui.screen(), Component.translatable(ONLINE_LABEL).getString()));
      check(!hasOnlineRow, "World Options stays vanilla (who may join is set in the Friends List)");
      context.setScreen(() -> null);

      world
          .getServer()
          .runOnServer(
              server -> {
                IntegratedServer integrated = (IntegratedServer) server;
                FriendsHost.get().setMode(integrated, OnlineMode.FRIENDS);
                check(integrated.isPublished(), "online hosting publishes the world");
                check(
                    integrated.getMultiplayerScope() == MinecraftServer.MultiplayerScope.OFF,
                    "LAN stays off");
                check(
                    integrated.publishServer(
                        MinecraftServer.MultiplayerScope.LAN, HttpUtil.getAvailablePort()),
                    "LAN can still be opened while online");
                check(integrated.unpublishServer(), "LAN can be closed on its own");
                check(integrated.isPublished(), "closing LAN keeps online hosting");
                FriendsHost.get().setMode(integrated, OnlineMode.OFF);
                check(!integrated.isPublished(), "closing online play unpublishes");
              });
    }
  }

  private static boolean hasHostRow(net.minecraft.client.Minecraft minecraft) {
    return ScreenWidgets.button(minecraft.gui.screen(), HostModeButton.class::isInstance) != null;
  }

  /** Searches nested containers too: the options sit in a scrollable layout. */
  private static boolean containsOnlineButton(GuiEventListener listener, String label) {
    if (listener instanceof CycleButton<?> button
        && button.getMessage().getString().startsWith(label)) {
      return true;
    }
    if (listener instanceof ContainerEventHandler container) {
      for (GuiEventListener child : container.children()) {
        if (containsOnlineButton(child, label)) {
          return true;
        }
      }
    }
    return false;
  }

  /** Loads (and so applies every mixin to) classes no other part of the test touches. */
  private static void loadMixinTargets() {
    try {
      Class.forName("net.minecraft.client.gui.screens.friends.FriendEntry");
      Class.forName("net.minecraft.client.gui.screens.friends.FriendsOverlayScreen");
      Class.forName("net.minecraft.client.gui.components.FriendsButton");
      Class.forName("net.minecraft.client.gui.screens.social.PresenceHandler");
    } catch (ClassNotFoundException exception) {
      throw new AssertionError("mixin target missing", exception);
    }
  }

  private static void check(boolean condition, String what) {
    if (!condition) {
      throw new AssertionError(what);
    }
  }
}
