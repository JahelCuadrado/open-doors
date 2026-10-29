package com.einsteinmods.opendoors.client.screen;

import com.einsteinmods.opendoors.client.host.FriendsHost;
import com.einsteinmods.opendoors.core.access.AccessPolicy;
import com.einsteinmods.opendoors.host.PeerAddress;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.ContainerObjectSelectionList;
import net.minecraft.client.gui.components.PlayerFaceExtractor;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.layouts.HeaderAndFooterLayout;
import net.minecraft.client.gui.narration.NarratableEntry;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.component.ResolvableProfile;

/**
 * The host's player list (Friends List → Players…): who is in the world (kick, block), and the
 * world's invited and blocked players. Player data is read on the server thread once a second and
 * handed back to the render thread.
 */
public final class HostPlayersScreen extends Screen {
  private static final String KEY = "opendoors.players.";
  private static final int ROW_HEIGHT = 24;
  private static final int FACE_SIZE = 16;
  private static final int BUTTON_WIDTH = 60;
  private static final int BUTTON_HEIGHT = 20;
  private static final int GAP = 4;
  private static final int TEXT_COLOR = 0xFFFFFFFF;
  private static final int SECTION_COLOR = 0xFFE0E0E0;
  private static final int REFRESH_TICKS = 20;
  private static final Component KICK = Component.translatable(KEY + "kick");
  private static final Component BLOCK = Component.translatable(KEY + "block");
  private static final Component UNBLOCK = Component.translatable(KEY + "unblock");
  private static final Component REMOVE = Component.translatable(KEY + "remove");

  /** One player in the world, as read on the server thread. */
  private record Present(UUID id, String name, boolean online) {}

  private final Screen parent;
  private final HeaderAndFooterLayout layout = new HeaderAndFooterLayout(this);
  private PlayerRows rows;
  private int ticksUntilRefresh;

  public HostPlayersScreen(Screen parent) {
    super(Component.translatable(KEY + "title"));
    this.parent = parent;
  }

  @Override
  protected void init() {
    layout.addTitleHeader(title, font);
    rows = layout.addToContents(new PlayerRows(minecraft, width, layout.getContentHeight()));
    layout.addToFooter(Button.builder(CommonComponents.GUI_DONE, button -> onClose()).build());
    layout.visitWidgets(this::addRenderableWidget);
    repositionElements();
    refresh();
  }

  @Override
  protected void repositionElements() {
    layout.arrangeElements();
    if (rows != null) {
      rows.updateSize(width, layout);
    }
  }

  @Override
  public void tick() {
    if (--ticksUntilRefresh <= 0) {
      ticksUntilRefresh = REFRESH_TICKS;
      refresh();
    }
  }

  @Override
  public void onClose() {
    minecraft.gui.setScreen(parent);
  }

