package com.einsteinmods.opendoors.core.signaling;

import java.util.Locale;

/** Who may join an online world: any friend, or invited players only. */
public enum HostMode {
  /** Any friend (plus invited players). */
  FRIENDS,
  /** Only invited players; other friends' requests wait for the host's approval. */
  INVITED;

  public String wireName() {
    return name().toLowerCase(Locale.ROOT);
  }
}
