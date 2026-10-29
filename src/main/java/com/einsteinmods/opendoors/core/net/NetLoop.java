package com.einsteinmods.opendoors.core.net;

import com.einsteinmods.opendoors.core.signaling.Candidate;
import com.einsteinmods.opendoors.core.stun.Demux;
import com.einsteinmods.opendoors.core.stun.StunAddress;
import com.einsteinmods.opendoors.core.stun.StunMessage;
import com.einsteinmods.opendoors.core.stun.StunReader;
import com.einsteinmods.opendoors.core.stun.StunWriter;
import java.io.IOException;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.StandardSocketOptions;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.nio.channels.SelectableChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The network thread: one UDP socket and one daemon thread ("OpenDoors Net") that blocks in its
 * selector while nothing is going on, and while a session is active wakes only on traffic or its
 * next deadline (KCP every {@link PeerSession#KCP_INTERVAL_MILLIS} ms). Datagrams are told apart by
 * their first byte (RFC 7983): STUN (checks, relay control), TURN ChannelData, or KCP. The receive
 * and send paths reuse their buffers; nothing is allocated per datagram.
 */
public final class NetLoop implements AutoCloseable {
  /**
   * Where candidates come from; tests pin loopback addresses and skip STUN.
   *
   * @param hostAddresses local addresses to offer, or null for the machine's interfaces
   * @param stunServer resolved STUN server for the public address, or null for none
   * @param natProbeServer a second STUN destination (other port or address), or null: if the NAT
   *     maps the socket to another public port for it, the mapping is endpoint-dependent
   *     ("symmetric"), which only diagnostics report
   */
  public record Options(
      List<InetAddress> hostAddresses,
      InetSocketAddress stunServer,
      InetSocketAddress natProbeServer) {
    public Options(List<InetAddress> hostAddresses, InetSocketAddress stunServer) {
      this(hostAddresses, stunServer, null);
    }
  }

  private static final String THREAD_NAME = "OpenDoors Net";
  private static final int DATAGRAM_SIZE = 2_048;
  private static final int SOCKET_BUFFER_BYTES = 1 << 20;
  private static final int MAX_HOST_CANDIDATES = 5;
  private static final int MAX_IPV6_CANDIDATES = 2;
  private static final int PROBE_FIRST_RETRANSMIT_MILLIS = 100;
  private static final int PROBE_MAX_TRANSMISSIONS = 5;
  private static final int REFLEXIVE_CACHE_MILLIS = 30_000;
  private static final char[] ICE_ALPHABET =
      "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/".toCharArray();

  private final Selector selector;
  private final DatagramChannel udp;
  private final int port;
  private final Thread thread;
  private final Queue<Runnable> tasks = new ConcurrentLinkedQueue<>();
  private final AtomicBoolean wakePending = new AtomicBoolean();
  private final SecureRandom random = new SecureRandom();
  private final long epochNanos = System.nanoTime();
  private volatile boolean running = true;

  // Network thread only.
  private final List<PeerSession> sessions = new ArrayList<>();
  private final List<Candidate> hostCandidates;
  private final byte[] receiveArray = new byte[DATAGRAM_SIZE];
  private final ByteBuffer receiveBuffer = ByteBuffer.wrap(receiveArray);
  private final ByteBuffer sendBuffer = ByteBuffer.allocateDirect(DATAGRAM_SIZE);
  private final StunReader reader = new StunReader();
  private final StunReader innerReader = new StunReader();
  private final StunWriter writer = new StunWriter();
  private final Probe primary = new Probe();
  private final Probe alternate = new Probe();
  private final Probe[] probes = {primary, alternate};
  private int reflexiveAt;
  private boolean natHintLogged;
  private volatile Candidate mappedCandidate;

  /** One STUN Binding probe to one server (RFC 8489 §6.2.1 retransmissions). */
  private static final class Probe {
    final byte[] message = new byte[StunMessage.HEADER_SIZE + 8];
    final byte[] transaction = new byte[StunMessage.TRANSACTION_ID_SIZE];
    final StunAddress result = new StunAddress();
    InetSocketAddress server;
    boolean active;
    boolean known;
    boolean done;
    int transmissions;
    int at;
    int interval;
    int length;
  }

  private IOException receiveFailure;
  private final java.util.function.Consumer<SelectionKey> onReadyKey = this::onReadyKey;

  private NetLoop(Options options) throws IOException {
    selector = Selector.open();
    udp = DatagramChannel.open();
    udp.setOption(StandardSocketOptions.SO_RCVBUF, SOCKET_BUFFER_BYTES);
    udp.setOption(StandardSocketOptions.SO_SNDBUF, SOCKET_BUFFER_BYTES);
    udp.bind(new InetSocketAddress(0));
    udp.configureBlocking(false);
    udp.register(selector, SelectionKey.OP_READ);
    port = ((InetSocketAddress) udp.getLocalAddress()).getPort();
    hostCandidates = gatherHostCandidates(options.hostAddresses(), port);
    primary.server = options.stunServer();
    alternate.server = options.natProbeServer();
    if (ConnectionLog.on()) {
      ConnectionLog.log("socket bound to UDP port " + port + "; host candidates " + hostCandidates);
    }
    thread = new Thread(this::run, THREAD_NAME);
    thread.setDaemon(true);
  }

  /** Binds the UDP socket on an ephemeral port and starts the network thread. */
  public static NetLoop open(Options options) throws IOException {
    NetLoop loop = new NetLoop(options);
    loop.thread.start();
    return loop;
  }

  public int port() {
    return port;
  }

  /** Runs {@code task} on the network thread (control path: may allocate). */
  public void execute(Runnable task) {
    tasks.add(task);
    wakeup();
  }

  /** Wakes the network thread so it moves pending stream bytes; cheap when already awake. */
  public void wakeup() {
    if (wakePending.compareAndSet(false, true)) {
      selector.wakeup();
    }
  }

  /** Whether the caller is the network thread. */
  public boolean inLoop() {
    return Thread.currentThread() == thread;
  }

  /** Network thread: creates a session; {@code relay} (joiner only) may be null. */
  public PeerSession startSession(
      PeerSession.Role role, PeerSession.Listener listener, RelayCredentials relay) {
    PeerSession session = new PeerSession(this, role, listener);
    sessions.add(session);
    TurnAllocation allocation =
        relay == null
            ? null
            : new TurnAllocation(
                relay.server(), relay.username(), relay.password(), this::send, session, random);
    session.start(now(), allocation);
    return session;
  }

  /** Network thread: the peer's answer or offer. */
  public void setRemote(
      PeerSession session,
      com.einsteinmods.opendoors.core.signaling.IceParameters remote,
      long conv) {
    session.setRemote(remote, (int) conv, now());
  }

  /** Network thread: connects the game stream to a connected session. */
  public void attach(PeerSession session, StreamEndpoint endpoint) {
    session.attach(endpoint);
  }

  /** Network thread: closes a session and tells the peer. */
  public void closeSession(PeerSession session) {
    session.close(now());
  }

  /** Network thread: number of live sessions. */
  public int sessionCount() {
    return sessions.size();
  }

  @Override
  public void close() {
    running = false;
    selector.wakeup();
  }

  // ---- for sessions (network thread) ----

  int now() {
    return (int) ((System.nanoTime() - epochNanos) / 1_000_000L);
  }

  SecureRandom random() {
    return random;
  }

  /** Candidates every new session offers: a router port mapping first, then the interfaces. */
  List<Candidate> hostCandidates() {
    Candidate mapped = mappedCandidate;
    if (mapped == null) {
      return hostCandidates;
    }
    List<Candidate> all = new ArrayList<>(hostCandidates.size() + 1);
    all.add(mapped);
    all.addAll(hostCandidates);
    return all;
  }

  /**
   * Any thread: a router port mapping (UPnP, NAT-PMP or PCP) of this socket, offered to new
   * sessions as a server-reflexive candidate (null removes it).
   */
  public void setMappedCandidate(Candidate candidate) {
    mappedCandidate = candidate;
  }

  String randomIceString(int length) {
    char[] text = new char[length];
    for (int i = 0; i < length; i++) {
      text[i] = ICE_ALPHABET[random.nextInt(ICE_ALPHABET.length)];
    }
    return new String(text);
  }

  void send(byte[] data, int offset, int length, InetSocketAddress target) {
    sendBuffer.clear();
    sendBuffer.put(data, offset, length).flip();
    try {
      udp.send(sendBuffer, target);
    } catch (IOException exception) {
      // UDP is best effort: a send error (no route, interface gone) is a lost datagram.
    }
  }

  void remove(PeerSession session) {
    sessions.remove(session);
  }

  void register(SelectableChannel channel, int operations, SelectableHandler handler)
      throws IOException {
    channel.configureBlocking(false);
    channel.register(selector, operations, handler);
  }

  /** Asks for the socket's public address (STUN Binding, RFC 8489 §6). */
  void requestReflexive(int now) {
    if (primary.server == null) {
      deliverReflexive(null, now);
      return;
    }
    if (primary.known && now - reflexiveAt < REFLEXIVE_CACHE_MILLIS) {
      deliverReflexive(primary.result, now);
      return;
    }
    if (!primary.active) {
      natHintLogged = false;
      startProbe(primary, now);
      if (alternate.server != null && !alternate.active) {
        startProbe(alternate, now);
      }
    }
  }

  private void startProbe(Probe probe, int now) {
    probe.active = true;
    probe.done = false;
    probe.known = false;
    probe.transmissions = 0;
    probe.interval = PROBE_FIRST_RETRANSMIT_MILLIS;
    random.nextBytes(probe.transaction);
    writer.begin(
        probe.message,
        0,
        StunMessage.messageType(StunMessage.METHOD_BINDING, StunMessage.CLASS_REQUEST),
        probe.transaction,
        0);
    writer.addFingerprint();
    probe.length = writer.length();
    sendProbe(probe, now);
  }

  /** A relayed datagram from {@code peer}, unwrapped by the session's TURN allocation. */
  void dispatchRelayed(PeerSession session, StunAddress peer, byte[] data, int offset, int length) {
    if (length <= 0) {
      return;
    }
    int kind = Demux.classify(data[offset]);
    if (kind == Demux.KIND_STUN) {
      if (innerReader.wrap(data, offset, length)) {
        session.onPeerStun(innerReader, null, peer, now());
      }
    } else if (kind == Demux.KIND_APPLICATION) {
      session.onPeerData(data, offset, length, null, peer);
    }
  }

  // ---- loop ----

  private void run() {
    try {
      while (running) {
        wakePending.set(false);
        int now = now();
        Runnable task;
        while ((task = tasks.poll()) != null) {
          task.run();
        }
        int timeout = service(now);
        // The consumer form walks ready keys without a selected-key set iterator per wake-up.
        if (timeout == 0) {
          selector.selectNow(onReadyKey);
        } else {
          selector.select(onReadyKey, timeout < 0 ? 0 : timeout);
        }
        if (receiveFailure != null) {
          throw receiveFailure;
        }
      }
    } catch (IOException exception) {
      running = false;
    } finally {
      shutdown();
    }
  }

  private void onReadyKey(SelectionKey key) {
    if (!key.isValid()) {
      return;
    }
    if (key.channel() == udp) {
      try {
        receiveAll();
      } catch (IOException exception) {
        receiveFailure = exception;
      }
    } else if (key.attachment() instanceof SelectableHandler handler) {
      try {
        handler.onReady(key);
      } catch (IOException exception) {
        key.cancel();
        try {
          key.channel().close();
        } catch (IOException closeFailure) {
          // Already broken; nothing more to release.
        }
      }
    }
  }

  /** Pumps and ticks every session; returns the select timeout (-1 idle, 0 now, else ms). */
  private int service(int now) {
    int next = Integer.MAX_VALUE;
    boolean anyDeadline = false;
    for (int i = sessions.size() - 1; i >= 0 && i < sessions.size(); i--) {
      PeerSession session = sessions.get(i);
      session.pump(now);
      int due = session.tick(now);
      if (due != Integer.MAX_VALUE) {
        next = anyDeadline ? earlier(next, due, now) : due;
        anyDeadline = true;
      }
    }
    for (Probe probe : probes) {
      if (!probe.active) {
        continue;
      }
      if (now - probe.at >= 0) {
        if (probe.transmissions >= PROBE_MAX_TRANSMISSIONS) {
          finishProbe(probe, false, now);
          continue;
        }
        sendProbe(probe, now);
      }
      next = anyDeadline ? earlier(next, probe.at, now) : probe.at;
      anyDeadline = true;
    }
    if (!anyDeadline) {
      return -1;
    }
    return Math.max(0, next - now) == 0 ? 0 : Math.max(1, next - now);
  }

  private void receiveAll() throws IOException {
    while (true) {
      receiveBuffer.clear();
      InetSocketAddress from = (InetSocketAddress) udp.receive(receiveBuffer);
      if (from == null) {
        return;
      }
      int length = receiveBuffer.position();
      if (length > 0) {
        dispatch(from, length);
      }
    }
  }

  private void dispatch(InetSocketAddress from, int length) {
    int now = now();
    switch (Demux.classify(receiveArray[0])) {
      case Demux.KIND_STUN -> {
        if (reader.wrap(receiveArray, 0, length)) {
          dispatchStun(from, now);
        }
      }
      case Demux.KIND_CHANNEL_DATA -> {
        for (int i = 0; i < sessions.size(); i++) {
          TurnAllocation turn = sessions.get(i).turn();
          if (turn != null && turn.server().equals(from)) {
            turn.onChannelData(receiveArray, 0, length);
            return;
          }
        }
      }
      default -> {
        for (int i = 0; i < sessions.size(); i++) {
          sessions.get(i).onPeerData(receiveArray, 0, length, from, null);
        }
      }
    }
  }

  private void dispatchStun(InetSocketAddress from, int now) {
    if (reader.messageClass() == StunMessage.CLASS_SUCCESS) {
      for (Probe probe : probes) {
        if (probe.active
            && reader.transactionIdEquals(probe.transaction, 0)
            && from.equals(probe.server)) {
          probe.result.clear();
          boolean found =
              (reader.find(StunMessage.ATTR_XOR_MAPPED_ADDRESS)
                      && reader.readXorAddress(probe.result))
                  || (resetAndFind(StunMessage.ATTR_MAPPED_ADDRESS)
                      && reader.readAddress(probe.result));
          finishProbe(probe, found, now);
          return;
        }
      }
    }
    for (int i = 0; i < sessions.size(); i++) {
      TurnAllocation turn = sessions.get(i).turn();
      if (turn != null && turn.server().equals(from)) {
        turn.onStun(reader, now);
        return;
      }
    }
    if (reader.method() != StunMessage.METHOD_BINDING) {
      return;
    }
    int messageClass = reader.messageClass();
    for (int i = 0; i < sessions.size(); i++) {
      PeerSession session = sessions.get(i);
      boolean owner =
          switch (messageClass) {
            case StunMessage.CLASS_REQUEST -> session.ownsUsername(reader);
            case StunMessage.CLASS_SUCCESS, StunMessage.CLASS_ERROR ->
                session.ownsTransaction(reader);
            default -> true;
          };
      if (owner) {
        reader.rewind();
        session.onPeerStun(reader, from, null, now);
        if (messageClass != StunMessage.CLASS_INDICATION) {
          return;
        }
      }
    }
  }

  private boolean resetAndFind(int type) {
    reader.rewind();
    return reader.find(type);
  }

  private void sendProbe(Probe probe, int now) {
    send(probe.message, 0, probe.length, probe.server);
    probe.transmissions++;
    probe.at = now + probe.interval;
    probe.interval *= 2;
  }

  private void finishProbe(Probe probe, boolean found, int now) {
    probe.active = false;
    probe.done = true;
    probe.known = found;
    if (ConnectionLog.on()) {
      ConnectionLog.log(
          "STUN "
              + probe.server
              + " -> "
              + (found
                  ? "mapped " + probe.result
                  : "no answer after " + probe.transmissions + " tries"));
    }
    if (probe == primary) {
      reflexiveAt = now;
      deliverReflexive(found ? primary.result : null, now);
    }
    logNatHint();
  }

  /** Once both probes are done: same public port for both destinations or not. */
  private void logNatHint() {
    if (natHintLogged
        || !ConnectionLog.on()
        || !primary.done
        || alternate.server == null
        || !alternate.done) {
      return;
    }
    natHintLogged = true;
    if (!primary.known || !alternate.known) {
      ConnectionLog.log("NAT type: unknown (a STUN probe got no answer)");
    } else if (primary.result.equals(alternate.result)) {
      ConnectionLog.log(
          "NAT type: endpoint-independent mapping (same public port "
              + primary.result.port()
              + " for both STUN destinations): direct connections usually work");
    } else {
      ConnectionLog.log(
          "NAT type: endpoint-dependent (symmetric) mapping (public ports "
              + primary.result.port()
              + " and "
              + alternate.result.port()
              + " for two destinations): the friend only"
              + " reaches this game if their own NAT is open, a router port mapping works, or a"
              + " relay is used. A VPN often causes this.");
    }
  }

  private void deliverReflexive(StunAddress address, int now) {
    for (int i = sessions.size() - 1; i >= 0 && i < sessions.size(); i--) {
      sessions.get(i).onReflexive(address, now);
    }
  }

  private void shutdown() {
    for (PeerSession session : List.copyOf(sessions)) {
      session.close(now());
    }
    try {
      for (SelectionKey key : selector.keys()) {
        key.channel().close();
      }
      selector.close();
    } catch (IOException exception) {
      // Closing anyway.
    }
  }

  private static List<Candidate> gatherHostCandidates(List<InetAddress> pinned, int port) {
    List<InetAddress> addresses = pinned != null ? pinned : interfaceAddresses();
    List<Candidate> candidates = new ArrayList<>();
    int ipv6 = 0;
    for (InetAddress address : addresses) {
      if (candidates.size() == MAX_HOST_CANDIDATES) {
        break;
      }
      if (address instanceof Inet6Address && ipv6++ == MAX_IPV6_CANDIDATES) {
        continue;
      }
      candidates.add(new Candidate(Candidate.Kind.HOST, PeerSession.literal(address), port));
    }
    return List.copyOf(candidates);
  }

  /**
   * Usable addresses of the interfaces that are up: global IPv6 first (no NAT in the way), then
   * IPv4. Link-local and unique-local (fc00::/7) IPv6 addresses are skipped.
   */
  private static List<InetAddress> interfaceAddresses() {
    List<InetAddress> ipv4 = new ArrayList<>();
    List<InetAddress> ipv6 = new ArrayList<>();
    try {
      for (NetworkInterface network : Collections.list(NetworkInterface.getNetworkInterfaces())) {
        if (!network.isUp() || network.isLoopback() || network.isVirtual()) {
          continue;
        }
        boolean vpnLike = isVpnLike(network.getName(), network.getDisplayName());
        for (InetAddress address : Collections.list(network.getInetAddresses())) {
          if (address.isLinkLocalAddress() || address.isLoopbackAddress()) {
            continue;
          }
          if (ConnectionLog.on()) {
            ConnectionLog.log(
                "interface "
                    + network.getName()
                    + " "
                    + PeerSession.literal(address)
                    + (vpnLike
                        ? " (VPN-like: if the VPN carries the game's traffic, direct connections"
                            + " often fail)"
                        : ""));
          }
          if (address instanceof Inet4Address) {
            ipv4.add(address);
          } else if (address instanceof Inet6Address && isGlobalIpv6(address)) {
            ipv6.add(address);
          }
        }
      }
    } catch (SocketException exception) {
      return List.of();
    }
    ipv6.addAll(ipv4);
    return ipv6;
  }

  /** Not unique-local (fc00::/7) nor deprecated site-local (fec0::/10). */
  static boolean isGlobalIpv6(InetAddress address) {
    byte first = address.getAddress()[0];
    return (first & 0xFE) != 0xFC && !address.isSiteLocalAddress();
  }

  /**
   * Tunnel interfaces of common VPN clients: macOS/Linux names (utun, ipsec, wg, tun, tap, ppp) or
   * a Windows adapter description naming a VPN, TAP, WireGuard or tunnel adapter.
   */
  static boolean isVpnLike(String name, String displayName) {
    String description = displayName == null ? "" : displayName.toLowerCase(java.util.Locale.ROOT);
    return description.contains("vpn")
        || description.contains("wireguard")
        || description.contains("tap-")
        || description.contains("tunnel")
        || name.startsWith("utun")
        || name.startsWith("tun")
        || name.startsWith("tap")
        || name.startsWith("wg")
        || name.startsWith("ppp")
        || name.startsWith("ipsec");
  }

  private static int earlier(int current, int candidate, int now) {
    return candidate - now < current - now ? candidate : current;
  }
}
