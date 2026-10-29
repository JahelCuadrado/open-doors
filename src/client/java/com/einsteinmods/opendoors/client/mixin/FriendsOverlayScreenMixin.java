package com.einsteinmods.opendoors.client.mixin;

import com.einsteinmods.opendoors.client.OpenDoorsClient;
import com.einsteinmods.opendoors.client.screen.FriendsListHosting;
import java.util.List;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.friends.FriendsOverlayScreen;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The Friends List ({@code FriendsOverlayScreen}): in this player's own world a host row ("My
 * world", "Players…") sits above the tabs' content, which give up its height ({@code
 * repositionElements} sizes both tabs); friends with a joinable world are listed first ({@code
 * populateLists} hands the presence-sorted entries to {@code FriendsTab#updateEntries}); and
 * opening it refreshes whose worlds are open.
 */
@Mixin(FriendsOverlayScreen.class)
abstract class FriendsOverlayScreenMixin extends Screen {
  @Shadow private @Nullable LinearLayout layout;

  @Unique private int opendoors$hostRowHeight;

  private FriendsOverlayScreenMixin() {
    super(Component.empty());
  }

  /** Before {@code init} adds the tab content to the layout: the host row goes first. */
  @Inject(
      method = "init",
      at =
          @At(
              value = "INVOKE",
              target =
                  "Lnet/minecraft/client/gui/layouts/LinearLayout;addChild(Lnet/minecraft/client/gui/layouts/LayoutElement;)Lnet/minecraft/client/gui/layouts/LayoutElement;",
              ordinal = 0))
  private void opendoors$addHostRow(CallbackInfo info) {
    LinearLayout row = FriendsListHosting.hostRow(minecraft, this);
    opendoors$hostRowHeight = row == null ? 0 : FriendsListHosting.HOST_ROW_HEIGHT;
    if (row != null && layout != null) {
      layout.addChild(row, settings -> settings.paddingBottom(FriendsListHosting.HOST_ROW_GAP));
    }
  }

  @Inject(method = "init", at = @At("TAIL"))
  private void opendoors$queryDirectory(CallbackInfo info) {
    OpenDoorsClient.online().queryDirectory(true);
  }

  @ModifyArg(
      method = "repositionElements",
      at =
          @At(
              value = "INVOKE",
              target = "Lnet/minecraft/client/gui/screens/friends/FriendsTab;setHeight(I)V"))
  private int opendoors$shrinkFriendsTab(int height) {
    return height - opendoors$hostRowHeight;
  }

  @ModifyArg(
      method = "repositionElements",
      at =
          @At(
              value = "INVOKE",
              target = "Lnet/minecraft/client/gui/screens/friends/PendingTab;setHeight(I)V"))
  private int opendoors$shrinkPendingTab(int height) {
    return height - opendoors$hostRowHeight;
  }

  @ModifyArg(
      method = "populateLists",
      at =
          @At(
              value = "INVOKE",
              target =
                  "Lnet/minecraft/client/gui/screens/friends/FriendsTab;updateEntries(Ljava/util/List;)V"))
  private List<AbstractWidget> opendoors$joinableFirst(List<AbstractWidget> entries) {
    return FriendsListHosting.joinableFirst(
        entries, entry -> ((FriendEntryAccess) entry).opendoors$playerId());
  }
}
