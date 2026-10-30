package com.einsteinmods.opendoors.gametest;

import com.einsteinmods.opendoors.client.OpenDoorsClient;
import com.einsteinmods.opendoors.client.host.FriendsHost;
import com.einsteinmods.opendoors.client.screen.HostModeButton;
import com.einsteinmods.opendoors.client.screen.HostPlayersScreen;
import com.einsteinmods.opendoors.client.screen.JoinProgressScreen;
import com.einsteinmods.opendoors.core.online.OnlineMode;
import com.einsteinmods.opendoors.host.PeerChannel;
import com.einsteinmods.opendoors.host.PeerConnections;
import com.mojang.blaze3d.platform.InputConstants;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.friends.FriendsOverlayScreen;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.HttpUtil;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Dev-only end to end run with two real clients and a local {@code wrangler dev} backend
 * (DEV_AUTH=1): {@code ./gradlew runE2eHost & ./gradlew runE2eJoiner} launch this test with {@code
 * -Dopendoors.e2e.role=host} (Alice) and {@code joiner} (Bob). Without the property (normal client
 * GameTests) it does nothing. Everything goes through the Friends List, as a player would.
 *
 * <ol>
 *   <li>Alice opens her world to friends with "My world". Bob, on the title screen, gets the green
 *       dot on the Friends button without asking; in his Friends List Alice comes first with a dot
 *       and "Join", which takes him in over a peer channel with vanilla encryption.
 *   <li>From "Players…" Alice kicks him (he joins again), then blocks him (which kicks him too) and
 *       closes her world; Bob's dot goes.
 *   <li>With her world closed, Alice presses "Invite" on Bob's row: the world opens invited-only
 *       and Bob, on the title screen, gets the invite (toast, dot, "Join" and "Decline") and joins.
 *   <li>Alice opens and closes LAN: Bob stays. Alice closes her world: Bob is kicked, his dot goes.
 *   <li>Alice reopens invited-only without inviting Bob: nothing shows for him. She invites him
 *       again: on his title screen "Invite" is greyed out (no world of his own), and "Decline"
 *       takes Alice's world off his list.
 * </ol>
 *
 * <p>The two clients wait for each other's later steps through marker files in {@code
 * build/run/e2e-sync}.
 *
 * <p>Screenshots of each step are saved in the run directory's {@code screenshots} folder.
 */
public final class OnlineEndToEndClientGameTest implements FabricClientGameTest {
  private static final Logger LOGGER = LoggerFactory.getLogger("opendoors-e2e");
  private static final String ROLE_PROPERTY = "opendoors.e2e.role";
  private static final String HOST_NAME = "Alice";
  private static final String JOINER_NAME = "Bob";
  private static final String JOIN_KEY = "gui.friends.join";
  private static final String DECLINE_KEY = "opendoors.friends.decline";
  private static final String INVITE_KEY = "opendoors.friends.invite";
  private static final int CONNECT_TICKS = 20 * 60;
  private static final int JOIN_TICKS = 20 * 180;
  private static final int SETTLE_TICKS = 20 * 5;
  private static final int RETRY_TICKS = 20 * 10;
  private static final int LINGER_TICKS = 20 * 20;
  private static final int UNINVITED_WATCH_TICKS = 20 * 15;
  private static final Path SYNC_DIRECTORY = Path.of("..", "e2e-sync");
  private static final String INVITED_ONLY_OPEN = "invited-only-open";
  private static final String UNINVITED_CHECKED = "uninvited-checked";
  private static final String DECLINED = "declined";

  @Override
  public void runTest(ClientGameTestContext context) {
    String role = System.getProperty(ROLE_PROPERTY, "");
    if (role.equals("host")) {
      host(context);
    } else if (role.equals("joiner")) {
      joiner(context);
    }
  }

