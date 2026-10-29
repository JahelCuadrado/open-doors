package com.einsteinmods.opendoors.core.signaling;

import java.util.Optional;

/** Which backend URL wins: dev system property, then the player's config file, then the build. */
public final class BackendUrls {
  private BackendUrls() {}

  /** The first non-blank, valid candidate; empty when none (online play stays off). */
  public static Optional<BackendEndpoint> resolve(
      String systemProperty, String configured, String buildDefault) {
    for (String candidate : new String[] {systemProperty, configured, buildDefault}) {
      if (candidate != null && !candidate.isBlank()) {
        return BackendEndpoint.parse(candidate);
      }
    }
    return Optional.empty();
  }
}
