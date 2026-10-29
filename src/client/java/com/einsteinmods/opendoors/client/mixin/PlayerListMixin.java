package com.einsteinmods.opendoors.client.mixin;

import com.einsteinmods.opendoors.client.host.FriendsHost;
import com.einsteinmods.opendoors.host.PeerAddress;
import java.net.SocketAddress;
import net.minecraft.network.chat.Component;
import net.minecraft.server.players.NameAndId;
import net.minecraft.server.players.PlayerList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Closes two login holes for friends that come in through Open Doors, in {@code
 * PlayerList#canPlayerLogin} (also reached from {@code IntegratedPlayerList#canPlayerLogin}):
 * {@code ServerLoginPacketListenerImpl#handleHello} hands the host's profile to anyone using the
 * host's name, and an integrated server lets a failed session check in with an offline profile. The
 * logged-in profile must be the account the backend authenticated for that stream, and the world's
 * access rules must still allow it. Other connections (LAN, the host) are untouched, and vanilla's
 * own checks (bans, the integrated server's player limit) still run afterwards.
 */
@Mixin(PlayerList.class)
abstract class PlayerListMixin {
  @Inject(method = "canPlayerLogin", at = @At("HEAD"), cancellable = true)
  private void opendoors$checkFriend(
      SocketAddress address, NameAndId nameAndId, CallbackInfoReturnable<Component> info) {
    if (address instanceof PeerAddress stream) {
      Component refusal = FriendsHost.get().checkLogin(nameAndId.id(), stream);
      if (refusal != null) {
        info.setReturnValue(refusal);
      }
    }
  }
}
