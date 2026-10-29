package com.einsteinmods.opendoors.core.net;

import com.einsteinmods.opendoors.core.stun.Demux;
import com.einsteinmods.opendoors.core.stun.StunAddress;
import com.einsteinmods.opendoors.core.stun.StunIntegrity;
import com.einsteinmods.opendoors.core.stun.StunMessage;
import com.einsteinmods.opendoors.core.stun.StunReader;
import com.einsteinmods.opendoors.core.stun.StunWriter;
import java.net.InetSocketAddress;
import java.util.random.RandomGenerator;

/**
 * TURN client over UDP (RFC 8656) for one relayed path: Allocate with the long-term credential
 * challenge (§7.2, 401 then retry with REALM/NONCE), CreatePermission for the peer's addresses
 * (§9), ChannelBind to the selected peer (§12) and Refresh before each lifetime ends (§7.3, §9.1,
 * §12.1). One request is in flight at a time; requests are retransmitted as RFC 8489 §6.2.1.
 * Network thread only.
 */
public final class TurnAllocation {
  /** Results reported to the owning session. */
  public interface Events {
    void onAllocated(StunAddress relayed);

    void onFailed();

    /** A datagram the peer sent to our relayed address. */
    void onPeerData(StunAddress peer, byte[] data, int offset, int length);
  }

  private static final int LIFETIME_SECONDS = 600;
  private static final int REFRESH_MARGIN_MILLIS = 60_000;
  private static final int PERMISSION_REFRESH_MILLIS = 240_000;
  private static final int CHANNEL_REFRESH_MILLIS = 540_000;
  private static final int FIRST_RETRANSMIT_MILLIS = 250;
  private static final int MAX_TRANSMISSIONS = 6;
  private static final int MAX_PERMISSIONS = 8;
  public static final int CHANNEL = Demux.MIN_CHANNEL;
  private static final int MILLIS_PER_SECOND = 1_000;
  private static final int BUFFER_SIZE = 1_600;

  private enum Request {
    NONE,
    ALLOCATE,
    PERMISSION,
    CHANNEL_BIND,
    REFRESH,
    RELEASE
  }

  private final InetSocketAddress server;
  private final String username;
  private final String password;
  private final DatagramSender sender;
  private final Events events;
  private final RandomGenerator random;
  private final StunWriter writer = new StunWriter();
  private final byte[] buffer = new byte[BUFFER_SIZE];
  private final byte[] transactionId = new byte[StunMessage.TRANSACTION_ID_SIZE];
  private final StunAddress relayed = new StunAddress();
  private final StunAddress[] permissions = new StunAddress[MAX_PERMISSIONS];
  private final StunAddress channelPeer = new StunAddress();
  private final StunAddress scratch = new StunAddress();
  private final byte[] transactionScratch = new byte[StunMessage.TRANSACTION_ID_SIZE];
  private int permissionCount;
  private long indicationCounter;
  private String realm;
  private String nonce;
  private StunIntegrity key;

  private Request inFlight = Request.NONE;
  private int transmissions;
  private int retransmitAt;
  private int retransmitInterval;
  private boolean allocated;
  private boolean failed;
  private boolean permissionsWanted;
  private boolean channelWanted;
  private boolean channelBound;
  private int allocationRefreshAt;
  private int permissionRefreshAt;
  private int channelRefreshAt;

  public TurnAllocation(
      InetSocketAddress server,
      String username,
      String password,
      DatagramSender sender,
      Events events,
      RandomGenerator random) {
    this.server = server;
    this.username = username;
    this.password = password;
    this.sender = sender;
    this.events = events;
    this.random = random;
    for (int i = 0; i < MAX_PERMISSIONS; i++) {
      permissions[i] = new StunAddress();
    }
  }

  public InetSocketAddress server() {
    return server;
  }

  public boolean isAllocated() {
    return allocated;
  }

  public StunAddress relayedAddress() {
    return relayed;
  }

  public void start(int now) {
    startRequest(Request.ALLOCATE, now);
  }

  /** Allows traffic from these peers (IP only, ports ignored by the server). */
  public void permit(StunAddress peer, int now) {
    for (int i = 0; i < permissionCount; i++) {
      if (sameIp(permissions[i], peer)) {
        return;
      }
    }
    if (permissionCount < MAX_PERMISSIONS) {
      permissions[permissionCount++].copyFrom(peer);
      permissionsWanted = true;
      pump(now);
    }
  }

  /** Binds the channel to the selected peer so data uses 4-byte ChannelData headers. */
  public void bindChannel(StunAddress peer, int now) {
    if (!channelPeer.equals(peer)) {
      channelPeer.copyFrom(peer);
      channelBound = false;
      channelWanted = true;
      pump(now);
    }
  }

