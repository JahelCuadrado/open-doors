package com.einsteinmods.opendoors.client.config;

import java.util.function.Consumer;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/** Option row whose value is free text, edited in a vanilla {@link EditBox} on the right. */
final class TextControl extends OptionControl<String> {
  private static final int MAX_LENGTH = 256;
  private static final int BOX_HEIGHT = 16;
  private static final int BOX_INSET = 2;

  /** Share of the row given to the text box. */
  private static final float BOX_SHARE = 0.6F;

  private final Component description;
  private final EditBox box;

  TextControl(
      Font font,
      Component name,
      Component description,
      Component hint,
      String initial,
      String defaultValue,
      Consumer<String> onPlayerChange) {
    super(font, name, initial, defaultValue, onPlayerChange);
    this.description = description;
    this.box = new EditBox(font, 0, BOX_HEIGHT, name);
    box.setMaxLength(MAX_LENGTH);
    box.setHint(hint);
    box.setValue(initial);
    box.setResponder(this::setByPlayer);
  }

  @Override
  public Component description() {
    return description;
  }

  @Override
  protected Component valueText() {
    return Component.literal(get());
  }

  @Override
  protected int controlWidth() {
    return (int) (getWidth() * BOX_SHARE);
  }

  @Override
  protected void extractControl(GuiGraphicsExtractor graphics, int textY) {
    if (!box.isFocused() && !box.getValue().equals(get())) {
      // Undo or Reset changed the value: show it.
      box.setValue(get());
    }
    int width = controlWidth();
    box.setRectangle(width, BOX_HEIGHT, getRight() - width - BOX_INSET, getY() + BOX_INSET);
    box.extractRenderState(graphics, 0, 0, 0.0F);
  }

  @Override
  public void onClick(MouseButtonEvent event, boolean doubleClick) {
    box.setFocused(true);
    box.onClick(event, doubleClick);
  }

  @Override
  public void setFocused(boolean focused) {
    super.setFocused(focused);
    box.setFocused(focused);
  }

  @Override
  public boolean keyPressed(KeyEvent event) {
    return box.keyPressed(event);
  }

  @Override
  public boolean charTyped(CharacterEvent event) {
    return box.charTyped(event);
  }
}
