package com.einsteinmods.opendoors.client.mixin;

import com.einsteinmods.opendoors.client.host.FriendsHost;
import com.mojang.authlib.services.response.PresenceStatus;
import net.minecraft.client.gui.screens.social.PresenceHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Friends see an online-hosted world as hosting ({@code PLAYING_HOSTED_SERVER}), as vanilla does
 * for LAN in {@code PresenceHandler#getPresenceStatus}. That method only runs when presence is
 * shared with everyone ({@code getPublicPresenceStatus}, sharePresence ALL); with NONE or LIMITED
 * nothing about the world is revealed.
 */
@Mixin(PresenceHandler.class)
abstract class PresenceHandlerMixin {
  @Inject(method = "getPresenceStatus", at = @At("RETURN"), cancellable = true)
  private void opendoors$hostingOnline(CallbackInfoReturnable<PresenceStatus> info) {
    if (info.getReturnValue() == PresenceStatus.PLAYING_OFFLINE
        && FriendsHost.get().isAdvertising()) {
      info.setReturnValue(PresenceStatus.PLAYING_HOSTED_SERVER);
    }
  }
}
