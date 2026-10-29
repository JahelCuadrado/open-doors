package com.einsteinmods.opendoors.client.mixin;

import com.einsteinmods.opendoors.client.host.FriendsHost;
import com.einsteinmods.opendoors.host.PeerAddress;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * While friends can join online the world counts as published ({@code IntegratedServer#isPublished}
 * is what keeps the game running when the host pauses, and what /kick checks), but LAN keeps its
 * vanilla meaning: {@code publishServer} and {@code unpublishServer} test the LAN scope alone, and
 * turning LAN off no longer disconnects friends who came in online.
 */
@Mixin(IntegratedServer.class)
abstract class IntegratedServerMixin {
  @Shadow private MinecraftServer.MultiplayerScope multiplayerScope;

  @Inject(method = "isPublished", at = @At("RETURN"), cancellable = true)
  private void opendoors$publishedWhileOnline(CallbackInfoReturnable<Boolean> info) {
    if (!info.getReturnValueZ() && FriendsHost.isOpen()) {
      info.setReturnValue(true);
    }
  }

  @WrapOperation(
      method = {
        "publishServer(Lnet/minecraft/server/MinecraftServer$MultiplayerScope;I)Z",
        "unpublishServer"
      },
      at =
          @At(
              value = "INVOKE",
              target = "Lnet/minecraft/client/server/IntegratedServer;isPublished()Z"))
  private boolean opendoors$lanOnly(IntegratedServer server, Operation<Boolean> original) {
    return multiplayerScope != MinecraftServer.MultiplayerScope.OFF;
  }

  @WrapOperation(
      method = "unpublishServer",
      at =
          @At(
              value = "INVOKE",
              target =
                  "Lnet/minecraft/server/network/ServerGamePacketListenerImpl;disconnect(Lnet/minecraft/network/chat/Component;)V"))
  private void opendoors$keepOnlineFriends(
      ServerGamePacketListenerImpl connection, Component reason, Operation<Void> original) {
    if (!(connection.getRemoteAddress() instanceof PeerAddress && FriendsHost.isOpen())) {
      original.call(connection, reason);
    }
  }
}
