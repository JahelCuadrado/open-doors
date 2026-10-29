package com.einsteinmods.opendoors.host;

import com.einsteinmods.opendoors.core.net.ByteRing;
import com.einsteinmods.opendoors.core.net.NetLoop;
import com.einsteinmods.opendoors.core.net.RingEndpoint;
import com.einsteinmods.opendoors.core.net.StreamEndpoint;
import io.netty.buffer.ByteBuf;
import io.netty.channel.AbstractChannel;
import io.netty.channel.ChannelConfig;
import io.netty.channel.ChannelMetadata;
import io.netty.channel.ChannelOutboundBuffer;
import io.netty.channel.ChannelPromise;
import io.netty.channel.DefaultChannelConfig;
import io.netty.channel.EventLoop;
import java.net.SocketAddress;
import java.nio.channels.UnsupportedAddressTypeException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Netty channel of one friend's connection on the host: the integrated server's pipeline reads and
 * writes it like an accepted TCP socket, while the bytes travel through two {@link ByteRing}s to
 * and from the network thread's peer stream. It never registers with an I/O handler, so any event
 * loop can run it (it uses the loop only as its executor). After construction it copies through
 * reused buffers and allocates only pooled {@link ByteBuf}s.
 */
public final class PeerChannel extends AbstractChannel {
  private static final ChannelMetadata METADATA = new ChannelMetadata(false);
  private static final int RING_BYTES = 1 << 20;
  private static final int CHUNK_BYTES = 16 * 1024;
  private static final String DECRYPT_HANDLER = "decrypt";

  private final ChannelConfig config = new DefaultChannelConfig(this);
  private final NetLoop loop;
  private final PeerAddress remote;
  private final ByteRing toPeer = new ByteRing(RING_BYTES);
  private final ByteRing fromPeer = new ByteRing(RING_BYTES);
  private final byte[] writeChunk = new byte[CHUNK_BYTES];
  private final byte[] readChunk = new byte[CHUNK_BYTES];
  private final AtomicBoolean readScheduled = new AtomicBoolean();
  private final AtomicBoolean writeBlocked = new AtomicBoolean();
  private final Runnable readTask = this::readFromPeer;
  private final Runnable flushTask = this::flush;
  private final StreamEndpoint endpoint;
  private volatile boolean open = true;
  private boolean readRequested;

  public PeerChannel(NetLoop loop, PeerAddress remote) {
    super(null);
    this.loop = loop;
    this.remote = remote;
    this.endpoint =
        new RingEndpoint(
            toPeer, fromPeer, this::scheduleRead, this::onRingSpace, this::scheduleRead);
  }

  /** The friend this channel carries. */
  public PeerAddress peer() {
    return remote;
  }

  /** Whether vanilla login switched the stream to AES ({@code Connection#setEncryptionKey}). */
  public boolean isEncrypted() {
    return pipeline().get(DECRYPT_HANDLER) != null;
  }

  /** The network thread's side of this channel. */
  public StreamEndpoint endpoint() {
    return endpoint;
  }

  @Override
  public ChannelConfig config() {
    return config;
  }

  @Override
  public boolean isOpen() {
    return open;
  }

  @Override
  public boolean isActive() {
    return open;
  }

  @Override
  public ChannelMetadata metadata() {
    return METADATA;
  }

  @Override
  protected AbstractUnsafe newUnsafe() {
    return new AbstractUnsafe() {
      @Override
      public void connect(
          SocketAddress remoteAddress, SocketAddress localAddress, ChannelPromise promise) {
        promise.setFailure(new UnsupportedAddressTypeException());
      }
    };
  }

  @Override
  protected boolean isCompatible(EventLoop eventLoop) {
    return true;
  }

  @Override
  protected void doRegister(ChannelPromise promise) {
    promise.setSuccess();
  }

  @Override
  protected SocketAddress localAddress0() {
    return remote;
  }

  @Override
  protected SocketAddress remoteAddress0() {
    return remote;
  }

  @Override
  protected void doBind(SocketAddress localAddress) {
    throw new UnsupportedAddressTypeException();
  }

  @Override
  protected void doDisconnect() {
    doClose();
  }

  @Override
  protected void doClose() {
    if (open) {
      open = false;
      toPeer.close();
      loop.wakeup();
    }
  }

  @Override
  protected void doBeginRead() {
    readRequested = true;
    if (fromPeer.available() > 0 || fromPeer.isClosed()) {
      scheduleRead();
    }
  }

  @Override
  protected void doWrite(ChannelOutboundBuffer outbound) {
    boolean wrote = false;
    while (true) {
      Object message = outbound.current();
      if (message == null) {
        break;
      }
      if (!(message instanceof ByteBuf buffer)) {
        outbound.remove(new UnsupportedOperationException("only bytes"));
        continue;
      }
      int readable = buffer.readableBytes();
      if (readable == 0) {
        outbound.remove();
        continue;
      }
      int chunk = Math.min(readable, CHUNK_BYTES);
      buffer.getBytes(buffer.readerIndex(), writeChunk, 0, chunk);
      int written = toPeer.write(writeChunk, 0, chunk);
      if (written > 0) {
        outbound.removeBytes(written);
        wrote = true;
      }
      if (written < chunk) {
        // Ring full: the network thread flushes again once it drained some.
        writeBlocked.set(true);
        if (toPeer.free() > 0 && writeBlocked.compareAndSet(true, false)) {
          continue;
        }
        break;
      }
    }
    if (wrote) {
      loop.wakeup();
    }
  }

  /** Network thread: bytes left the outbound ring. */
  private void onRingSpace() {
    if (writeBlocked.compareAndSet(true, false)) {
      eventLoop().execute(flushTask);
    }
  }

  /** Network thread: peer bytes (or the end of the stream) are waiting. */
  private void scheduleRead() {
    // Before registration there is no loop yet: doBeginRead, which runs once registered, sees the
    // waiting bytes (the ring write happens before this volatile read of the registration flag).
    if (isRegistered() && readScheduled.compareAndSet(false, true)) {
      eventLoop().execute(readTask);
    }
  }

  private void readFromPeer() {
    readScheduled.set(false);
    if (!open) {
      return;
    }
    boolean read = false;
    while (readRequested || config.isAutoRead()) {
      int available = fromPeer.available();
      if (available == 0) {
        break;
      }
      int count = fromPeer.read(readChunk, 0, Math.min(available, CHUNK_BYTES));
      ByteBuf buffer = config.getAllocator().ioBuffer(count);
      buffer.writeBytes(readChunk, 0, count);
      readRequested = false;
      read = true;
      pipeline().fireChannelRead(buffer);
    }
    if (read) {
      pipeline().fireChannelReadComplete();
    }
    if (fromPeer.isClosed() && fromPeer.available() == 0) {
      close();
    } else if (fromPeer.available() > 0 && (readRequested || config.isAutoRead())) {
      scheduleRead();
    }
  }
}
