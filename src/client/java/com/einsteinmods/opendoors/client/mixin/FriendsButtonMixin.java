package com.einsteinmods.opendoors.client.mixin;

import com.einsteinmods.opendoors.client.OpenDoorsClient;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.FriendsButton;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * A green dot on the Friends button (title and pause screens, {@code CommonButtons#friends}) while
 * a friend's world is joinable or an invite is waiting. It sits where {@code
 * FriendsButton#extractContents} draws the friend request badge, or in the opposite corner when
 * that badge shows. Per frame: a few field reads, no allocation of its own.
 */
@Mixin(FriendsButton.class)
abstract class FriendsButtonMixin extends AbstractWidget {
  @Unique private static final int DOT_SIZE = 6;
  @Unique private static final int DOT_OVERHANG = 2;
  @Unique private static final int DOT_BORDER_COLOR = 0xFF0A3D0A;
  @Unique private static final int DOT_COLOR = 0xFF3CE03C;

  @Shadow private int incomingRequestCount;

  private FriendsButtonMixin() {
    super(0, 0, 0, 0, Component.empty());
  }

  @Inject(method = "extractContents", at = @At("TAIL"))
  private void opendoors$extractDot(
      GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick, CallbackInfo info) {
    if (!isActive() || !OpenDoorsClient.online().hasFriendNotification()) {
      return;
    }
    int x =
        incomingRequestCount > 0
            ? getX() - DOT_OVERHANG
            : getX() + getWidth() - DOT_SIZE + DOT_OVERHANG;
    int y = getY() - DOT_OVERHANG;
    graphics.fill(x, y, x + DOT_SIZE, y + DOT_SIZE, DOT_BORDER_COLOR);
    graphics.fill(x + 1, y + 1, x + DOT_SIZE - 1, y + DOT_SIZE - 1, DOT_COLOR);
  }
}
