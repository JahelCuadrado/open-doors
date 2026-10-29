package com.einsteinmods.opendoors.core.net;

import java.nio.ByteBuffer;

/**
 * {@link StreamEndpoint} over two {@link ByteRing}s, for a game connection that lives on another
 * thread (the host's Netty channel). {@code onReadable} runs on the network thread when new peer
 * bytes are waiting; {@code onWritable} when ring space was freed for the game.
 */
public final class RingEndpoint implements StreamEndpoint {
  private final ByteRing toPeer;
  private final ByteRing fromPeer;
  private final Runnable onReadable;
  private final Runnable onWritable;
  private final Runnable onClose;

  public RingEndpoint(
      ByteRing toPeer,
      ByteRing fromPeer,
      Runnable onReadable,
      Runnable onWritable,
      Runnable onClose) {
    this.toPeer = toPeer;
    this.fromPeer = fromPeer;
    this.onReadable = onReadable;
    this.onWritable = onWritable;
    this.onClose = onClose;
  }

  @Override
  public int read(ByteBuffer target) {
    int count = toPeer.read(target);
    if (count > 0) {
      onWritable.run();
      return count;
    }
    return toPeer.isClosed() && toPeer.available() == 0 ? -1 : 0;
  }

  @Override
  public int write(ByteBuffer source) {
    int count = fromPeer.write(source);
    if (count > 0) {
      onReadable.run();
    }
    return count;
  }

  @Override
  public void close() {
    fromPeer.close();
    onClose.run();
  }
}
