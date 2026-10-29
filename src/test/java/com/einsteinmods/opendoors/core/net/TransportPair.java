package com.einsteinmods.opendoors.core.net;

import com.einsteinmods.opendoors.core.signaling.Candidate;
import com.einsteinmods.opendoors.core.signaling.IceParameters;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.UnaryOperator;

/**
 * Wires a host and a joiner {@link NetLoop} together in one process, exchanging ICE parameters
 * directly (the hub's role), optionally through an {@link ImpairedLink}. The host side echoes every
 * byte back; the joiner side is a real TCP socket through the {@link LoopbackProxy}.
 */
public final class TransportPair implements AutoCloseable {
  public static final long CONV = 0x1234_5680L;

  /** Discard port: nothing listens there, so direct checks to it never succeed. */
  private static final int UNREACHABLE_PORT = 9;

  public final NetLoop hostLoop;
  public final NetLoop joinerLoop;
  public final ImpairedLink link;
  public final Socket client;
  public final CompletableFuture<Boolean> joinerRelayed = new CompletableFuture<>();
  public final CompletableFuture<PeerSession.End> hostEnded = new CompletableFuture<>();
  public final CompletableFuture<PeerSession.End> joinerEnded = new CompletableFuture<>();
  private final EchoEndpoint echo;
  public PeerSession hostSession;
  public PeerSession joinerSession;

  public TransportPair(ImpairedLink.Impairment impairment, RelayCredentials relay, long seed)
      throws Exception {
    this(impairment, relay, seed, false);
  }

  /**
   * @param blockDirect hide each side's real host candidate from the other (an unroutable port
   *     instead), so only the relay can connect them
   */
  public TransportPair(
      ImpairedLink.Impairment impairment, RelayCredentials relay, long seed, boolean blockDirect)
      throws Exception {
    InetAddress loopback = InetAddress.getLoopbackAddress();
    NetLoop.Options options = new NetLoop.Options(List.of(loopback), null);
    hostLoop = NetLoop.open(options);
    joinerLoop = NetLoop.open(options);
    link =
        impairment == null
            ? null
            : new ImpairedLink(joinerLoop.port(), hostLoop.port(), impairment, seed);
    UnaryOperator<IceParameters> towardsHost =
        blockDirect
            ? ice -> retarget(ice, UNREACHABLE_PORT)
            : link == null
                ? UnaryOperator.identity()
                : ice -> retarget(ice, link.joinerFacingPort());
    UnaryOperator<IceParameters> towardsJoiner =
        blockDirect
            ? ice -> retarget(ice, UNREACHABLE_PORT)
            : link == null ? UnaryOperator.identity() : ice -> retarget(ice, link.hostFacingPort());
    echo = new EchoEndpoint(hostLoop);

    CompletableFuture<IceParameters> joinerIce = new CompletableFuture<>();
    CompletableFuture<IceParameters> hostIce = new CompletableFuture<>();
    CompletableFuture<Integer> proxyPort = new CompletableFuture<>();
    PeerSession.Listener joinerListener =
        new PeerSession.Listener() {
          @Override
          public void onGathered(PeerSession session, IceParameters local) {
            joinerIce.complete(local);
          }

          @Override
          public void onConnected(PeerSession session, boolean relayed) {
            try {
              LoopbackProxy proxy = LoopbackProxy.open(joinerLoop);
              joinerLoop.attach(session, proxy);
              joinerRelayed.complete(relayed);
              proxyPort.complete(proxy.port());
            } catch (IOException exception) {
              proxyPort.completeExceptionally(exception);
            }
          }

          @Override
          public void onEnded(PeerSession session, PeerSession.End end) {
            joinerEnded.complete(end);
            proxyPort.completeExceptionally(new IOException("ended: " + end));
          }
        };
    PeerSession.Listener hostListener =
        new PeerSession.Listener() {
          @Override
          public void onGathered(PeerSession session, IceParameters local) {
            hostIce.complete(local);
          }

          @Override
          public void onConnected(PeerSession session, boolean relayed) {
            hostLoop.attach(session, echo);
          }

          @Override
          public void onEnded(PeerSession session, PeerSession.End end) {
            hostEnded.complete(end);
          }
        };
    joinerLoop.execute(
        () ->
            joinerSession =
                joinerLoop.startSession(PeerSession.Role.JOINER, joinerListener, relay));
    IceParameters fromJoiner = towardsJoiner.apply(joinerIce.get(5, TimeUnit.SECONDS));
    hostLoop.execute(
        () -> {
          hostSession = hostLoop.startSession(PeerSession.Role.HOST, hostListener, null);
          hostLoop.setRemote(hostSession, fromJoiner, CONV);
        });
    IceParameters fromHost = towardsHost.apply(hostIce.get(5, TimeUnit.SECONDS));
    joinerLoop.execute(() -> joinerLoop.setRemote(joinerSession, fromHost, CONV));
    int port = proxyPort.get(20, TimeUnit.SECONDS);
    client = new Socket(loopback, port);
    client.setTcpNoDelay(true);
  }

  /** Bytes the host has echoed so far. */
  public long echoed() {
    return echo.echoed();
  }

  private static IceParameters retarget(IceParameters ice, int port) {
    List<Candidate> candidates =
        ice.candidates().stream()
            .map(
                candidate ->
                    candidate.kind() == Candidate.Kind.HOST
                        ? new Candidate(Candidate.Kind.HOST, "127.0.0.1", port)
                        : candidate)
            .toList();
    return new IceParameters(ice.ufrag(), ice.password(), candidates);
  }

  @Override
  public void close() throws IOException {
    client.close();
    if (link != null) {
      link.close();
    }
    hostLoop.close();
    joinerLoop.close();
  }

  /** Host-side stream that sends back whatever arrives (network thread only). */
  public static final class EchoEndpoint implements StreamEndpoint {
    private final ByteRing pending = new ByteRing(1 << 20);
    private final NetLoop loop;
    private long echoed;

    public EchoEndpoint(NetLoop loop) {
      this.loop = loop;
    }

    public long echoed() {
      return echoed;
    }

    @Override
    public int read(java.nio.ByteBuffer target) {
      int count = pending.read(target);
      echoed += count;
      return count;
    }

    @Override
    public int write(java.nio.ByteBuffer source) {
      int count = pending.write(source);
      if (count > 0) {
        loop.wakeup();
      }
      return count;
    }

    @Override
    public void close() {}
  }

  static InetSocketAddress loopback(int port) {
    return new InetSocketAddress(InetAddress.getLoopbackAddress(), port);
  }
}
