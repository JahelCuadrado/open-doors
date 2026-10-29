package com.einsteinmods.opendoors.gametest;

import com.mojang.blaze3d.platform.InputConstants;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.components.events.ContainerEventHandler;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/**
 * Finds widgets nested in containers (Friends List rows, scroll areas), which Fabric's {@code
 * clickScreenButton} does not reach.
 */
final class ScreenWidgets {
  private ScreenWidgets() {}

  static List<AbstractWidget> all(@Nullable Screen screen) {
    List<AbstractWidget> widgets = new ArrayList<>();
    if (screen != null) {
      collect(screen, widgets);
    }
    return widgets;
  }

  static @Nullable Button button(@Nullable Screen screen, Predicate<Button> matches) {
    for (AbstractWidget widget : all(screen)) {
      if (widget instanceof Button button && button.visible && matches.test(button)) {
        return button;
      }
    }
    return null;
  }

  static @Nullable Button button(@Nullable Screen screen, String translationKey) {
    String label = Component.translatable(translationKey).getString();
    return button(screen, button -> button.getMessage().getString().equals(label));
  }

  /** Presses the first visible, active button labelled {@code translationKey}. */
  static boolean press(@Nullable Screen screen, String translationKey) {
    Button button = button(screen, translationKey);
    if (button == null || !button.active) {
      return false;
    }
    button.onPress(new MouseButtonInfo(InputConstants.MOUSE_BUTTON_LEFT, 0));
    return true;
  }

  /** Texts of the screen's string widgets, in order (Friends List names among them). */
  static List<String> texts(@Nullable Screen screen) {
    List<String> texts = new ArrayList<>();
    for (AbstractWidget widget : all(screen)) {
      if (widget instanceof StringWidget text) {
        texts.add(text.getMessage().getString());
      }
    }
    return texts;
  }

  private static void collect(GuiEventListener listener, List<AbstractWidget> widgets) {
    if (listener instanceof AbstractWidget widget) {
      widgets.add(widget);
    }
    if (listener instanceof ContainerEventHandler container) {
      for (GuiEventListener child : container.children()) {
        collect(child, widgets);
      }
    }
  }
}
