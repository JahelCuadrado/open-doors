package com.einsteinmods.opendoors.client.config;

import java.util.function.Consumer;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

/** On/off row drawn with a tick box on the right, as YACL's tick box controller. */
final class TickBoxControl extends OptionControl<Boolean> {
  private static final int BOX_SIZE = 10;
  private static final int TICK_INSET = 2;

  private final Component description;

  TickBoxControl(
      Font font,
      Component name,
      Component description,
      boolean initial,
      boolean defaultValue,
      Consumer<Boolean> onPlayerChange) {
    super(font, name, initial, defaultValue, onPlayerChange);
    this.description = description;
  }

  @Override
  public Component description() {
    return description;
  }

  /** Narrated only: the box shows the state. */
  @Override
  protected Component valueText() {
    return CommonComponents.optionStatus(get());
  }

  @Override
  protected int controlWidth() {
    return BOX_SIZE;
  }

  @Override
  protected void extractControl(GuiGraphicsExtractor graphics, int textY) {
    int right = getRight() - X_PADDING;
    int left = right - BOX_SIZE;
    int top = centerY() - BOX_SIZE / 2;
    int bottom = top + BOX_SIZE;
    graphics.outline(left + 1, top + 1, BOX_SIZE, BOX_SIZE, CONTROL_SHADOW_COLOR);
    graphics.outline(left, top, BOX_SIZE, BOX_SIZE, TEXT_COLOR);
    if (get()) {
      int inset = TICK_INSET;
      graphics.fill(
          left + inset + 1,
          top + inset + 1,
          right - inset + 1,
          bottom - inset + 1,
          CONTROL_SHADOW_COLOR);
      graphics.fill(left + inset, top + inset, right - inset, bottom - inset, TEXT_COLOR);
    }
  }

  @Override
  public void onClick(MouseButtonEvent event, boolean doubleClick) {
    setByPlayer(!get());
  }

  @Override
  public boolean keyPressed(KeyEvent event) {
    if (event.isSelection()) {
      setByPlayer(!get());
      return true;
    }
    return false;
  }
}
