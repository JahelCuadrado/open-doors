package com.einsteinmods.opendoors.core.online;

import com.einsteinmods.opendoors.core.signaling.HostMode;

/** Who can join the open world over the internet, chosen in World Options next to LAN. */
public enum OnlineMode {
  OFF,
  /** Any friend, plus invited players. */
  FRIENDS,
  /** Only invited players; other friends do not see the world. */
  INVITED;

  /** The hub's mode, or null when off. */
  public HostMode hostMode() {
    return switch (this) {
      case OFF -> null;
      case FRIENDS -> HostMode.FRIENDS;
      case INVITED -> HostMode.INVITED;
    };
  }
}
