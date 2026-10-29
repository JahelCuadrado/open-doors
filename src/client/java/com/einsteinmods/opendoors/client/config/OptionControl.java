package com.einsteinmods.opendoors.client.config;

import java.util.function.Consumer;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.WidgetSprites;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;

/**
 * One option row in the style of a YACL controller: a button-shaped box with the name on the left
 * and the value (or a control) on the right. The name turns italic while the value differs from the
 * saved one.
 *
 * <p>Holds three values: the edited one, the saved one (Undo, "changed") and the default (Reset).
 * Player edits notify the listener; programmatic sets ({@link #setQuietly}) do not.
 *
 * @param <T> option value type; values are compared with {@link Object#equals}
 */
abstract class OptionControl<T> extends AbstractWidget implements DescribedOption {
  static final int HEIGHT = 20;
  static final int TEXT_COLOR = 0xFFFFFFFF;

  /** Shadow under drawn controls (tick box, slider), as YACL. */
  static final int CONTROL_SHADOW_COLOR = 0xFF404040;

  /** Gap between the box edges and the name / value. */
  static final int X_PADDING = 5;

  /** Minimum gap between the name and the value. */
  private static final int NAME_VALUE_GAP = 7;

  private static final String ELLIPSIS = "...";
  private static final WidgetSprites BACKGROUND =
      new WidgetSprites(
          Identifier.withDefaultNamespace("widget/button"),
          Identifier.withDefaultNamespace("widget/button_disabled"),
          Identifier.withDefaultNamespace("widget/button_highlighted"));

  protected final Font font;
  private final Component name;
  private final T defaultValue;
  private final Consumer<T> onPlayerChange;
  private T savedValue;
  private T value;

  OptionControl(Font font, Component name, T initial, T defaultValue, Consumer<T> onPlayerChange) {
    super(0, 0, 0, HEIGHT, name);
    this.font = font;
    this.name = name;
    this.defaultValue = defaultValue;
    this.onPlayerChange = onPlayerChange;
    this.savedValue = initial;
    this.value = initial;
  }

  @Override
  public Component name() {
    return name;
  }

  final T get() {
    return value;
  }

  /** Sets the value without notifying the listener (presets, Undo, Reset all). */
  final void setQuietly(T newValue) {
    value = newValue;
  }

  /** Sets the value as a player edit: notifies the listener when it changes. */
  protected final void setByPlayer(T newValue) {
    if (!newValue.equals(value)) {
      value = newValue;
      onPlayerChange.accept(newValue);
    }
  }

  final boolean isChanged() {
    return !value.equals(savedValue);
  }

  final boolean isDefault() {
    return value.equals(defaultValue);
  }

  /** The row's reset button: a player edit back to the default. */
  final void resetToDefault() {
    setByPlayer(defaultValue);
  }

  final void resetQuietly() {
    value = defaultValue;
  }

  final void undo() {
    value = savedValue;
  }

  final void markSaved() {
    savedValue = value;
  }

  /** Value as text: shown on the right (unless the control is drawn as a shape) and narrated. */
  protected abstract Component valueText();

  /** Width taken by the control on the right, so the name can be shortened to fit. */
  protected int controlWidth() {
    return font.width(valueText());
  }

  /** Draws the value or control on the right side of the box. */
  protected void extractControl(GuiGraphicsExtractor graphics, int textY) {
    Component text = valueText();
    graphics.text(font, text, getRight() - X_PADDING - font.width(text), textY, TEXT_COLOR, true);
  }

  protected final int centerY() {
    return getY() + getHeight() / 2;
  }

  @Override
  protected void extractWidgetRenderState(
      GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
    graphics.blitSprite(
        RenderPipelines.GUI_TEXTURED,
        BACKGROUND.get(active, isHoveredOrFocused()),
        getX(),
        getY(),
        getWidth(),
        getHeight());
    int textY = centerY() - font.lineHeight / 2;
    int nameWidth = getWidth() - controlWidth() - X_PADDING - NAME_VALUE_GAP;
    graphics.text(font, fittedName(nameWidth), getX() + X_PADDING, textY, TEXT_COLOR, true);
    extractControl(graphics, textY);
    handleCursor(graphics);
  }

  private Component fittedName(int maxWidth) {
    String text = name.getString();
    if (font.width(text) > maxWidth) {
      text = font.plainSubstrByWidth(text, Math.max(0, maxWidth - font.width(ELLIPSIS))) + ELLIPSIS;
    }
    MutableComponent fitted = Component.literal(text);
    return isChanged() ? fitted.withStyle(ChatFormatting.ITALIC) : fitted;
  }

  @Override
  protected void updateWidgetNarration(NarrationElementOutput output) {
    output.add(NarratedElementType.TITLE, CommonComponents.optionNameValue(name, valueText()));
    output.add(NarratedElementType.HINT, description());
  }
}
