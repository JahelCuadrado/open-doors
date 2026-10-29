package com.einsteinmods.opendoors.client.mixin;

import java.util.UUID;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.StringWidget;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Package-private members of the Friends List rows (vanilla {@code
 * AbstractFriendsEntryContainerWidget}).
 */
@Mixin(targets = "net.minecraft.client.gui.screens.friends.AbstractFriendsEntryContainerWidget")
public interface FriendEntryAccess {
  @Invoker("addChild")
  void opendoors$addChild(AbstractWidget child);

  @Invoker("playerId")
  UUID opendoors$playerId();

  @Accessor("playerName")
  String opendoors$playerName();

  @Accessor("nameWidget")
  StringWidget opendoors$nameWidget();
}
