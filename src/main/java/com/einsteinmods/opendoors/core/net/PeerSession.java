package com.einsteinmods.opendoors.core.net;

import com.einsteinmods.opendoors.core.kcp.Kcp;
import com.einsteinmods.opendoors.core.signaling.Candidate;
import com.einsteinmods.opendoors.core.signaling.IceParameters;
import com.einsteinmods.opendoors.core.stun.StunAddress;
import com.einsteinmods.opendoors.core.stun.StunIntegrity;
import com.einsteinmods.opendoors.core.stun.StunMessage;
import com.einsteinmods.opendoors.core.stun.StunReader;
import com.einsteinmods.opendoors.core.stun.StunWriter;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * One peer-to-peer stream: ICE connectivity checks (RFC 8445, simplified: one socket, the joiner
 * controls and nominates, the host answers and follows the nomination), then a KCP stream over the
 * selected path, direct or through the joiner's TURN relay. Relay paths are only checked once
 * direct checks found nothing for {@link #RELAY_DELAY_MILLIS}. Datagrams from anything but the
 * selected path or with another conversation id are dropped. Network thread only, except
 * construction.
 */
public final class PeerSession implements TurnAllocation.Events, Kcp.Output {
  /** Which end of the join this is. */
  public enum Role {
    /** Answers checks and follows the joiner's nomination (ICE controlled). */
    HOST,
    /** Nominates the path (ICE controlling) and owns the relay allocation. */
    JOINER
  }

  /** Why a session ended. */
  public enum End {
    /** No path worked. */
    UNREACHABLE,
    /** The local game or the peer closed the stream. */
    CLOSED,
    /** The peer went silent. */
    TIMED_OUT
  }

  /** Session events, on the network thread. */
  public interface Listener {
    void onGathered(PeerSession session, IceParameters local);

    void onConnected(PeerSession session, boolean relayed);

    void onEnded(PeerSession session, End end);
  }

  public static final int MTU = 1_200;
  public static final int KCP_INTERVAL_MILLIS = 10;
  private static final int KCP_RESEND = 2;
  static final int KCP_WINDOW = 256;
  public static final int RELAY_DELAY_MILLIS = 2_500;

  /** Checks go out every 50 ms for the first 5 s (both sides at once), then every 250 ms. */
  static final int CHECK_INTERVAL_MILLIS = 50;

  static final int SLOW_CHECK_INTERVAL_MILLIS = 250;
  static final int AGGRESSIVE_CHECK_MILLIS = 5_000;
  static final int CHECK_TIMEOUT_MILLIS = 10_000;
  private static final int GATHER_TIMEOUT_MILLIS = 2_000;
  private static final int KEEPALIVE_MILLIS = 15_000;
  private static final int SILENCE_TIMEOUT_MILLIS = 30_000;
  private static final int MAX_PAIRS = 16;

  /** Segments waiting to be sent or acked before the game side stops being read. */
  static final int SEND_QUEUE_LIMIT = KCP_WINDOW;

  private static final int GOODBYE_LENGTH = 5;
  private static final byte GOODBYE_MARKER = (byte) 0xFF;
  private static final int GOODBYE_REPEATS = 3;
  private static final int PRIORITY_HOST = 126 << 24;
  private static final int PRIORITY_PREFLEX = 110 << 24;

  /** 32 KB per KCP send stays far below its 128-fragment message limit at this MTU. */
  static final int BUFFER_SIZE = 32 * 1024;

  /**
   * Upper bound of KCP segments one stream can hold at once: the send limit plus one 32 KB write
   * past it, the receive buffer (one window) and the receive queue (one window). All are pooled up
   * front when the stream opens (about 0.95 MB per connected peer), so the stream never allocates
   * later, even when the network thread runs late and queues fill up.
   */
  private static final int MAX_SEGMENTS =
      SEND_QUEUE_LIMIT + BUFFER_SIZE / (MTU - Kcp.OVERHEAD) + 1 + 2 * KCP_WINDOW + 1;

  /** Pending acks kept before they are sent early (more than a full socket buffer of datagrams). */
  private static final int ACK_CAPACITY = 1_024;

  private static final int DATAGRAM_SIZE = 1_600;

  private enum Phase {
    GATHERING,
    WAITING_FOR_PEER,
    CHECKING,
    CONNECTED,
    ENDED
  }

  /** A candidate pair: our socket (directly or via the relay) to one remote address. */
  private static final class Pair {
    final InetSocketAddress remote;
    final StunAddress remoteStun = new StunAddress();
    final boolean relayed;

    /** Relay path (ours or the peer's): only checked after direct checks had their chance. */
    final boolean delayed;

    final byte[] transactionId = new byte[StunMessage.TRANSACTION_ID_SIZE];
    final boolean ipv6;
    boolean succeeded;
    int checksSent;
    int requestsReceived;
    int responsesReceived;
    boolean nominating;
    boolean checkNow;

    Pair(InetSocketAddress remote, boolean relayed, boolean delayed) {
      this.remote = remote;
      this.relayed = relayed;
      this.delayed = delayed;
      this.ipv6 = remote.getAddress() instanceof java.net.Inet6Address;
      remoteStun.set(remote);
    }

    @Override
    public String toString() {
      return (relayed ? "relay->" : "")
          + remote.getAddress().getHostAddress()
          + ":"
          + remote.getPort();
    }
  }

  private final NetLoop loop;
  private final Role role;
  private final Listener listener;
  private final String localUfrag;
  private final String localPassword;
  private final StunIntegrity localKey;
  private final long tieBreaker;
  private final List<Candidate> localCandidates = new ArrayList<>();
  private final Pair[] pairs = new Pair[MAX_PAIRS];
  private final StunWriter writer = new StunWriter();
  private final byte[] datagram = new byte[DATAGRAM_SIZE];
  private final ByteBuffer sendBuffer = ByteBuffer.allocate(BUFFER_SIZE);
  private final ByteBuffer receiveBuffer = ByteBuffer.allocate(BUFFER_SIZE);
  private final StunAddress scratchAddress = new StunAddress();
  private final byte[] scratchTransaction = new byte[StunMessage.TRANSACTION_ID_SIZE];
  private int pairCount;

  private Phase phase = Phase.GATHERING;
  private TurnAllocation turn;
  private boolean reflexiveDone;
  private String remoteUsername;
  private StunIntegrity remoteKey;
  private int conv;
  private Kcp kcp;
  private StreamEndpoint endpoint;
  private Pair selected;
  private int phaseStartedAt;
  private int checksStartedAt;
  private boolean checking;
  private int nextCheckAt;
  private int keepaliveAt;
  private int lastInboundAt;
  private int kcpAt;
  private boolean pendingDelivery;

  PeerSession(NetLoop loop, Role role, Listener listener) {
    this.loop = loop;
    this.role = role;
    this.listener = listener;
    this.localUfrag = loop.randomIceString(8);
    this.localPassword = loop.randomIceString(24);
    this.localKey = StunIntegrity.shortTerm(localPassword);
    this.tieBreaker = loop.random().nextLong();
  }

  public Role role() {
    return role;
  }

  public boolean isRelayed() {
    return selected != null && selected.relayed;
  }

  /** Smoothed round trip of the stream in ms (KCP's estimate), 0 before connecting. */
  public int smoothedRttMillis() {
    return kcp == null ? 0 : kcp.smoothedRtt();
  }

  String localUfrag() {
    return localUfrag;
  }

  /** Starts gathering; {@code relay} (joiner only, may be null) is allocated in parallel. */
  void start(int now, TurnAllocation relay) {
    phaseStartedAt = now;
    if (ConnectionLog.on()) {
      log(
          "session started as "
              + role
              + (relay == null ? ", no relay" : ", relay " + relay.server()));
    }
    localCandidates.addAll(loop.hostCandidates());
    turn = relay;
    if (turn != null) {
      turn.start(now);
    }
    loop.requestReflexive(now);
    finishGatheringIfReady(now);
  }

  /** The socket's public address is known (or could not be found: {@code address} null). */
  void onReflexive(StunAddress address, int now) {
    if (phase != Phase.GATHERING || reflexiveDone) {
      return;
    }
    reflexiveDone = true;
    if (address != null) {
      addCandidate(Candidate.Kind.SRFLX, address);
    }
    finishGatheringIfReady(now);
  }

  @Override
  public void onAllocated(StunAddress relayed) {
    if (phase == Phase.GATHERING) {
      addCandidate(Candidate.Kind.RELAY, relayed);
      finishGatheringIfReady(loop.now());
    }
  }

  @Override
  public void onFailed() {
    turn = null;
    if (phase == Phase.GATHERING) {
      finishGatheringIfReady(loop.now());
    }
  }

  /** The other side's credentials and candidates arrived; checks begin. */
  void setRemote(IceParameters remote, int conversation, int now) {
    if (phase == Phase.ENDED || remoteKey != null) {
      return;
    }
    conv = conversation;
    remoteUsername = remote.ufrag() + ":" + localUfrag;
    remoteKey = StunIntegrity.shortTerm(remote.password());
    for (Candidate candidate : remote.candidates()) {
      InetSocketAddress address = toSocketAddress(candidate);
      if (address == null) {
        continue;
      }
      addPair(address, false, candidate.kind() == Candidate.Kind.RELAY);
      if (turn != null && candidate.kind() != Candidate.Kind.RELAY) {
        Pair relayPair = addPair(address, true, true);
        if (relayPair != null) {
          turn.permit(relayPair.remoteStun, now);
        }
      }
    }
    if (ConnectionLog.on()) {
      log("remote candidates " + remote.candidates());
    }
    // Check at once, even while still gathering: both sides then send at the same time
    // (simultaneous open), which is what opens most NATs.
    startChecks(now);
    if (phase == Phase.WAITING_FOR_PEER) {
      phase = Phase.CHECKING;
    }
  }

  /** Connects the local game stream (once connected). */
  void attach(StreamEndpoint gameSide) {
    endpoint = gameSide;
  }

  /** Closes the stream and tells the peer. */
  void close(int now) {
    if (phase == Phase.CONNECTED) {
      sendGoodbye();
    }
    end(End.CLOSED, now);
  }

  /** A STUN message from the peer (directly from {@code from}, or relayed from {@code peer}). */
  void onPeerStun(StunReader reader, InetSocketAddress from, StunAddress peer, int now) {
    if (phase == Phase.ENDED) {
      return;
    }
    int method = reader.method();
    int messageClass = reader.messageClass();
    if (method != StunMessage.METHOD_BINDING) {
      return;
    }
    if (messageClass == StunMessage.CLASS_REQUEST) {
      onBindingRequest(reader, from, peer, now);
    } else if (messageClass == StunMessage.CLASS_SUCCESS && remoteKey != null) {
      onBindingSuccess(reader, from, peer, now);
    } else if (messageClass == StunMessage.CLASS_INDICATION && isFromSelected(from, peer)) {
      lastInboundAt = now;
    }
  }

  /** Whether a binding request's USERNAME names this session ({@code local:remote}). */
  boolean ownsUsername(StunReader reader) {
    reader.rewind();
    if (!reader.find(StunMessage.ATTR_USERNAME)) {
      return false;
    }
    int length = reader.valueLength();
    int ufragLength = localUfrag.length();
    if (length <= ufragLength || reader.buffer()[reader.valueOffset() + ufragLength] != ':') {
      return false;
    }
    byte[] buffer = reader.buffer();
    int offset = reader.valueOffset();
    for (int i = 0; i < ufragLength; i++) {
      if (buffer[offset + i] != localUfrag.charAt(i)) {
        return false;
      }
    }
    return true;
  }

  /** Whether a binding response answers one of our checks. */
  boolean ownsTransaction(StunReader reader) {
    for (int i = 0; i < pairCount; i++) {
      if (reader.transactionIdEquals(pairs[i].transactionId, 0)) {
        return true;
      }
    }
    return false;
  }

  TurnAllocation turn() {
    return turn;
  }

  /** KCP (or goodbye) datagram; dropped unless it comes over the selected path. */
  void onPeerData(byte[] data, int offset, int length, InetSocketAddress from, StunAddress peer) {
    if (phase != Phase.CONNECTED || !isFromSelected(from, peer)) {
      return;
    }
    if (length == GOODBYE_LENGTH
        && data[offset + 4] == GOODBYE_MARKER
        && readConv(data, offset) == conv) {
      end(End.CLOSED, loop.now());
      return;
    }
    if (kcp.input(data, offset, length) == 0) {
      lastInboundAt = loop.now();
    }
  }

  @Override
  public void onPeerData(StunAddress peer, byte[] data, int offset, int length) {
    loop.dispatchRelayed(this, peer, data, offset, length);
  }

  /** Moves stream bytes between KCP and the game; called when data may be waiting. */
  void pump(int now) {
    if (phase != Phase.CONNECTED || endpoint == null) {
      return;
    }
    boolean sent = false;
    while (kcp.waitSnd() < SEND_QUEUE_LIMIT) {
      sendBuffer.clear();
      int read = endpoint.read(sendBuffer);
      if (read < 0) {
        close(now);
        return;
      }
      if (read == 0) {
        break;
      }
      if (kcp.send(sendBuffer.array(), 0, read) != read) {
        close(now);
        return;
      }
      sent = true;
    }
    if (sent) {
      kcp.update(now);
      kcp.flush();
    }
    deliver();
  }

  private void deliver() {
    if (pendingDelivery) {
      endpoint.write(receiveBuffer);
      if (receiveBuffer.hasRemaining()) {
        return;
      }
      pendingDelivery = false;
    }
    while (kcp.peekSize() > 0) {
      receiveBuffer.clear();
      int length = kcp.recv(receiveBuffer.array(), 0, receiveBuffer.capacity());
      if (length <= 0) {
        return;
      }
      receiveBuffer.limit(length);
      endpoint.write(receiveBuffer);
      if (receiveBuffer.hasRemaining()) {
        pendingDelivery = true;
        return;
      }
    }
  }

  /** Runs timers; returns the next time this session needs to run. */
  int tick(int now) {
    int next = now + KEEPALIVE_MILLIS;
    if (turn != null) {
      next = earlier(next, turn.tick(now), now);
    }
    switch (phase) {
      case GATHERING -> {
        if (now - phaseStartedAt >= GATHER_TIMEOUT_MILLIS) {
          reflexiveDone = true;
          if (turn != null && !turn.isAllocated()) {
            turn = null;
          }
          finishGatheringIfReady(now);
        }
        next = earlier(next, phaseStartedAt + GATHER_TIMEOUT_MILLIS, now);
      }
      case WAITING_FOR_PEER, CHECKING -> {
        // Waiting for the answer is timed out by the signaling layer.
      }
      case CONNECTED -> {
        if (now - lastInboundAt >= SILENCE_TIMEOUT_MILLIS || kcp.state() < 0) {
          end(End.TIMED_OUT, now);
          return Integer.MAX_VALUE;
        }
        if (now - keepaliveAt >= 0) {
          sendKeepalive();
          keepaliveAt = now + KEEPALIVE_MILLIS;
        }
        if (now - kcpAt >= 0) {
          kcp.update(now);
          kcpAt = kcp.check(now);
          pump(now);
        }
        next = earlier(earlier(next, keepaliveAt, now), kcpAt, now);
      }
      case ENDED -> {
        return Integer.MAX_VALUE;
      }
    }
    if (checking && phase != Phase.CONNECTED) {
      int elapsed = now - checksStartedAt;
      if (elapsed >= CHECK_TIMEOUT_MILLIS) {
        end(End.UNREACHABLE, now);
        return Integer.MAX_VALUE;
      }
      if (now - nextCheckAt >= 0) {
        sendChecks(now);
        nextCheckAt =
            now
                + (elapsed < AGGRESSIVE_CHECK_MILLIS
                    ? CHECK_INTERVAL_MILLIS
                    : SLOW_CHECK_INTERVAL_MILLIS);
      }
      next = earlier(next, nextCheckAt, now);
    }
    return next;
  }

  @Override
  public void output(byte[] buffer, int length) {
    sendOnPath(selected, buffer, 0, length);
  }

  private void finishGatheringIfReady(int now) {
    boolean relayPending = turn != null && !turn.isAllocated();
    if (phase != Phase.GATHERING || !reflexiveDone || relayPending) {
      return;
    }
    phase = remoteKey != null ? Phase.CHECKING : Phase.WAITING_FOR_PEER;
    if (ConnectionLog.on()) {
      log("local candidates " + localCandidates);
    }
    listener.onGathered(
        this, new IceParameters(localUfrag, localPassword, List.copyOf(localCandidates)));
  }

  private void startChecks(int now) {
    checking = true;
    checksStartedAt = now;
    nextCheckAt = now;
  }

  private void sendChecks(int now) {
    boolean relayAllowed = now - checksStartedAt >= RELAY_DELAY_MILLIS;
    Pair nominee = null;
    for (int i = 0; i < pairCount; i++) {
      Pair pair = pairs[i];
      if (pair.nominating) {
        nominee = pair;
      }
    }
    if (role == Role.JOINER && nominee == null) {
      nominee = pickNominee(relayAllowed);
      if (nominee != null) {
        if (ConnectionLog.on()) {
          log("nominating " + nominee);
        }
        nominee.nominating = true;
        loop.random().nextBytes(nominee.transactionId);
      }
    }
    for (int i = 0; i < pairCount; i++) {
      Pair pair = pairs[i];
      boolean eligible = !pair.delayed || relayAllowed || pair.checkNow;
      if (pair == nominee || (!pair.succeeded && eligible)) {
        sendCheck(pair, pair == nominee);
        pair.checkNow = false;
      }
    }
  }

  /** Direct IPv6 first (no NAT on the path), then direct IPv4, then the relay. */
  private Pair pickNominee(boolean relayAllowed) {
    Pair direct = null;
    Pair relayed = null;
    for (int i = 0; i < pairCount; i++) {
      Pair pair = pairs[i];
      if (pair.succeeded) {
        if (!pair.relayed && pair.ipv6) {
          return pair;
        }
        if (!pair.relayed) {
          direct = direct == null ? pair : direct;
        } else {
          relayed = relayed == null ? pair : relayed;
        }
      }
    }
    return direct != null ? direct : relayAllowed ? relayed : null;
  }

  private void sendCheck(Pair pair, boolean useCandidate) {
    writer.begin(
        datagram,
        0,
        StunMessage.messageType(StunMessage.METHOD_BINDING, StunMessage.CLASS_REQUEST),
        pair.transactionId,
        0);
    writer.addUtf8(StunMessage.ATTR_USERNAME, remoteUsername);
    writer.addU32(StunMessage.ATTR_PRIORITY, pair.relayed ? PRIORITY_PREFLEX : PRIORITY_HOST);
    writer.addU64(
        role == Role.JOINER ? StunMessage.ATTR_ICE_CONTROLLING : StunMessage.ATTR_ICE_CONTROLLED,
        tieBreaker);
    if (useCandidate) {
      writer.addEmpty(StunMessage.ATTR_USE_CANDIDATE);
    }
    writer.addMessageIntegrity(remoteKey);
    writer.addFingerprint();
    sendOnPath(pair, datagram, 0, writer.length());
    if (pair.checksSent++ == 0 && ConnectionLog.on()) {
      log("first check sent to " + pair);
    }
  }

  private void onBindingRequest(
      StunReader reader, InetSocketAddress from, StunAddress peer, int now) {
    if (!reader.verifyFingerprint() || !reader.verifyIntegrity(localKey)) {
      return;
    }
    boolean relayed = from == null;
    Pair pair = findPair(from, peer, relayed);
    if (pair == null && remoteKey != null) {
      pair = addPair(relayed ? peer.toInetSocketAddress() : from, relayed, relayed);
      if (pair != null && relayed && turn != null) {
        turn.permit(pair.remoteStun, now);
      }
    }
    // Success response on the path the request came from (RFC 8445 §7.3.1).
    byte[] response = datagram;
    if (relayed) {
      scratchAddress.copyFrom(peer);
    } else {
      scratchAddress.set(from);
    }
    writer.begin(
        response,
        0,
        StunMessage.messageType(StunMessage.METHOD_BINDING, StunMessage.CLASS_SUCCESS),
        reader.buffer(),
        reader.offset() + 8);
    writer.addXorAddress(StunMessage.ATTR_XOR_MAPPED_ADDRESS, scratchAddress);
    writer.addMessageIntegrity(localKey);
    writer.addFingerprint();
    if (relayed) {
      if (turn != null) {
        turn.sendTo(scratchAddress, response, 0, writer.length());
      }
    } else {
      loop.send(response, 0, writer.length(), from);
    }
    if (pair == null) {
      if (ConnectionLog.on()) {
        log(
            "check from "
                + (relayed ? peer : from)
                + " answered (no pair yet: answer not received)");
      }
      return;
    }
    if (pair.requestsReceived++ == 0 && ConnectionLog.on()) {
      log("first check received from " + pair);
    }
    reader.rewind();
    boolean useCandidate = reader.find(StunMessage.ATTR_USE_CANDIDATE);
    if (role == Role.HOST && useCandidate && phase != Phase.CONNECTED) {
      select(pair, now);
    } else if (!pair.succeeded && checking && phase != Phase.CONNECTED) {
      // Triggered check (RFC 8445 §7.3.1.4): opens our NAT towards the peer right away.
      pair.checkNow = true;
      sendCheck(pair, false);
    }
  }

  private void onBindingSuccess(
      StunReader reader, InetSocketAddress from, StunAddress peer, int now) {
    Pair pair = null;
    for (int i = 0; i < pairCount; i++) {
      if (reader.transactionIdEquals(pairs[i].transactionId, 0)) {
        pair = pairs[i];
        break;
      }
    }
    if (pair == null
        || (from == null) != pair.relayed
        || !reader.verifyFingerprint()
        || !reader.verifyIntegrity(remoteKey)) {
      return;
    }
    if (pair.relayed) {
      // The peer's NAT may map it differently towards the relay: follow the real source.
      pair.remoteStun.copyFrom(peer);
    }
    pair.succeeded = true;
    if (pair.responsesReceived++ == 0 && ConnectionLog.on()) {
      log("check answered by " + pair + ": path works");
    }
    if (role == Role.JOINER && pair.nominating && phase != Phase.CONNECTED) {
      select(pair, now);
    } else if (role == Role.JOINER && phase != Phase.CONNECTED) {
      // Nominate now rather than at the next check round.
      nextCheckAt = now;
    }
  }

  private void select(Pair pair, int now) {
    if (ConnectionLog.on()) {
      log("connected over " + pair + " after " + (now - checksStartedAt) + " ms of checks");
    }
    selected = pair;
    phase = Phase.CONNECTED;
    lastInboundAt = now;
    keepaliveAt = now + KEEPALIVE_MILLIS;
    kcp = newStream(conv, this);
    kcp.update(now);
    kcpAt = now;
    if (pair.relayed && turn != null) {
      turn.bindChannel(pair.remoteStun, now);
    }
    listener.onConnected(this, pair.relayed);
  }

  private void sendKeepalive() {
    // Indications get no answer, so their id need not be unpredictable: a counter, without the
    // SecureRandom call (which allocates) every keepalive.
    nextIndicationId(scratchTransaction);
    writer.begin(
        datagram,
        0,
        StunMessage.messageType(StunMessage.METHOD_BINDING, StunMessage.CLASS_INDICATION),
        scratchTransaction,
        0);
    writer.addFingerprint();
    sendOnPath(selected, datagram, 0, writer.length());
  }

  private void sendGoodbye() {
    datagram[0] = (byte) conv;
    datagram[1] = (byte) (conv >>> 8);
    datagram[2] = (byte) (conv >>> 16);
    datagram[3] = (byte) (conv >>> 24);
    datagram[4] = GOODBYE_MARKER;
    for (int i = 0; i < GOODBYE_REPEATS; i++) {
      sendOnPath(selected, datagram, 0, GOODBYE_LENGTH);
    }
  }

  private void sendOnPath(Pair pair, byte[] data, int offset, int length) {
    if (pair.relayed) {
      if (turn != null) {
        turn.sendTo(pair.remoteStun, data, offset, length);
      }
    } else {
      loop.send(data, offset, length, pair.remote);
    }
  }

  private boolean isFromSelected(InetSocketAddress from, StunAddress peer) {
    if (selected == null) {
      return false;
    }
    return selected.relayed
        ? from == null && selected.remoteStun.equals(peer)
        : from != null && selected.remote.equals(from);
  }

  private void end(End end, int now) {
    if (phase == Phase.ENDED) {
      return;
    }
    if (ConnectionLog.on()) {
      logEnd(end, now);
    }
    phase = Phase.ENDED;
    if (endpoint != null) {
      endpoint.close();
    }
    if (turn != null) {
      turn.release(now);
    }
    loop.remove(this);
    listener.onEnded(this, end);
  }

  private Pair findPair(InetSocketAddress from, StunAddress peer, boolean relayed) {
    for (int i = 0; i < pairCount; i++) {
      Pair pair = pairs[i];
      if (pair.relayed == relayed
          && (relayed ? pair.remoteStun.equals(peer) : pair.remote.equals(from))) {
        return pair;
      }
    }
    return null;
  }

  private Pair addPair(InetSocketAddress remote, boolean relayed, boolean delayed) {
    if (pairCount == MAX_PAIRS) {
      return null;
    }
    for (int i = 0; i < pairCount; i++) {
      if (pairs[i].relayed == relayed && pairs[i].remote.equals(remote)) {
        return pairs[i];
      }
    }
    Pair pair = new Pair(remote, relayed, delayed);
    loop.random().nextBytes(pair.transactionId);
    pairs[pairCount++] = pair;
    return pair;
  }

  private void addCandidate(Candidate.Kind kind, StunAddress address) {
    InetSocketAddress socketAddress = address.toInetSocketAddress();
    String literal = literal(socketAddress.getAddress());
    for (Candidate existing : localCandidates) {
      if (existing.address().equals(literal) && existing.port() == socketAddress.getPort()) {
        return;
      }
    }
    if (localCandidates.size()
        < com.einsteinmods.opendoors.core.signaling.Validation.MAX_CANDIDATES) {
      localCandidates.add(new Candidate(kind, literal, socketAddress.getPort()));
    }
  }

  /** IP literal without an IPv6 scope suffix. */
  static String literal(InetAddress address) {
    String text = address.getHostAddress();
    int scope = text.indexOf('%');
    return scope < 0 ? text : text.substring(0, scope);
  }

  private static InetSocketAddress toSocketAddress(Candidate candidate) {
    try {
      // Candidate addresses are validated literals, so no name lookup happens here.
      return new InetSocketAddress(InetAddress.getByName(candidate.address()), candidate.port());
    } catch (UnknownHostException exception) {
      return null;
    }
  }

  private long indicationCounter;

  private void log(String message) {
    ConnectionLog.log("[" + role + " " + localUfrag + "] " + message);
  }

  private void logEnd(End end, int now) {
    StringBuilder line = new StringBuilder("ended: ").append(end);
    if (phase != Phase.CONNECTED) {
      line.append(
          checking ? " after " + (now - checksStartedAt) + " ms of checks" : " before checks");
    }
    for (int i = 0; i < pairCount; i++) {
      Pair pair = pairs[i];
      line.append("; ")
          .append(pair)
          .append(" sent ")
          .append(pair.checksSent)
          .append(" got ")
          .append(pair.requestsReceived)
          .append(" checks, ")
          .append(pair.responsesReceived)
          .append(" answers");
    }
    log(line.toString());
  }

  /** A KCP stream as every session runs it, with its pools reserved to {@link #MAX_SEGMENTS}. */
  static Kcp newStream(int conversation, Kcp.Output output) {
    Kcp stream = new Kcp(conversation, output);
    stream.setStreamMode(true);
    stream.setMtu(MTU);
    stream.nodelay(1, KCP_INTERVAL_MILLIS, KCP_RESEND, 1);
    stream.wndsize(KCP_WINDOW, KCP_WINDOW);
    stream.reserve(MAX_SEGMENTS, ACK_CAPACITY);
    return stream;
  }

  private void nextIndicationId(byte[] target) {
    long value = ++indicationCounter;
    for (int i = 0; i < Long.BYTES; i++) {
      target[target.length - 1 - i] = (byte) (value >>> (8 * i));
    }
  }

  private static int readConv(byte[] data, int offset) {
    return (data[offset] & 0xFF)
        | (data[offset + 1] & 0xFF) << 8
        | (data[offset + 2] & 0xFF) << 16
        | (data[offset + 3] & 0xFF) << 24;
  }

  private static int earlier(int current, int candidate, int now) {
    return candidate - now < current - now ? candidate : current;
  }
}
