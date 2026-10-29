package com.einsteinmods.opendoors.client;

import com.einsteinmods.opendoors.client.host.FriendsHost;
import com.einsteinmods.opendoors.config.BuildInfo;
import com.einsteinmods.opendoors.config.JsonConfigFile;
import com.einsteinmods.opendoors.config.OpenDoorsSettings;
import com.einsteinmods.opendoors.core.net.ConnectionLog;
import com.einsteinmods.opendoors.core.signaling.BackendEndpoint;
import com.einsteinmods.opendoors.core.signaling.BackendUrls;
import java.util.Optional;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.server.IntegratedServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Client entrypoint: settings, keys and the online connection's lifecycle. */
public final class OpenDoorsClient implements ClientModInitializer {
  public static final String MOD_ID = "opendoors";
  private static final String SETTINGS_FILE = "opendoors.json";
  private static final String DIAGNOSTICS_LOGGER = "Open Doors diagnostics";

  /** Dev runs turn diagnostics on without touching the settings file. */
  private static final String DIAGNOSTICS_PROPERTY = "opendoors.diagnostics";

  /** Dev-only overrides, set by the Gradle dev runs (see build.gradle). */
  private static final String BACKEND_URL_PROPERTY = "opendoors.backendUrl";

  private static final String DEV_AUTH_PROPERTY = "opendoors.devAuth";

  /** Ticks between eligibility checks until online play starts (the Friends List loads late). */
  private static final int START_CHECK_TICKS = 100;

  private static JsonConfigFile<OpenDoorsSettings> settingsFile;
  private static OpenDoorsSettings settings = OpenDoorsSettings.DEFAULTS;
  private static OpenDoorsKeyMappings keyMappings;
  private static OnlineClient online;
  private static int ticksUntilStartCheck;

  @Override
  public void onInitializeClient() {
    settingsFile =
        new JsonConfigFile<>(
            FabricLoader.getInstance().getConfigDir().resolve(SETTINGS_FILE),
            OpenDoorsSettings.class,
            OpenDoorsSettings.DEFAULTS);
    settings = settingsFile.load().sanitized();
    applyDiagnostics();
    keyMappings = new OpenDoorsKeyMappings();
    ClientTickEvents.END_CLIENT_TICK.register(OpenDoorsClient::onClientTick);
    ClientPlayConnectionEvents.DISCONNECT.register(
        (listener, minecraft) -> minecraft.execute(() -> online().onLeftWorld()));
    ServerLifecycleEvents.SERVER_STOPPING.register(
        server -> {
          if (server instanceof IntegratedServer integrated) {
            FriendsHost.get().onServerStopping(integrated);
          }
        });
  }

  private static void onClientTick(Minecraft minecraft) {
    keyMappings.onClientTick(minecraft);
    if (--ticksUntilStartCheck <= 0) {
      ticksUntilStartCheck = START_CHECK_TICKS;
      online().startIfEligible();
    }
  }

  public static OnlineClient online() {
    if (online == null) {
      online = new OnlineClient(Minecraft.getInstance());
    }
    return online;
  }

  public static OpenDoorsKeyMappings keyMappings() {
    return keyMappings;
  }

  public static OpenDoorsSettings settings() {
    return settings;
  }

  public static void saveSettings(OpenDoorsSettings updated) {
    settings = updated.sanitized();
    settingsFile.save(settings);
    applyDiagnostics();
  }

  /** Routes connection diagnostics to the game log while the setting is on. */
  private static void applyDiagnostics() {
    boolean on = settings.connectionDiagnostics() || Boolean.getBoolean(DIAGNOSTICS_PROPERTY);
    if (on == ConnectionLog.on()) {
      return;
    }
    if (on) {
      Logger logger = LoggerFactory.getLogger(DIAGNOSTICS_LOGGER);
      ConnectionLog.setSink(logger::info);
      ConnectionLog.log(
          "connection diagnostics on (Open Doors "
              + FabricLoader.getInstance()
                  .getModContainer(MOD_ID)
                  .map(mod -> mod.getMetadata().getVersion().getFriendlyString())
                  .orElse("?")
              + ", Java "
              + Runtime.version()
              + ", "
              + System.getProperty("os.name")
              + " "
              + System.getProperty("os.version")
              + ", preferIPv4Stack="
              + Boolean.getBoolean("java.net.preferIPv4Stack")
              + ")");
    } else {
      ConnectionLog.log("connection diagnostics off");
      ConnectionLog.setSink(null);
    }
  }

  /** The backend to use, or empty while none is configured (online play then stays off). */
  public static Optional<BackendEndpoint> backend() {
    return BackendUrls.resolve(
        System.getProperty(BACKEND_URL_PROPERTY), settings.backendUrl(), BuildInfo.backendUrl());
  }

  /** Dev runs talk to a local backend with offline identities (backend DEV_AUTH=1). */
  public static boolean devAuth() {
    return Boolean.getBoolean(DEV_AUTH_PROPERTY);
  }
}