  private void refresh() {
    IntegratedServer server = minecraft.getSingleplayerServer();
    if (server == null) {
      rebuild(List.of());
      return;
    }
    UUID host = minecraft.player == null ? null : minecraft.player.getUUID();
    server.execute(
        () -> {
          List<Present> present = new ArrayList<>();
          for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (!player.getUUID().equals(host)) {
              present.add(
                  new Present(
                      player.getUUID(),
                      player.getGameProfile().name(),
                      player.connection.getRemoteAddress() instanceof PeerAddress));
            }
          }
          present.sort(Comparator.comparing(Present::name));
          minecraft.execute(() -> rebuild(present));
        });
  }

  private void rebuild(List<Present> present) {
    if (rows == null) {
      return;
    }
    FriendsHost host = FriendsHost.get();
    AccessPolicy policy = host.policy();
    List<Row> next = new ArrayList<>();
    if (!present.isEmpty()) {
      next.add(new SectionRow(Component.translatable(KEY + "section.present")));
      for (Present player : present) {
        next.add(
            new PlayerRow(
                player.id(),
                player.name(),
                button(KICK, () -> kick(player.id())),
                player.online()
                    ? button(BLOCK, () -> host.block(player.id(), player.name()))
                    : null));
      }
    }
    addListed(next, "section.invited", policy.invited(), REMOVE, host::uninvite);
    addListed(next, "section.blocked", policy.blocked(), UNBLOCK, host::unblock);
    if (next.isEmpty()) {
      next.add(new SectionRow(Component.translatable(KEY + "empty")));
    }
    rows.show(next);
  }

  private void addListed(
      List<Row> next,
      String section,
      Map<UUID, String> players,
      Component action,
      java.util.function.Consumer<UUID> onAction) {
    if (players.isEmpty()) {
      return;
    }
    next.add(new SectionRow(Component.translatable(KEY + section)));
    players.entrySet().stream()
        .sorted(Map.Entry.comparingByValue())
        .forEach(
            entry ->
                next.add(
                    new PlayerRow(
                        entry.getKey(),
                        entry.getValue(),
                        button(action, () -> onAction.accept(entry.getKey())),
                        null)));
  }

  private Button button(Component label, Runnable action) {
    return Button.builder(
            label,
            pressed -> {
              action.run();
              refresh();
            })
        .size(BUTTON_WIDTH, BUTTON_HEIGHT)
        .build();
  }

  private void kick(UUID player) {
    IntegratedServer server = minecraft.getSingleplayerServer();
    if (server == null) {
      return;
    }
    server.execute(
        () -> {
          ServerPlayer target = server.getPlayerList().getPlayer(player);
          if (target != null) {
            target.connection.disconnect(Component.translatable("multiplayer.disconnect.kicked"));
          }
        });
  }

  /** The scrolling list of sections and players. */
  private static final class PlayerRows extends ContainerObjectSelectionList<Row> {
    PlayerRows(Minecraft minecraft, int width, int height) {
      super(minecraft, width, height, 0, ROW_HEIGHT);
    }

    void show(List<Row> next) {
      replaceEntries(next);
      refreshScrollAmount();
    }

    @Override
    public int getRowWidth() {
      return Math.min(320, getWidth() - 40);
    }
  }

  private abstract static class Row extends ContainerObjectSelectionList.Entry<Row> {}

  /** A section title (or the empty-list message). */
  private final class SectionRow extends Row {
    private final Component text;

    SectionRow(Component text) {
      this.text = text;
    }

    @Override
    public void extractContent(
        GuiGraphicsExtractor graphics, int mouseX, int mouseY, boolean hovered, float partialTick) {
      graphics.text(
          font,
          text,
          getContentX(),
          getContentYMiddle() - font.lineHeight / 2,
          SECTION_COLOR,
          true);
    }

    @Override
    public List<? extends GuiEventListener> children() {
      return List.of();
    }

    @Override
    public List<? extends NarratableEntry> narratables() {
      return List.of();
    }
  }

  /** Face, name and up to two action buttons on the right. */
  private final class PlayerRow extends Row {
    private final ResolvableProfile face;
    private final Component name;
    private final List<Button> buttons;

    PlayerRow(UUID id, String name, Button first, Button second) {
      this.face = ResolvableProfile.createUnresolved(id);
      this.name = Component.literal(name);
      this.buttons = second == null ? List.of(first) : List.of(first, second);
    }

    @Override
    public void extractContent(
        GuiGraphicsExtractor graphics, int mouseX, int mouseY, boolean hovered, float partialTick) {
      int x = getContentX();
      PlayerFaceExtractor.extractRenderState(
          graphics, face, x, getContentYMiddle() - FACE_SIZE / 2, FACE_SIZE);
      graphics.text(
          font,
          name,
          x + FACE_SIZE + GAP,
          getContentYMiddle() - font.lineHeight / 2,
          TEXT_COLOR,
          true);
      int right = getContentRight();
      for (int i = buttons.size() - 1; i >= 0; i--) {
        Button button = buttons.get(i);
        right -= BUTTON_WIDTH;
        button.setPosition(right, getContentYMiddle() - BUTTON_HEIGHT / 2);
        button.extractRenderState(graphics, mouseX, mouseY, partialTick);
        right -= GAP;
      }
    }

    @Override
    public List<? extends GuiEventListener> children() {
      return buttons;
    }

    @Override
    public List<? extends NarratableEntry> narratables() {
      return buttons;
    }
  }
}
