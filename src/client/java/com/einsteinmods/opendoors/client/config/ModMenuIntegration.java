package com.einsteinmods.opendoors.client.config;

import com.einsteinmods.opendoors.client.OpenDoorsClient;
import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;

/** Mod Menu "Configure" button; only loaded when Mod Menu is installed. */
public final class ModMenuIntegration implements ModMenuApi {
  @Override
  public ConfigScreenFactory<?> getModConfigScreenFactory() {
    return parent -> new OpenDoorsSettingsScreen(parent, OpenDoorsClient.keyMappings());
  }
}
