package com.einsteinmods.opendoors.client.config;

import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import org.jspecify.annotations.Nullable;

/**
 * Right-hand pane text, as YACL's option description: the option name in bold, then its wrapped
 * description, clipped to the pane. Shows the last option hovered or focused.
 */
final class OptionDescriptionPanel {
  /** Gap between the name and the description, as YACL. */
  private static final int NAME_GAP = 5;

  private final Font font;
  private DescribedOption shown;

  /** Wrapped lines of {@link #wrappedName} / {@link #wrappedDescription} at {@link #wrapWidth}. */
  private List<FormattedCharSequence> nameLines = List.of();

  private List<FormattedCharSequence> descriptionLines = List.of();
  private @Nullable Component wrappedName;
  private @Nullable Component wrappedDescription;
  private int wrapWidth;

  OptionDescriptionPanel(Font font, DescribedOption initial) {
    this.font = font;
    this.shown = initial;
  }

  void show(DescribedOption option) {
    shown = option;
  }

  void extract(GuiGraphicsExtractor graphics, ScreenRectangle area) {
    rewrapIfNeeded(area.width());
    graphics.enableScissor(area.left(), area.top(), area.right(), area.bottom());
    int y = area.top();
    for (FormattedCharSequence line : nameLines) {
      graphics.text(font, line, area.left(), y, OptionControl.TEXT_COLOR, true);
      y += font.lineHeight;
    }
    y += NAME_GAP;
    for (FormattedCharSequence line : descriptionLines) {
      graphics.text(font, line, area.left(), y, OptionControl.TEXT_COLOR, true);
      y += font.lineHeight;
    }
    graphics.disableScissor();
  }

  /** Wraps again only when the option, its text (a preset's changes) or the width changed. */
  private void rewrapIfNeeded(int width) {
    Component name = shown.name();
    Component description = shown.description();
    if (name == wrappedName && description == wrappedDescription && width == wrapWidth) {
      return;
    }
    wrappedName = name;
    wrappedDescription = description;
    wrapWidth = width;
    nameLines = font.split(name.copy().withStyle(ChatFormatting.BOLD), width);
    descriptionLines = font.split(description, width);
  }
}