  private static void host(ClientGameTestContext context) {
    UUID bob = E2eFriends.offlineId(JOINER_NAME);
    clearSignals();
    try (TestSingleplayerContext world = context.worldBuilder().create()) {
      world.getConnection().waitForChunksDownload();
      context.waitFor(minecraft -> OpenDoorsClient.online().canHost(), CONNECT_TICKS);

      // 1. "My world: Closed" -> "Open to friends", from the Friends List.
      openFriends(context, false);
      context.runOnClient(OnlineEndToEndClientGameTest::pressHostMode);
      check(FriendsHost.get().mode() == OnlineMode.FRIENDS, "My world opens to friends");
      context.waitTicks(2);
      screenshot(context, "host-friends-screen");
      LOGGER.info("E2E host: world open to friends from the Friends List");
      context.waitFor(minecraft -> joinerOnline(minecraft.getSingleplayerServer()), JOIN_TICKS);
      PeerChannel channel = PeerConnections.live().get(0);
      check(channel.isEncrypted(), "the friend's stream must be encrypted by vanilla login");
      check(channel.peer().profileId().equals(bob), "the channel carries the verified identity");
      LOGGER.info("E2E host: Bob joined through the Friends List");

      // 2. Players… (from the Friends List): Kick (Bob comes back), then Block; then close.
      context.waitTicks(SETTLE_TICKS);
      openPlayers(context);
      screenshot(context, "host-players-screen");
      context.runOnClient(
          minecraft ->
              check(
                  ScreenWidgets.press(minecraft.gui.screen(), "opendoors.players.kick"),
                  "Kick Bob"));
      context.waitFor(minecraft -> !joinerOnline(minecraft.getSingleplayerServer()), CONNECT_TICKS);
      LOGGER.info("E2E host: Bob kicked from Players…");
      context.waitFor(minecraft -> joinerOnline(minecraft.getSingleplayerServer()), JOIN_TICKS);
      LOGGER.info("E2E host: Bob back after the kick");
      openPlayers(context);
      context.runOnClient(
          minecraft ->
              check(
                  ScreenWidgets.press(minecraft.gui.screen(), "opendoors.players.block"),
                  "Block Bob"));
      context.waitFor(minecraft -> !joinerOnline(minecraft.getSingleplayerServer()), CONNECT_TICKS);
      LOGGER.info("E2E host: Bob blocked and kicked from Players…");
      check(FriendsHost.get().policy().blocked().containsKey(bob), "Bob in the blocked list");
      openFriends(context, false);
      context.runOnClient(
          minecraft ->
              check(
                  ScreenWidgets.press(minecraft.gui.screen(), "opendoors.players.button"),
                  "Players… in the Friends List"));
      context.waitTicks(2);
      screenshot(context, "host-players-blocked");
      context.runOnClient(
          minecraft ->
              check(
                  ScreenWidgets.press(minecraft.gui.screen(), "opendoors.players.unblock"),
                  "Unblock Bob"));
      check(!FriendsHost.get().policy().blocked().containsKey(bob), "Unblock clears the block");
      LOGGER.info("E2E host: Bob unblocked from Players…");
      openFriends(context, false);
      for (int presses = 0; FriendsHost.get().mode() != OnlineMode.OFF; presses++) {
        check(presses < OnlineMode.values().length, "My world cycles back to Closed");
        context.runOnClient(OnlineEndToEndClientGameTest::pressHostMode);
      }
      LOGGER.info("E2E host: world closed");

      // 3. Invite while closed: the world opens invited-only and the invite goes out.
      context.waitTicks(RETRY_TICKS);
      openFriends(context, false);
      context.runOnClient(
          minecraft ->
              check(
                  pressInRow(minecraft.gui.screen(), JOINER_NAME, INVITE_KEY),
                  "Invite on Bob's row"));
      check(FriendsHost.get().mode() == OnlineMode.INVITED, "Invite opens the world invited-only");
      context.waitTicks(2);
      String label =
          context.computeOnClient(
              minecraft ->
                  ScreenWidgets.button(minecraft.gui.screen(), HostModeButton.class::isInstance)
                      .getMessage()
                      .getString());
      check(
          label.contains(Component.translatable("opendoors.online.invited").getString()),
          "My world shows Invited only: " + label);
      screenshot(context, "host-invited-while-closed");
      LOGGER.info("E2E host: invited Bob with the world closed; it opened invited-only");
      context.waitFor(minecraft -> joinerOnline(minecraft.getSingleplayerServer()), JOIN_TICKS);
      LOGGER.info("E2E host: Bob joined from the invite");

      // 4. LAN on its own: opening and closing it keeps Bob, who came through Open Doors.
      context.waitTicks(SETTLE_TICKS);
      world
          .getServer()
          .runOnServer(
              server -> {
                IntegratedServer integrated = (IntegratedServer) server;
                check(
                    integrated.publishServer(
                        MinecraftServer.MultiplayerScope.LAN, HttpUtil.getAvailablePort()),
                    "LAN opens while Bob plays");
                check(integrated.unpublishServer(), "LAN closes on its own");
              });
      context.waitTicks(SETTLE_TICKS);
      check(
          context.computeOnClient(minecraft -> joinerOnline(minecraft.getSingleplayerServer())),
          "closing LAN keeps Bob in the world");
      LOGGER.info("E2E host: LAN opened and closed, Bob still in");

      // 5. Closing the world to friends kicks Bob.
      context.runOnClient(
          minecraft ->
              FriendsHost.get().changeMode(minecraft.getSingleplayerServer(), OnlineMode.OFF));
      context.waitFor(minecraft -> !joinerOnline(minecraft.getSingleplayerServer()), CONNECT_TICKS);
      LOGGER.info("E2E host: world closed, Bob kicked");

      // 6. Invited only, Bob no longer invited: the world must not reach him.
      context.waitTicks(RETRY_TICKS);
      context.runOnClient(
          minecraft -> {
            FriendsHost.get().uninvite(bob);
            FriendsHost.get().changeMode(minecraft.getSingleplayerServer(), OnlineMode.INVITED);
          });
      check(!FriendsHost.get().policy().invited().containsKey(bob), "Bob not invited");
      signal(INVITED_ONLY_OPEN);
      LOGGER.info("E2E host: invited only, without Bob");
      awaitSignal(context, UNINVITED_CHECKED);

      // 7. Invite again from Bob's row; Bob declines.
      openFriends(context, false);
      context.runOnClient(
          minecraft ->
              check(
                  pressInRow(minecraft.gui.screen(), JOINER_NAME, INVITE_KEY),
                  "Invite on Bob's row again"));
      LOGGER.info("E2E host: invited Bob again");
      awaitSignal(context, DECLINED);
      LOGGER.info("E2E host: Bob declined; PASS");
      context.runOnClient(
          minecraft ->
              FriendsHost.get().changeMode(minecraft.getSingleplayerServer(), OnlineMode.OFF));
    }
  }

