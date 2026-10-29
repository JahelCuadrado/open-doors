package com.einsteinmods.opendoors.gametest.mixin;

import com.einsteinmods.opendoors.gametest.E2eFriends;
import net.minecraft.client.gui.screens.PauseScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * End to end run only: the pause screen's Friends button is active although dev identities run in
 * offline developer mode ({@code PauseScreen#createPauseMenu} passes {@code
 * !isOfflineDeveloperMode()} to {@code CommonButtons#friends}).
 */
@Mixin(PauseScreen.class)
abstract class PauseScreenFriendsMixin {
  @ModifyArg(
      method = "createPauseMenu",
      at =
          @At(
              value = "INVOKE",
              target =
                  "Lnet/minecraft/client/gui/components/CommonButtons;friends(ILnet/minecraft/client/gui/components/Button$OnPress;Z)Lnet/minecraft/client/gui/components/FriendsButton;"),
      index = 2)
  private boolean opendoorsTest$available(boolean available) {
    return available || E2eFriends.active();
  }
}
