package com.einsteinmods.opendoors.client.mixin;

import com.einsteinmods.opendoors.client.HostingDirectory;
import com.einsteinmods.opendoors.client.OpenDoorsClient;
import com.einsteinmods.opendoors.client.screen.FriendsListHosting;
import com.einsteinmods.opendoors.client.screen.FriendsListHosting.InviteState;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.SpriteIconButton;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.WidgetSprites;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Friends List rows ({@code FriendEntry}): "Invite" left of vanilla's remove button (greyed out
 * with the reason outside this player's own world), then "Join" and "Decline" when that friend's
 * world is joinable or they sent an invite, and a green dot after the name of a joinable friend.
 * Built once per row; per frame it reads a few fields, places the buttons as {@code
 * FriendEntry#extractWidgetRenderState} places the remove button, and allocates nothing of its own.
 */
@Mixin(targets = "net.minecraft.client.gui.screens.friends.FriendEntry")
abstract class FriendEntryMixin extends AbstractWidget {
  @Unique private static final int BUTTON_SIZE = 20;
  @Unique private static final int GAP = 4;
  @Unique private static final int ICON_SIZE = 18;
  @Unique private static final int INVITE_ICON_WIDTH = 13;
  @Unique private static final int INVITE_ICON_HEIGHT = 11;

  /** Vanilla's left edge of the name: face (24) plus padding (4). */
  @Unique private static final int NAME_OFFSET = 28;

  @Unique private static final int DOT_SIZE = 5;
  @Unique private static final int DOT_GAP = 4;
  @Unique private static final int DOT_BORDER_COLOR = 0xFF0A3D0A;
  @Unique private static final int DOT_COLOR = 0xFF3CE03C;

  @Unique
  private static final WidgetSprites INVITE_SPRITE =
      new WidgetSprites(Identifier.withDefaultNamespace("friends/send_request"));

  @Unique
  private static final WidgetSprites JOIN_SPRITE =
      new WidgetSprites(
          Identifier.withDefaultNamespace("friends/accept"),
          Identifier.withDefaultNamespace("friends/accept_highlighted"));

  @Unique
  private static final WidgetSprites DECLINE_SPRITE =
      new WidgetSprites(
          Identifier.withDefaultNamespace("friends/reject"),
          Identifier.withDefaultNamespace("friends/reject_highlighted"));

  @Unique
  private static final Tooltip INVITE_TOOLTIP =
      Tooltip.create(Component.translatable("opendoors.friends.invite"));

  @Unique
  private static final Tooltip NO_WORLD_TOOLTIP =
      Tooltip.create(Component.translatable("opendoors.friends.invite.no_world"));

  @Unique
  private static final Tooltip OFFLINE_TOOLTIP =
      Tooltip.create(Component.translatable("opendoors.online.unavailable"));

  @Unique private UUID opendoors$friend;
  @Unique private SpriteIconButton opendoors$invite;
  @Unique private SpriteIconButton opendoors$join;
  @Unique private SpriteIconButton opendoors$decline;
  @Unique private InviteState opendoors$inviteState;
  @Unique private int opendoors$nameWidth;

  private FriendEntryMixin() {
    super(0, 0, 0, 0, Component.empty());
  }

  @Inject(method = "<init>", at = @At("TAIL"))
  private void opendoors$addButtons(CallbackInfo info) {
    FriendEntryAccess entry = (FriendEntryAccess) (Object) this;
    UUID friend = entry.opendoors$playerId();
    String name = entry.opendoors$playerName();
    Minecraft minecraft = Minecraft.getInstance();
    opendoors$friend = friend;
    opendoors$invite =
        opendoors$button(
            "opendoors.friends.invite",
            "opendoors.friends.narration.invite",
            name,
            INVITE_SPRITE,
            INVITE_ICON_WIDTH,
            INVITE_ICON_HEIGHT,
            () -> FriendsListHosting.invite(minecraft, friend, name));
    opendoors$join =
        opendoors$button(
            "gui.friends.join",
            "opendoors.friends.narration.join",
            name,
            JOIN_SPRITE,
            ICON_SIZE,
            ICON_SIZE,
            () -> OpenDoorsClient.online().join(minecraft, friend, name));
    opendoors$decline =
        opendoors$button(
            "opendoors.friends.decline",
            "opendoors.friends.narration.decline",
            name,
            DECLINE_SPRITE,
            ICON_SIZE,
            ICON_SIZE,
            () -> OpenDoorsClient.online().directory().decline(friend));
    entry.opendoors$addChild(opendoors$invite);
    entry.opendoors$addChild(opendoors$join);
    entry.opendoors$addChild(opendoors$decline);
    opendoors$updateButtons();
  }

  /** Before vanilla draws: which buttons show, so the name and status make room for them. */
  @Inject(method = "extractWidgetRenderState", at = @At("HEAD"))
  private void opendoors$updateBeforeDrawing(
      GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick, CallbackInfo info) {
    opendoors$updateButtons();
    StringWidget nameWidget = ((FriendEntryAccess) (Object) this).opendoors$nameWidget();
    int dot = opendoors$join.visible ? DOT_GAP + DOT_SIZE : 0;
    int nameWidth = Math.max(1, opendoors$buttonsLeft() - GAP - dot - getX() - NAME_OFFSET);
    if (nameWidth != opendoors$nameWidth) {
      opendoors$nameWidth = nameWidth;
      nameWidget.setMaxWidth(nameWidth);
      // StringWidget#setMaxWidth keeps the cached width; setMessage marks it stale.
      nameWidget.setMessage(nameWidget.getMessage());
    }
  }

  @Inject(method = "extractWidgetRenderState", at = @At("TAIL"))
  private void opendoors$extractButtons(
      GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick, CallbackInfo info) {
    int y = getY() + (getHeight() - BUTTON_SIZE) / 2;
    int x = getX() + getWidth() - BUTTON_SIZE;
    x = opendoors$place(opendoors$invite, x, y, graphics, mouseX, mouseY, partialTick);
    x = opendoors$place(opendoors$join, x, y, graphics, mouseX, mouseY, partialTick);
    opendoors$place(opendoors$decline, x, y, graphics, mouseX, mouseY, partialTick);
    if (opendoors$join.visible) {
      StringWidget name = ((FriendEntryAccess) (Object) this).opendoors$nameWidget();
      int dotX = name.getRight() + DOT_GAP;
      int dotY = name.getY() + (name.getHeight() - DOT_SIZE) / 2;
      graphics.fill(dotX, dotY, dotX + DOT_SIZE, dotY + DOT_SIZE, DOT_BORDER_COLOR);
      graphics.fill(dotX + 1, dotY + 1, dotX + DOT_SIZE - 1, dotY + DOT_SIZE - 1, DOT_COLOR);
    }
  }

  /** Keeps the presence text clear of the extra buttons. */
  @ModifyArg(
      method = "extractWidgetRenderState",
      at =
          @At(
              value = "INVOKE",
              target =
                  "Lnet/minecraft/client/gui/components/StringWidget;setMaxWidth(ILnet/minecraft/client/gui/components/StringWidget$TextOverflow;)Lnet/minecraft/client/gui/components/StringWidget;"),
      index = 0)
  private int opendoors$narrowStatus(int width) {
    return Math.max(0, width - (getX() + getWidth() - BUTTON_SIZE - opendoors$buttonsLeft()));
  }

  @Unique
  private void opendoors$updateButtons() {
    HostingDirectory directory = OpenDoorsClient.online().directory();
    opendoors$join.visible = directory.isJoinable(opendoors$friend);
    opendoors$decline.visible = directory.hasInvite(opendoors$friend);
    InviteState state = FriendsListHosting.inviteState(Minecraft.getInstance());
    if (state != opendoors$inviteState) {
      opendoors$inviteState = state;
      opendoors$invite.active = state == InviteState.AVAILABLE;
      opendoors$invite.setTooltip(
          switch (state) {
            case AVAILABLE -> INVITE_TOOLTIP;
            case NO_WORLD -> NO_WORLD_TOOLTIP;
            case OFFLINE -> OFFLINE_TOOLTIP;
          });
    }
  }

  /** Left edge of the leftmost visible button (vanilla's remove button included). */
  @Unique
  private int opendoors$buttonsLeft() {
    int buttons = 2;
    if (opendoors$join.visible) {
      buttons++;
    }
    if (opendoors$decline.visible) {
      buttons++;
    }
    return getX() + getWidth() - BUTTON_SIZE - (buttons - 1) * (BUTTON_SIZE + GAP);
  }

  /** Draws {@code button} left of {@code right} when visible; returns the next free x. */
  @Unique
  private static int opendoors$place(
      SpriteIconButton button,
      int right,
      int y,
      GuiGraphicsExtractor graphics,
      int mouseX,
      int mouseY,
      float partialTick) {
    if (!button.visible) {
      return right;
    }
    int x = right - BUTTON_SIZE - GAP;
    button.setPosition(x, y);
    button.extractRenderState(graphics, mouseX, mouseY, partialTick);
    return x;
  }

  @Unique
  private static SpriteIconButton opendoors$button(
      String labelKey,
      String narrationKey,
      String name,
      WidgetSprites sprite,
      int iconWidth,
      int iconHeight,
      Runnable action) {
    Component label = Component.translatable(labelKey);
    return SpriteIconButton.builder(label, button -> action.run(), true)
        .size(BUTTON_SIZE, BUTTON_SIZE)
        .sprite(sprite, iconWidth, iconHeight)
        .tooltip(label)
        .narration(narration -> Component.translatable(narrationKey, name))
        .build();
  }
}