  /** Friends List -> Players…, waiting until Bob's row (with Block) is listed. */
  private static void openPlayers(ClientGameTestContext context) {
    openFriends(context, false);
    context.runOnClient(
        minecraft ->
            check(
                ScreenWidgets.press(minecraft.gui.screen(), "opendoors.players.button"),
                "Players… in the Friends List"));
    context.waitFor(
        minecraft ->
            minecraft.gui.screen() instanceof HostPlayersScreen
                && ScreenWidgets.button(minecraft.gui.screen(), "opendoors.players.block") != null,
        CONNECT_TICKS);
  }

  private static void joiner(ClientGameTestContext context) {
    UUID alice = E2eFriends.offlineId(HOST_NAME);
    context.waitFor(minecraft -> OpenDoorsClient.online().service().isConnected(), CONNECT_TICKS);

    // 1. Alice's world opens to friends: dot on the title screen, Alice first with Join.
    context.setScreen(TitleScreen::new);
    context.waitFor(
        minecraft -> OpenDoorsClient.online().directory().isJoinable(alice), JOIN_TICKS);
    check(
        context.computeOnClient(minecraft -> OpenDoorsClient.online().hasFriendNotification()),
        "Friends button dot");
    context.waitTicks(2);
    screenshot(context, "guest-title-dot");
    openFriends(context, true);
    List<String> names =
        context.computeOnClient(
            minecraft ->
                ScreenWidgets.texts(minecraft.gui.screen()).stream()
                    .filter(
                        text -> E2eFriends.friends().stream().anyMatch(f -> f.name().equals(text)))
                    .toList());
    check(!names.isEmpty() && names.get(0).equals(HOST_NAME), "Alice listed first: " + names);
    screenshot(context, "guest-friends-open-world");
    LOGGER.info("E2E joiner: Alice's world pushed as joinable; listed first {}", names);
    joinFromFriends(context, alice);
    LOGGER.info("E2E joiner: in Alice's world");

    // 2. Kicked: the world is still open to him, so he joins again; then blocked (kicked too),
    // after which the world no longer shows as joinable.
    context.waitFor(minecraft -> minecraft.gui.screen() instanceof DisconnectedScreen, JOIN_TICKS);
    context.setScreen(TitleScreen::new);
    openFriends(context, true);
    joinFromFriends(context, alice);
    LOGGER.info("E2E joiner: kicked, joined again");
    context.waitFor(minecraft -> minecraft.gui.screen() instanceof DisconnectedScreen, JOIN_TICKS);
    context.setScreen(TitleScreen::new);
    context.waitFor(
        minecraft -> !OpenDoorsClient.online().directory().isJoinable(alice), CONNECT_TICKS);
    LOGGER.info("E2E joiner: blocked, Alice's world gone from the list");

    // 3. The invite arrives on the title screen: toast, dot, Join and Decline.
    context.waitFor(minecraft -> OpenDoorsClient.online().directory().hasInvite(alice), JOIN_TICKS);
    check(
        context.computeOnClient(minecraft -> OpenDoorsClient.online().hasFriendNotification()),
        "Friends button dot for the invite");
    context.waitTicks(SETTLE_TICKS / 5);
    screenshot(context, "guest-title-invite");
    openFriends(context, true);
    check(
        context.computeOnClient(
            minecraft -> rowButton(minecraft.gui.screen(), HOST_NAME, DECLINE_KEY) != null),
        "Decline on Alice's row");
    screenshot(context, "guest-friends-invite");
    joinFromFriends(context, alice);
    LOGGER.info("E2E joiner: joined from the invite");

    // 4-5. Alice opens and closes LAN (Bob stays), then closes her world: kicked, the dot goes.
    context.waitFor(minecraft -> minecraft.level == null, JOIN_TICKS);
    context.setScreen(TitleScreen::new);
    context.waitFor(
        minecraft ->
            !OpenDoorsClient.online().directory().isJoinable(alice)
                && !OpenDoorsClient.online().hasFriendNotification(),
        CONNECT_TICKS);
    screenshot(context, "guest-title-closed");
    LOGGER.info("E2E joiner: Alice closed her world, the dot went");

    // 6. Invited only without an invite for Bob: nothing reaches him.
    awaitSignal(context, INVITED_ONLY_OPEN);
    for (int tick = 0; tick < UNINVITED_WATCH_TICKS; tick++) {
      check(
          context.computeOnClient(
              minecraft ->
                  !OpenDoorsClient.online().directory().isJoinable(alice)
                      && !OpenDoorsClient.online().directory().hasInvite(alice)),
          "an invited-only world must not reach an uninvited friend");
      context.waitTick();
    }
    signal(UNINVITED_CHECKED);
    LOGGER.info("E2E joiner: invited-only world stayed hidden");

    // 7. The new invite: Invite is greyed out on the title screen; Decline hides Alice's world.
    context.waitFor(minecraft -> OpenDoorsClient.online().directory().hasInvite(alice), JOIN_TICKS);
    openFriends(context, true);
    check(
        context.computeOnClient(
            minecraft -> {
              Button invite = rowButton(minecraft.gui.screen(), HOST_NAME, INVITE_KEY);
              return invite != null && !invite.active;
            }),
        "Invite greyed out without a world to share");
    screenshot(context, "guest-friends-invite-again");
    context.runOnClient(
        minecraft ->
            check(
                pressInRow(minecraft.gui.screen(), HOST_NAME, DECLINE_KEY),
                "Decline on Alice's row"));
    context.waitTicks(2);
    check(
        context.computeOnClient(
            minecraft ->
                !OpenDoorsClient.online().directory().isJoinable(alice)
                    && !OpenDoorsClient.online().directory().hasInvite(alice)
                    && rowButton(minecraft.gui.screen(), HOST_NAME, JOIN_KEY) == null),
        "Decline takes Alice's world off the list");
    screenshot(context, "guest-friends-declined");
    signal(DECLINED);
    LOGGER.info("E2E joiner: declined Alice's invite; PASS");
    context.setScreen(TitleScreen::new);
  }