  /** Sends {@code data} to {@code peer} through the relay. */
  public void sendTo(StunAddress peer, byte[] data, int offset, int length) {
    if (!allocated) {
      return;
    }
    if (channelBound && channelPeer.equals(peer)) {
      Demux.writeChannelDataHeader(buffer, 0, CHANNEL, length);
      System.arraycopy(data, offset, buffer, Demux.CHANNEL_DATA_HEADER_SIZE, length);
      sender.send(buffer, 0, Demux.CHANNEL_DATA_HEADER_SIZE + length, server);
      return;
    }
    // A Send indication gets no answer: a counter id avoids SecureRandom's allocation per datagram.
    long id = ++indicationCounter;
    for (int i = 0; i < Long.BYTES; i++) {
      transactionScratch[transactionScratch.length - 1 - i] = (byte) (id >>> (8 * i));
    }
    writer
        .begin(
            buffer,
            0,
            StunMessage.messageType(StunMessage.METHOD_SEND, StunMessage.CLASS_INDICATION),
            transactionScratch,
            0)
        .addXorAddress(StunMessage.ATTR_XOR_PEER_ADDRESS, peer)
        .addBytes(StunMessage.ATTR_DATA, data, offset, length);
    sender.send(buffer, 0, writer.length(), server);
  }

  /** Handles a STUN message from the TURN server; false when it is not ours. */
  public boolean onStun(StunReader reader, int now) {
    int method = reader.method();
    int messageClass = reader.messageClass();
    if (method == StunMessage.METHOD_DATA && messageClass == StunMessage.CLASS_INDICATION) {
      if (reader.find(StunMessage.ATTR_XOR_PEER_ADDRESS) && reader.readXorAddress(scratch)) {
        reader.rewind();
        if (reader.find(StunMessage.ATTR_DATA)) {
          events.onPeerData(scratch, reader.buffer(), reader.valueOffset(), reader.valueLength());
        }
      }
      return true;
    }
    if (inFlight == Request.NONE || !reader.transactionIdEquals(transactionId, 0)) {
      return false;
    }
    if (messageClass == StunMessage.CLASS_ERROR) {
      onError(reader, now);
    } else if (messageClass == StunMessage.CLASS_SUCCESS) {
      if (key != null && !reader.verifyIntegrity(key)) {
        return true;
      }
      onSuccess(reader, now);
    }
    return true;
  }

  /** Handles ChannelData from the TURN server. */
  public void onChannelData(byte[] data, int offset, int length) {
    if (channelBound
        && Demux.isValidChannelData(data, offset, length)
        && Demux.channelNumber(data, offset) == CHANNEL) {
      events.onPeerData(
          channelPeer,
          data,
          offset + Demux.CHANNEL_DATA_HEADER_SIZE,
          Demux.channelDataLength(data, offset));
    }
  }

  /** Retransmits and refreshes; returns the next time it needs to run. */
  public int tick(int now) {
    if (failed) {
      return Integer.MAX_VALUE;
    }
    if (inFlight != Request.NONE && now - retransmitAt >= 0) {
      if (transmissions >= MAX_TRANSMISSIONS) {
        Request lost = inFlight;
        inFlight = Request.NONE;
        if (lost == Request.ALLOCATE) {
          fail();
          return Integer.MAX_VALUE;
        }
      } else {
        transmit(now);
      }
    }
    if (allocated && inFlight == Request.NONE) {
      if (now - allocationRefreshAt >= 0) {
        startRequest(Request.REFRESH, now);
      } else if (permissionCount > 0 && now - permissionRefreshAt >= 0) {
        permissionsWanted = true;
      } else if (channelBound && now - channelRefreshAt >= 0) {
        channelWanted = true;
      }
      pump(now);
    }
    int next = allocated ? allocationRefreshAt : now + FIRST_RETRANSMIT_MILLIS;
    if (inFlight != Request.NONE) {
      next = earlier(next, retransmitAt, now);
    }
    if (permissionCount > 0) {
      next = earlier(next, permissionRefreshAt, now);
    }
    if (channelBound) {
      next = earlier(next, channelRefreshAt, now);
    }
    return next;
  }

  /** Releases the allocation (Refresh with lifetime 0, best effort). */
  public void release(int now) {
    if (allocated) {
      inFlight = Request.NONE;
      startRequest(Request.RELEASE, now);
      allocated = false;
    }
  }

  private void pump(int now) {
    if (!allocated || inFlight != Request.NONE) {
      return;
    }
    if (permissionsWanted) {
      startRequest(Request.PERMISSION, now);
    } else if (channelWanted) {
      startRequest(Request.CHANNEL_BIND, now);
    }
  }

  private void startRequest(Request request, int now) {
    inFlight = request;
    transmissions = 0;
    retransmitInterval = FIRST_RETRANSMIT_MILLIS;
    random.nextBytes(transactionId);
    transmit(now);
  }

