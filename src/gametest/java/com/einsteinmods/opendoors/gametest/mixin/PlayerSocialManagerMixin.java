package com.einsteinmods.opendoors.gametest.mixin;

import com.einsteinmods.opendoors.gametest.E2eFriends;
import java.util.List;
import net.minecraft.client.gui.screens.social.PlayerSocialManager;
import net.minecraft.client.gui.screens.social.RemoteFriendListUpdateHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** End to end run only: the Friends List shows {@link E2eFriends} instead of Mojang's list. */
@Mixin(PlayerSocialManager.class)
abstract class PlayerSocialManagerMixin {
  @Inject(method = "isFriendListEnabled", at = @At("HEAD"), cancellable = true)
  private void opendoorsTest$enabled(CallbackInfoReturnable<Boolean> info) {
    if (E2eFriends.active()) {
      info.setReturnValue(true);
    }
  }

  @Inject(method = "getFriendListState", at = @At("HEAD"), cancellable = true)
  private void opendoorsTest$loaded(
      CallbackInfoReturnable<RemoteFriendListUpdateHandler.State> info) {
    if (E2eFriends.active()) {
      info.setReturnValue(RemoteFriendListUpdateHandler.State.SUCCESS);
    }
  }

  @Inject(method = "getFriends", at = @At("HEAD"), cancellable = true)
  private void opendoorsTest$friends(
      CallbackInfoReturnable<List<PlayerSocialManager.PlayerData>> info) {
    if (E2eFriends.active()) {
      info.setReturnValue(E2eFriends.friends());
    }
  }
}