  private static void clearSignals() {
    for (String step : List.of(INVITED_ONLY_OPEN, UNINVITED_CHECKED, DECLINED)) {
      try {
        Files.deleteIfExists(SYNC_DIRECTORY.resolve(step));
      } catch (IOException exception) {
        throw new UncheckedIOException(exception);
      }
    }
  }

  /** Tells the other client that {@code step} is done. */
  private static void signal(String step) {
    try {
      Files.createDirectories(SYNC_DIRECTORY);
      Files.writeString(SYNC_DIRECTORY.resolve(step), step);
    } catch (IOException exception) {
      throw new UncheckedIOException(exception);
    }
  }

  private static void awaitSignal(ClientGameTestContext context, String step) {
    context.waitFor(minecraft -> Files.exists(SYNC_DIRECTORY.resolve(step)), JOIN_TICKS);
  }

  /** Presses Join on Alice's row, pressing again if an attempt fails while her side settles. */
  private static void joinFromFriends(ClientGameTestContext context, UUID alice) {
    context.runOnClient(
        minecraft ->
            check(pressInRow(minecraft.gui.screen(), HOST_NAME, JOIN_KEY), "Join on Alice's row"));
    for (int tick = 0; ; tick++) {
      if (tick == JOIN_TICKS) {
        throw new AssertionError("never got into Alice's world");
      }
      if (context.computeOnClient(minecraft -> minecraft.level != null)) {
        return;
      }
      boolean failed =
          context.computeOnClient(
              minecraft ->
                  minecraft.gui.screen() instanceof JoinProgressScreen screen
                      && screen.failure() != null);
      if (failed && tick % RETRY_TICKS == 0) {
        context.runOnClient(
            minecraft -> OpenDoorsClient.online().join(minecraft, alice, HOST_NAME));
      }
      context.waitTick();
    }
  }

