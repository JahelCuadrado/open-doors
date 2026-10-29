package com.einsteinmods.opendoors.core.net;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.StandardSocketOptions;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;

/**
 * The joiner's end: a TCP listener on 127.0.0.1 that accepts exactly one connection (the game's own
 * client connection, so the vanilla login with encryption runs unchanged end to end) and bridges it
 * to the peer stream. Served by the network thread's selector; no thread of its own.
 */
public final class LoopbackProxy implements StreamEndpoint, SelectableHandler {
  private final NetLoop loop;
  private final ServerSocketChannel server;
  private final int port;
  private SocketChannel client;
  private SelectionKey clientKey;
  private boolean closed;
  private boolean peerClosed;

  private LoopbackProxy(NetLoop loop, ServerSocketChannel server, int port) {
    this.loop = loop;
    this.server = server;
    this.port = port;
  }

  /** Network thread: listens on an ephemeral loopback port. */
  public static LoopbackProxy open(NetLoop loop) throws IOException {
    ServerSocketChannel server = ServerSocketChannel.open();
    server.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 1);
    int port = ((InetSocketAddress) server.getLocalAddress()).getPort();
    LoopbackProxy proxy = new LoopbackProxy(loop, server, port);
    loop.register(server, SelectionKey.OP_ACCEPT, proxy);
    return proxy;
  }

  public int port() {
    return port;
  }

  @Override
  public void onReady(SelectionKey key) throws IOException {
    if (key.isAcceptable()) {
      SocketChannel accepted = server.accept();
      if (accepted == null) {
        return;
      }
      if (client != null || closed) {
        accepted.close();
        return;
      }
      client = accepted;
      client.setOption(StandardSocketOptions.TCP_NODELAY, true);
      client.configureBlocking(false);
      clientKey = client.register(key.selector(), SelectionKey.OP_READ, this);
      // One connection only: stop listening.
      key.cancel();
      server.close();
    } else if (key.isWritable()) {
      // Space again: the session's next pump retries the pending bytes.
      key.interestOps(SelectionKey.OP_READ);
    }
    // Readable: the loop pumps every session after handling keys.
  }

  @Override
  public int read(ByteBuffer target) {
    if (closed) {
      return -1;
    }
    if (client == null) {
      return 0;
    }
    try {
      int count = client.read(target);
      return count < 0 ? -1 : count;
    } catch (IOException exception) {
      return -1;
    }
  }

  @Override
  public int write(ByteBuffer source) {
    if (client == null || closed) {
      // The game has not connected yet: keep the bytes in KCP until it does.
      return 0;
    }
    try {
      int count = client.write(source);
      if (source.hasRemaining() && clientKey.isValid()) {
        clientKey.interestOps(SelectionKey.OP_READ | SelectionKey.OP_WRITE);
      }
      return count;
    } catch (IOException exception) {
      close();
      return 0;
    }
  }

  @Override
  public void close() {
    if (closed) {
      return;
    }
    closed = true;
    peerClosed = true;
    try {
      server.close();
      if (client != null) {
        client.close();
      }
    } catch (IOException exception) {
      // Already closing.
    }
    loop.wakeup();
  }

  /** Whether the peer stream ended (the game sees its connection closed). */
  public boolean isPeerClosed() {
    return peerClosed;
  }
}
