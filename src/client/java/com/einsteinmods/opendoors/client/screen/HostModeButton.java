package com.einsteinmods.opendoors.client.screen;

import com.einsteinmods.opendoors.client.OpenDoorsClient;
import com.einsteinmods.opendoors.client.host.FriendsHost;
import com.einsteinmods.opendoors.core.online.OnlineMode;
import java.util.Locale;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

/**
 * "My world: Closed / Open to friends / Invited only", applied on click. It shows {@link
 * FriendsHost#mode()} rather than its own value, so a world opened by "Invite" reads right too: one
 * field comparison per frame, and the label is only rebuilt when the mode changed.
 */
public final class HostModeButton extends Button.Plain {
  private static final String KEY = "opendoors.online";
  private static final Component NAME = Component.translatable(KEY);
  private static final Tooltip UNAVAILABLE =
      Tooltip.create(Component.translatable(KEY + ".unavailable"));
  private static final OnlineMode[] MODES = OnlineMode.values();
  private static final int HEIGHT = 20;

  private OnlineMode shown;
  private boolean shownActive;

  public HostModeButton(int width, IntegratedServer server) {
    super(0, 0, width, HEIGHT, label(OnlineMode.OFF), pressed -> cycle(server), DEFAULT_NARRATION);
    shown = OnlineMode.OFF;
    shownActive = true;
    setTooltip(tooltip(shown));
    sync();
  }

  @Override
  protected void extractContents(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
    sync();
    super.extractContents(graphics, mouseX, mouseY, a);
  }

  /** Follows the current mode; opening needs the Open Doors server, closing never does. */
  private void sync() {
    OnlineMode mode = FriendsHost.get().mode();
    boolean available = mode != OnlineMode.OFF || OpenDoorsClient.online().canHost();
    if (mode == shown && available == shownActive) {
      return;
    }
    shown = mode;
    shownActive = available;
    active = available;
    setMessage(label(mode));
    setTooltip(available ? tooltip(mode) : UNAVAILABLE);
  }

  private static void cycle(IntegratedServer server) {
    OnlineMode next = MODES[(FriendsHost.get().mode().ordinal() + 1) % MODES.length];
    FriendsHost.get().changeMode(server, next);
  }

  private static Component label(OnlineMode mode) {
    return CommonComponents.optionNameValue(
        NAME, Component.translatable(KEY + "." + mode.name().toLowerCase(Locale.ROOT)));
  }

  private static Tooltip tooltip(OnlineMode mode) {
    return Tooltip.create(
        Component.translatable(KEY + "." + mode.name().toLowerCase(Locale.ROOT) + ".tooltip"));
  }
}
