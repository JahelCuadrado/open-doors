package com.einsteinmods.opendoors.client;

import com.einsteinmods.opendoors.client.config.OpenDoorsSettingsScreen;
import com.mojang.blaze3d.platform.InputConstants;
import java.util.List;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;

/**
 * Mod keys: "Join last invite" (K by default: toasts cannot be clicked) and an unbound key that
 * opens the settings.
 */
public final class OpenDoorsKeyMappings {
  private static final KeyMapping.Category CATEGORY =
      KeyMapping.Category.register(Identifier.fromNamespaceAndPath(OpenDoorsClient.MOD_ID, "main"));

  private final KeyMapping joinInvite =
      KeyMappingHelper.registerKeyMapping(
          new KeyMapping("key.opendoors.join_invite", InputConstants.KEY_K, CATEGORY));
  private final KeyMapping openSettings =
      KeyMappingHelper.registerKeyMapping(
          new KeyMapping(
              "key.opendoors.open_settings", InputConstants.UNKNOWN.getValue(), CATEGORY));

  /** Mappings shown in the settings screen, in display order. */
  public List<KeyMapping> configurable() {
    return List.of(joinInvite, openSettings);
  }

  public KeyMapping joinInvite() {
    return joinInvite;
  }

  /** Per client tick: one click counter read per mapping while its key is not pressed. */
  public void onClientTick(Minecraft minecraft) {
    while (joinInvite.consumeClick()) {
      OpenDoorsClient.online().joinLastInvite(minecraft);
    }
    while (openSettings.consumeClick()) {
      if (minecraft.gui.screen() == null) {
        minecraft.gui.setScreen(new OpenDoorsSettingsScreen(null, this));
      }
    }
  }
}