  private void transmit(int now) {
    int method =
        switch (inFlight) {
          case ALLOCATE -> StunMessage.METHOD_ALLOCATE;
          case PERMISSION -> StunMessage.METHOD_CREATE_PERMISSION;
          case CHANNEL_BIND -> StunMessage.METHOD_CHANNEL_BIND;
          case REFRESH, RELEASE -> StunMessage.METHOD_REFRESH;
          case NONE -> throw new IllegalStateException("no request");
        };
    writer.begin(
        buffer, 0, StunMessage.messageType(method, StunMessage.CLASS_REQUEST), transactionId, 0);
    switch (inFlight) {
      case ALLOCATE -> {
        writer.addRequestedTransportUdp();
        writer.addU32(StunMessage.ATTR_LIFETIME, LIFETIME_SECONDS);
      }
      case PERMISSION -> {
        for (int i = 0; i < permissionCount; i++) {
          writer.addXorAddress(StunMessage.ATTR_XOR_PEER_ADDRESS, permissions[i]);
        }
      }
      case CHANNEL_BIND -> {
        writer.addChannelNumber(CHANNEL);
        writer.addXorAddress(StunMessage.ATTR_XOR_PEER_ADDRESS, channelPeer);
      }
      case REFRESH -> writer.addU32(StunMessage.ATTR_LIFETIME, LIFETIME_SECONDS);
      case RELEASE -> writer.addU32(StunMessage.ATTR_LIFETIME, 0);
      case NONE -> throw new IllegalStateException("no request");
    }
    if (key != null) {
      writer.addUtf8(StunMessage.ATTR_USERNAME, username);
      writer.addUtf8(StunMessage.ATTR_REALM, realm);
      writer.addUtf8(StunMessage.ATTR_NONCE, nonce);
      writer.addMessageIntegrity(key);
    }
    sender.send(buffer, 0, writer.length(), server);
    transmissions++;
    retransmitAt = now + retransmitInterval;
    retransmitInterval *= 2;
    if (inFlight == Request.RELEASE) {
      inFlight = Request.NONE;
    }
  }

  private void onError(StunReader reader, int now) {
    int code = reader.find(StunMessage.ATTR_ERROR_CODE) ? reader.readErrorCode() : 0;
    boolean challenge =
        code == StunMessage.ERROR_UNAUTHORIZED || code == StunMessage.ERROR_STALE_NONCE;
    boolean firstChallenge = key == null || code == StunMessage.ERROR_STALE_NONCE;
    if (challenge && firstChallenge && readRealmAndNonce(reader)) {
      Request retry = inFlight;
      startRequest(retry, now);
      return;
    }
    Request failedRequest = inFlight;
    inFlight = Request.NONE;
    if (failedRequest == Request.ALLOCATE) {
      fail();
    }
  }

  private boolean readRealmAndNonce(StunReader reader) {
    reader.rewind();
    if (!reader.find(StunMessage.ATTR_NONCE)) {
      return false;
    }
    nonce = reader.valueAsString();
    reader.rewind();
    if (reader.find(StunMessage.ATTR_REALM)) {
      String newRealm = reader.valueAsString();
      if (key == null || !newRealm.equals(realm)) {
        realm = newRealm;
        key = StunIntegrity.longTerm(username, realm, password);
      }
    }
    return key != null;
  }

  private void onSuccess(StunReader reader, int now) {
    Request done = inFlight;
    inFlight = Request.NONE;
    switch (done) {
      case ALLOCATE -> {
        reader.rewind();
        if (!reader.find(StunMessage.ATTR_XOR_RELAYED_ADDRESS) || !reader.readXorAddress(relayed)) {
          fail();
          return;
        }
        allocated = true;
        allocationRefreshAt = now + lifetimeMillis(reader);
        events.onAllocated(relayed);
      }
      case PERMISSION -> {
        permissionsWanted = false;
        permissionRefreshAt = now + PERMISSION_REFRESH_MILLIS;
      }
      case CHANNEL_BIND -> {
        channelWanted = false;
        channelBound = true;
        channelRefreshAt = now + CHANNEL_REFRESH_MILLIS;
      }
      case REFRESH -> allocationRefreshAt = now + lifetimeMillis(reader);
      case RELEASE, NONE -> {
        // Nothing waits for these.
      }
    }
    pump(now);
  }

  private int lifetimeMillis(StunReader reader) {
    reader.rewind();
    int seconds = reader.find(StunMessage.ATTR_LIFETIME) ? reader.readU32() : LIFETIME_SECONDS;
    return Math.max(seconds * MILLIS_PER_SECOND - REFRESH_MARGIN_MILLIS, REFRESH_MARGIN_MILLIS);
  }

  private void fail() {
    failed = true;
    allocated = false;
    events.onFailed();
  }

  private static boolean sameIp(StunAddress first, StunAddress second) {
    if (first.family() != second.family()) {
      return false;
    }
    for (int i = 0; i < first.addressLength(); i++) {
      if (first.addressByte(i) != second.addressByte(i)) {
        return false;
      }
    }
    return true;
  }

  private static int earlier(int current, int candidate, int now) {
    return candidate - now < current - now ? candidate : current;
  }
}