  /** Opens the Friends List over the title screen or (null background) over the world. */
  private static void openFriends(ClientGameTestContext context, boolean overTitle) {
    context.setScreen(() -> new FriendsOverlayScreen(overTitle ? new TitleScreen() : null));
    context.waitTicks(2);
  }

  private static void pressHostMode(Minecraft minecraft) {
    Button button = ScreenWidgets.button(minecraft.gui.screen(), HostModeButton.class::isInstance);
    check(button != null && button.active, "My world button in the Friends List");
    button.onPress(new MouseButtonInfo(InputConstants.MOUSE_BUTTON_LEFT, 0));
  }

  /** The button labelled {@code key} in the Friends List row of {@code friend}. */
  private static @Nullable Button rowButton(@Nullable Screen screen, String friend, String key) {
    String label = Component.translatable(key).getString();
    boolean inRow = false;
    for (AbstractWidget widget : ScreenWidgets.all(screen)) {
      if (widget instanceof StringWidget text && E2eFriends.isFriendName(text.getMessage())) {
        inRow = text.getMessage().getString().equals(friend);
      } else if (inRow
          && widget instanceof Button button
          && button.visible
          && button.getMessage().getString().equals(label)) {
        return button;
      }
    }
    return null;
  }

  private static boolean pressInRow(@Nullable Screen screen, String friend, String key) {
    Button button = rowButton(screen, friend, key);
    if (button == null || !button.active) {
      return false;
    }
    button.onPress(new MouseButtonInfo(InputConstants.MOUSE_BUTTON_LEFT, 0));
    return true;
  }

  private static void screenshot(ClientGameTestContext context, String name) {
    Path path = context.takeScreenshot("opendoors-" + name);
    LOGGER.info("E2E screenshot {}", path.toAbsolutePath());
  }

  private static boolean joinerOnline(IntegratedServer server) {
    return server != null && server.getPlayerList().getPlayerByName(JOINER_NAME) != null;
  }

  private static void check(boolean condition, String what) {
    if (!condition) {
      throw new AssertionError(what);
    }
  }
}
