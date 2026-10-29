package com.einsteinmods.opendoors.core.signaling;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;

class BackoffTest {
  @Test
  void doublesFromOneSecondUpToFiveMinutesWithJitter() {
    SplittableRandom random = new SplittableRandom(1);
    for (int i = 0; i < 1000; i++) {
      long first = Backoff.delayMillis(0, 0, random);
      assertTrue(first >= 800 && first <= 1200, "first " + first);
      long third = Backoff.delayMillis(2, 0, random);
      assertTrue(third >= 3200 && third <= 4800, "third " + third);
      long late = Backoff.delayMillis(40, 0, random);
      assertTrue(late >= 240_000 && late <= Backoff.MAX_MILLIS, "late " + late);
    }
  }

  @Test
  void retryAfterIsAFloor() {
    assertEquals(600_000, Backoff.delayMillis(0, 600_000, new SplittableRandom(2)));
  }
}
