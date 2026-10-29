package com.einsteinmods.opendoors.core.signaling;

import java.util.Locale;

/** Why a host refused (or the hub dropped) a join request. */
public enum JoinRejectReason {
  DENIED,
  FULL,
  BLOCKED,
  CLOSED,
  BUSY;

  public String wireName() {
    return name().toLowerCase(Locale.ROOT);
  }

  public static JoinRejectReason fromWireName(String value) {
    for (JoinRejectReason reason : values()) {
      if (reason.wireName().equals(value)) {
        return reason;
      }
    }
    return null;
  }
}
