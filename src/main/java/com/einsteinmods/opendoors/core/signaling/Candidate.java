package com.einsteinmods.opendoors.core.signaling;

import java.util.Locale;

/**
 * One ICE candidate as exchanged through the hub (RFC 8445 §5.1.1): where a peer may be reached.
 *
 * @param kind host (local interface), srflx (seen by STUN) or relay (TURN allocation)
 * @param address IPv4 or IPv6 literal
 * @param port UDP port
 */
public record Candidate(Kind kind, String address, int port) {
  public enum Kind {
    HOST,
    SRFLX,
    RELAY;

    /** Wire name ("host", "srflx", "relay"). */
    public String wireName() {
      return name().toLowerCase(Locale.ROOT);
    }

    /** The kind for a wire name, or null when it is not one of the three. */
    public static Kind fromWireName(String value) {
      for (Kind kind : values()) {
        if (kind.wireName().equals(value)) {
          return kind;
        }
      }
      return null;
    }
  }

  /** Compact form for diagnostics: {@code srflx 203.0.113.9:50000}. */
  @Override
  public String toString() {
    return kind.wireName()
        + " "
        + (address.indexOf(':') >= 0 ? "[" + address + "]" : address)
        + ":"
        + port;
  }

  public Candidate {
    if (kind == null || !Validation.isIpLiteral(address) || !Validation.isPort(port)) {
      throw new IllegalArgumentException("invalid candidate");
    }
  }
}
