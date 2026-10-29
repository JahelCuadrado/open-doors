package com.einsteinmods.opendoors.config;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/** Values baked in at build time from {@code gradle.properties} (resource expanded by Gradle). */
public final class BuildInfo {
  private static final String RESOURCE = "/opendoors-build.properties";
  private static final String BACKEND_URL = "backendUrl";

  private BuildInfo() {}

  /** The built-in backend URL; blank until the backend is deployed (see backend/DEPLOY.md). */
  public static String backendUrl() {
    Properties properties = new Properties();
    try (InputStream stream = BuildInfo.class.getResourceAsStream(RESOURCE)) {
      if (stream != null) {
        properties.load(stream);
      }
    } catch (IOException exception) {
      return "";
    }
    return properties.getProperty(BACKEND_URL, "");
  }
}
