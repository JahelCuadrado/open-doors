package com.einsteinmods.opendoors.core.online;

import com.einsteinmods.opendoors.core.net.RelayCredentials;
import com.einsteinmods.opendoors.core.signaling.HubMessage.TurnCredentials;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Turns the hub's TURN credentials into a resolved relay server (blocking DNS lookup). */
public final class RelayResolver {
  private static final Pattern URL = Pattern.compile("turn:([a-z0-9.-]+):([0-9]+)\\?transport=udp");
  private static final int PREFERRED_PORT = 3478;

  private RelayResolver() {}

  /** The relay to use, or null when no url resolves. Prefers the standard port 3478. */
  public static RelayCredentials resolve(TurnCredentials credentials) {
    RelayCredentials fallback = null;
    for (String url : credentials.urls()) {
      Matcher matcher = URL.matcher(url);
      if (!matcher.matches()) {
        continue;
      }
      int port = Integer.parseInt(matcher.group(2));
      try {
        InetAddress address = firstIpv4(InetAddress.getAllByName(matcher.group(1)));
        RelayCredentials relay =
            new RelayCredentials(
                new InetSocketAddress(address, port),
                credentials.username(),
                credentials.credential());
        if (port == PREFERRED_PORT) {
          return relay;
        }
        fallback = fallback == null ? relay : fallback;
      } catch (UnknownHostException exception) {
        // Try the next url.
      }
    }
    return fallback;
  }

  /** STUN server address, or null when it does not resolve. */
  public static InetSocketAddress resolveStun(String host, int port) {
    try {
      return new InetSocketAddress(firstIpv4(InetAddress.getAllByName(host)), port);
    } catch (UnknownHostException exception) {
      return null;
    }
  }

  private static InetAddress firstIpv4(InetAddress[] addresses) {
    for (InetAddress address : addresses) {
      if (address instanceof java.net.Inet4Address) {
        return address;
      }
    }
    return addresses[0];
  }
}
