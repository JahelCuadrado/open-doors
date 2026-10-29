package com.einsteinmods.opendoors.core.net;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteBuffer;

/**
 * Single-producer single-consumer byte ring between a Netty event loop and the network thread.
 * Copies only; never allocates after construction. ByteBuffer variants need heap buffers. Positions
 * only grow (no wrap of the counters in practice: 2^63 bytes).
 */
public final class ByteRing {
  private static final VarHandle HEAD;
  private static final VarHandle TAIL;
  private static final VarHandle CLOSED;

  static {
    try {
      MethodHandles.Lookup lookup = MethodHandles.lookup();
      HEAD = lookup.findVarHandle(ByteRing.class, "head", long.class);
      TAIL = lookup.findVarHandle(ByteRing.class, "tail", long.class);
      CLOSED = lookup.findVarHandle(ByteRing.class, "closed", boolean.class);
    } catch (ReflectiveOperationException exception) {
      throw new ExceptionInInitializerError(exception);
    }
  }

  private final byte[] data;
  private final int mask;

  /** Next position to read; written by the consumer only. */
  @SuppressWarnings("unused")
  private long head;

  /** Next position to write; written by the producer only. */
  @SuppressWarnings("unused")
  private long tail;

  @SuppressWarnings("unused")
  private boolean closed;

  /** {@code capacity} must be a power of two. */
  public ByteRing(int capacity) {
    if (Integer.bitCount(capacity) != 1) {
      throw new IllegalArgumentException("capacity must be a power of two");
    }
    data = new byte[capacity];
    mask = capacity - 1;
  }

  /** Producer: copies as much of {@code source} as fits; returns the bytes taken. */
  public int write(ByteBuffer source) {
    int count = write(source.array(), source.arrayOffset() + source.position(), source.remaining());
    source.position(source.position() + count);
    return count;
  }

  /** Producer: copies as much of {@code source[offset, offset + length)} as fits. */
  public int write(byte[] source, int offset, int length) {
    long writePosition = (long) TAIL.getOpaque(this);
    long readPosition = (long) HEAD.getAcquire(this);
    int free = data.length - (int) (writePosition - readPosition);
    int count = Math.min(free, length);
    int index = (int) writePosition & mask;
    int first = Math.min(count, data.length - index);
    System.arraycopy(source, offset, data, index, first);
    System.arraycopy(source, offset + first, data, 0, count - first);
    TAIL.setRelease(this, writePosition + count);
    return count;
  }

  /** Consumer: copies up to {@code target.remaining()} bytes; returns the bytes copied. */
  public int read(ByteBuffer target) {
    int count = read(target.array(), target.arrayOffset() + target.position(), target.remaining());
    target.position(target.position() + count);
    return count;
  }

  /** Consumer: copies up to {@code length} bytes into {@code target}; returns the count. */
  public int read(byte[] target, int offset, int length) {
    long readPosition = (long) HEAD.getOpaque(this);
    long writePosition = (long) TAIL.getAcquire(this);
    int count = Math.min((int) (writePosition - readPosition), length);
    int index = (int) readPosition & mask;
    int first = Math.min(count, data.length - index);
    System.arraycopy(data, index, target, offset, first);
    System.arraycopy(data, 0, target, offset + first, count - first);
    HEAD.setRelease(this, readPosition + count);
    return count;
  }

  /** Bytes waiting to be read (consumer view). */
  public int available() {
    return (int) ((long) TAIL.getAcquire(this) - (long) HEAD.getOpaque(this));
  }

  /** Free space (producer view). */
  public int free() {
    return data.length - (int) ((long) TAIL.getOpaque(this) - (long) HEAD.getAcquire(this));
  }

  /** Producer side is done; the consumer sees it once the ring is drained. */
  public void close() {
    CLOSED.setRelease(this, true);
  }

  public boolean isClosed() {
    return (boolean) CLOSED.getAcquire(this);
  }
}
