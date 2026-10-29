package com.einsteinmods.opendoors.config;

/**
 * Contents of {@code config/opendoors.json}.
 *
 * @param backendUrl base https URL of a self-hosted backend; blank uses the one built in
 * @param connectionDiagnostics write every connection step (with IP addresses) to the game log
 */
public record OpenDoorsSettings(
    int schemaVersion, String backendUrl, boolean connectionDiagnostics) {
  public static final int CURRENT_SCHEMA_VERSION = 1;
  public static final OpenDoorsSettings DEFAULTS =
      new OpenDoorsSettings(CURRENT_SCHEMA_VERSION, "", false);

  /** Missing fields (older or hand-edited files) fall back to defaults. */
  public OpenDoorsSettings sanitized() {
    return new OpenDoorsSettings(
        CURRENT_SCHEMA_VERSION,
        backendUrl == null ? "" : backendUrl.strip(),
        connectionDiagnostics);
  }
}
