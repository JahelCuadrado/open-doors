package com.einsteinmods.opendoors.core.signaling;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Optional;

/**
 * The backend's base URL and the two endpoints derived from it. Only {@code https} is accepted,
 * plus plain {@code http} to a loopback host for a local {@code wrangler dev}; no user info, query
 * or fragment.
 */
public record BackendEndpoint(URI base) {
  private static final String CHALLENGE_PATH = "/v1/auth/challenge";
  private static final String SOCKET_PATH = "/v1/ws";

  public static Optional<BackendEndpoint> parse(String text) {
    if (text == null || text.isBlank()) {
      return Optional.empty();
    }
    try {
      URI uri = new URI(text.strip());
      String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
      String host = uri.getHost();
      if (host == null
          || uri.getRawUserInfo() != null
          || uri.getRawQuery() != null
          || uri.getRawFragment() != null) {
        return Optional.empty();
      }
      boolean allowed = scheme.equals("https") || (scheme.equals("http") && isLoopback(host));
      if (!allowed) {
        return Optional.empty();
      }
      String path = uri.getRawPath() == null ? "" : uri.getRawPath();
      while (path.endsWith("/")) {
        path = path.substring(0, path.length() - 1);
      }
      return Optional.of(
          new BackendEndpoint(new URI(scheme, null, host, uri.getPort(), path, null, null)));
    } catch (URISyntaxException exception) {
      return Optional.empty();
    }
  }

  public URI challenge() {
    return base.resolve(base.getRawPath() + CHALLENGE_PATH);
  }

  /**
   * The WebSocket URL; {@code serverId} null means a dev identity (only accepted by a backend
   * started with DEV_AUTH=1).
   */
  public URI socket(String playerName, String serverId) {
    if (!Validation.isName(playerName) || (serverId != null && !Validation.isServerId(serverId))) {
      throw new IllegalArgumentException("invalid socket parameters");
    }
    String scheme = base.getScheme().equals("https") ? "wss" : "ws";
    String query =
        serverId == null
            ? "dev=1&name=" + playerName
            : "name=" + playerName + "&serverId=" + serverId;
    return URI.create(
        scheme + "://" + base.getRawAuthority() + base.getRawPath() + SOCKET_PATH + "?" + query);
  }

  private static boolean isLoopback(String host) {
    return host.equals("127.0.0.1") || host.equals("localhost") || host.equals("[::1]");
  }
}
