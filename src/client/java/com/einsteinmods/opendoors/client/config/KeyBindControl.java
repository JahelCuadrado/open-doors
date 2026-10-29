package com.einsteinmods.opendoors.client.config;

import com.mojang.blaze3d.platform.InputConstants;
import java.util.function.Consumer;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.Font;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/**
 * Row for one vanilla key binding: shows the bound key; click, Enter or Space asks the screen to
 * capture the next key or mouse button (the screen receives every input while capturing).
 */
final class KeyBindControl extends OptionControl<InputConstants.Key> {
  private final Component description;
  private final Component waitingPrompt;
  private final Consumer<KeyBindControl> onStartCapture;
  private boolean capturing;

  KeyBindControl(
      Font font,
      Component name,
      Component description,
      Component waiting,
      InputConstants.Key initial,
      InputConstants.Key defaultValue,
      Consumer<KeyBindControl> onStartCapture,
      Consumer<InputConstants.Key> onPlayerChange) {
    super(font, name, initial, defaultValue, onPlayerChange);
    this.description = description;
    this.waitingPrompt = waitingPrompt(waiting);
    this.onStartCapture = onStartCapture;
  }

  @Override
  public Component description() {
    return description;
  }

  @Override
  protected Component valueText() {
    return capturing ? waitingPrompt : get().getDisplayName();
  }

  void setCapturing(boolean capturing) {
    this.capturing = capturing;
  }

  /** Assigns the captured key as a player edit. */
  void assign(InputConstants.Key key) {
    capturing = false;
    setByPlayer(key);
  }

  @Override
  public void onClick(MouseButtonEvent event, boolean doubleClick) {
    onStartCapture.accept(this);
  }

  @Override
  public boolean keyPressed(KeyEvent event) {
    if (event.isSelection()) {
      onStartCapture.accept(this);
      return true;
    }
    return false;
  }

  /** Same look as vanilla {@code KeyBindsList} while waiting for a key. */
  private static Component waitingPrompt(Component waiting) {
    return Component.literal("> ")
        .append(waiting.copy().withStyle(ChatFormatting.WHITE, ChatFormatting.UNDERLINE))
        .append(" <")
        .withStyle(ChatFormatting.YELLOW);
  }
}
