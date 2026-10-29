package com.einsteinmods.opendoors.core.net;

import java.nio.ByteBuffer;

/**
 * The local side of a peer stream (the game's connection), driven by the network thread: KCP pulls
 * what the game wrote and pushes what the peer sent. Both methods must not block.
 */
public interface StreamEndpoint {
  /** Copies bytes the game wants to send into {@code target}; -1 once the game side closed. */
  int read(ByteBuffer target);

  /** Hands bytes from the peer to the game; returns how many were taken (0 when full). */
  int write(ByteBuffer source);

  /** The peer stream is gone (closed, timed out or failed); release the game side. */
  void close();
}
