package com.einsteinmods.opendoors.client.mixin;

import com.einsteinmods.opendoors.client.host.FriendsHost;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import net.minecraft.client.gui.screens.WorldOptionsScreen;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Guest command access and forced game mode in World Options can be set while the world is open to
 * friends online with LAN off, since friends are guests too ({@code
 * WorldOptionsScreen#updateGuestCommandAccessButton} and {@code updateForceGameModeButton} only
 * enable them for a LAN scope). Who may join online is chosen in the Friends List.
 */
@Mixin(WorldOptionsScreen.class)
abstract class WorldOptionsScreenMixin {
  @ModifyExpressionValue(
      method = {"updateGuestCommandAccessButton", "updateForceGameModeButton"},
      at =
          @At(
              value = "FIELD",
              target =
                  "Lnet/minecraft/client/gui/screens/WorldOptionsScreen;wantedMultiplayerScope:Lnet/minecraft/server/MinecraftServer$MultiplayerScope;"))
  private MinecraftServer.MultiplayerScope opendoors$treatOnlineAsGuests(
      MinecraftServer.MultiplayerScope original) {
    return original == MinecraftServer.MultiplayerScope.OFF && FriendsHost.isOpen()
        ? MinecraftServer.MultiplayerScope.LAN
        : original;
  }
}
