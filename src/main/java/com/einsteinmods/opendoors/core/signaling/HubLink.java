package com.einsteinmods.opendoors.core.signaling;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;

/** The signaling side as the session coordinators see it: send, and a single control thread. */
public interface HubLink {
  /** Queues a message; completes with false when it could not be sent. */
  CompletableFuture<Boolean> send(HubMessage.Outbound message);

  /** Runs {@code task} on the control thread. */
  void execute(Runnable task);

  /** Runs {@code task} on the control thread after {@code delayMillis}. */
  Future<?> schedule(Runnable task, long delayMillis);
}
