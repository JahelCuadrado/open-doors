package com.einsteinmods.opendoors.core.signaling;

import java.util.random.RandomGenerator;

/**
 * Reconnect delays: doubling from 1 s up to 5 min, each with ±20 % jitter so clients that lost the
 * hub together do not come back together.
 */
public final class Backoff {
  public static final long INITIAL_MILLIS = 1_000;
  public static final long MAX_MILLIS = 300_000;
  private static final int JITTER_PERCENT = 20;
  private static final int PERCENT = 100;
  private static final int MAX_SHIFT = 20;

  private Backoff() {}

  /**
   * Delay before reconnect attempt {@code attempt} (0 for the first retry), never below {@code
   * minimumMillis} (a server Retry-After).
   */
  public static long delayMillis(int attempt, long minimumMillis, RandomGenerator random) {
    long base = Math.min(MAX_MILLIS, INITIAL_MILLIS << Math.clamp(attempt, 0, MAX_SHIFT));
    long spread = base * JITTER_PERCENT / PERCENT;
    long jittered = base - spread + random.nextLong(2 * spread + 1);
    return Math.max(Math.min(jittered, MAX_MILLIS), minimumMillis);
  }
}
