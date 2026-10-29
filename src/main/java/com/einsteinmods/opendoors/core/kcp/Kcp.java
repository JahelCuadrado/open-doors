package com.einsteinmods.opendoors.core.kcp;

/**
 * KCP reliable ARQ protocol: a Java port of {@code ikcp.c} by skywind3000.
 *
 * <p>Original work: KCP - A Better ARQ Protocol Implementation, Copyright (c) 2017 Lin Wei
 * (skywind3000 at gmail.com), https://github.com/skywind3000/kcp, released under the MIT License:
 *
 * <pre>
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 * </pre>
 *
 * <p>Port notes: behaviour follows {@code ikcp.c} with {@code IKCP_FASTACK_CONSERVE} defined and no
 * pluggable congestion control ({@code ccops}). Segments and their data buffers are pooled, the
 * output buffer and ack list are reused, so a warmed-up instance allocates nothing in steady state.
 * Wire format is little endian, 24-byte header, as the original. All 32-bit counters and timestamps
 * are Java {@code int}s compared with wrap-around differences, like {@code _itimediff}. Not thread
 * safe: one owner thread.
 */
public final class Kcp {
  /** Receives every datagram KCP wants sent; the buffer is reused after the call returns. */
  @FunctionalInterface
  public interface Output {
    void output(byte[] buffer, int length);
  }

  public static final int OVERHEAD = 24;

  private static final int RTO_NDL = 30;
  private static final int RTO_MIN = 100;
  private static final int RTO_DEF = 200;
  private static final int RTO_MAX = 60000;
  private static final int CMD_PUSH = 81;
  private static final int CMD_ACK = 82;
  private static final int CMD_WASK = 83;
  private static final int CMD_WINS = 84;
  private static final int ASK_SEND = 1;
  private static final int ASK_TELL = 2;
  private static final int WND_SND = 32;
  private static final int WND_RCV = 128;
  private static final int MTU_DEF = 1400;
  private static final int INTERVAL = 100;
  private static final int DEADLINK = 20;
  private static final int THRESH_INIT = 2;
  private static final int THRESH_MIN = 2;
  private static final int PROBE_INIT = 5000;
  private static final int PROBE_LIMIT = 120000;
  private static final int FASTACK_LIMIT = 5;
  private static final int CLOCK_JUMP_LIMIT = 10000;
  private static final int MIN_MTU = 50;
  private static final int MAX_INTERVAL = 5000;
  private static final int MIN_INTERVAL = 10;
  private static final int INITIAL_ACK_BLOCK = 8;

  /** Intrusive doubly linked list node, as {@code IKCPSEG} with its {@code IQUEUEHEAD}. */
  private static final class Segment {
    Segment prev;
    Segment next;
    int frg;
    int wnd;
    int ts;
    int sn;
    int len;
    int resendts;
    int rto;
    int fastack;
    int xmit;
    byte[] data;

    Segment(int capacity) {
      this.prev = this;
      this.next = this;
      this.data = new byte[capacity];
    }

    boolean isEmptyHead() {
      return next == this;
    }

    void unlink() {
      prev.next = next;
      next.prev = prev;
      prev = this;
      next = this;
    }

    /** Inserts {@code node} right after this node ({@code iqueue_add}). */
    void addAfter(Segment node) {
      node.prev = this;
      node.next = next;
      next.prev = node;
      next = node;
    }

    /** Inserts {@code node} right before this head, at the list tail ({@code iqueue_add_tail}). */
    void addTail(Segment node) {
      prev.addAfter(node);
    }
  }

  private final int conv;
  private final Output output;
  private int mtu = MTU_DEF;
  private int mss = MTU_DEF - OVERHEAD;
  private int state;
  private int sndUna;
  private int sndNxt;
  private int rcvNxt;
  private int ssthresh = THRESH_INIT;
  private int rxRttval;
  private int rxSrtt;
  private int rxRto = RTO_DEF;
  private int rxMinrto = RTO_MIN;
  private int sndWnd = WND_SND;
  private int rcvWnd = WND_RCV;
  private int rmtWnd = WND_RCV;
  private int cwnd;
  private int probe;
  private int current;
  private int interval = INTERVAL;
  private int tsFlush = INTERVAL;
  private int xmit;
  private int nsndBuf;
  private int nrcvQue;
  private int nsndQue;
  private int nodelay;
  private boolean updated;
  private int tsProbe;
  private int probeWait;
  private int deadLink = DEADLINK;
  private int incr;
  private int fastresend;
  private int fastlimit = FASTACK_LIMIT;
  private boolean nocwnd;
  private boolean stream;

  private final Segment sndQueue = new Segment(0);
  private final Segment rcvQueue = new Segment(0);
  private final Segment sndBuf = new Segment(0);
  private final Segment rcvBuf = new Segment(0);
  private Segment pool;

  private int[] ackList = new int[INITIAL_ACK_BLOCK * 2];
  private int ackCount;
  private byte[] buffer = new byte[(MTU_DEF + OVERHEAD) * 3];

  /** {@code ikcp_create}: both ends must use the same {@code conv}. */
  public Kcp(int conv, Output output) {
    this.conv = conv;
    this.output = output;
  }

  public int conv() {
    return conv;
  }

  public int mss() {
    return mss;
  }

  /** {@code -1} once a segment was retransmitted {@code dead_link} times; 0 otherwise. */
  public int state() {
    return state;
  }

  /** Smoothed round trip time in milliseconds (0 until the first ack). */
  public int smoothedRtt() {
    return rxSrtt;
  }

  /** Current retransmission timeout in milliseconds. */
  public int retransmitTimeout() {
    return rxRto;
  }

  /** Total retransmissions caused by timeouts. */
  public int retransmissions() {
    return xmit;
  }

  /** Stream mode merges small sends into MSS-sized segments (no message boundaries). */
  public void setStreamMode(boolean streamMode) {
    this.stream = streamMode;
  }

  /** {@code ikcp_setmtu}: returns 0, or -1 for an MTU below 50 bytes. */
  public int setMtu(int newMtu) {
    if (newMtu < MIN_MTU || newMtu < OVERHEAD) {
      return -1;
    }
    buffer = new byte[(newMtu + OVERHEAD) * 3];
    mtu = newMtu;
    mss = newMtu - OVERHEAD;
    return 0;
  }

  /** {@code ikcp_interval}: internal flush interval, clamped to 10..5000 ms. */
  public void setInterval(int newInterval) {
    interval = clampInterval(newInterval);
  }

  /**
   * {@code ikcp_nodelay}: fastest is {@code nodelay(1, 10, 2, 1)}. Negative arguments leave the
   * setting unchanged.
   */
  public void nodelay(int nodelayMode, int newInterval, int resend, int noCongestionWindow) {
    if (nodelayMode >= 0) {
      nodelay = nodelayMode;
      rxMinrto = nodelayMode != 0 ? RTO_NDL : RTO_MIN;
    }
    if (newInterval >= 0) {
      interval = clampInterval(newInterval);
    }
    if (resend >= 0) {
      fastresend = resend;
    }
    if (noCongestionWindow >= 0) {
      nocwnd = noCongestionWindow != 0;
    }
  }

  /** {@code ikcp_wndsize}: receive window is at least 128 (the maximum fragment count). */
  public void wndsize(int sendWindow, int receiveWindow) {
    if (sendWindow > 0) {
      sndWnd = sendWindow;
    }
    if (receiveWindow > 0) {
      rcvWnd = Math.max(receiveWindow, WND_RCV);
    }
  }

  /** {@code ikcp_waitsnd}: segments waiting to be sent or acknowledged. */
  public int waitSnd() {
    return nsndBuf + nsndQue;
  }

  /** {@code ikcp_peeksize}: size of the next whole message, or -1 if none is complete. */
  public int peekSize() {
    if (rcvQueue.isEmptyHead()) {
      return -1;
    }
    Segment seg = rcvQueue.next;
    if (seg.frg == 0) {
      return seg.len;
    }
    if (nrcvQue < seg.frg + 1) {
      return -1;
    }
    int length = 0;
    for (Segment p = rcvQueue.next; p != rcvQueue; p = p.next) {
      length += p.len;
      if (p.frg == 0) {
        break;
      }
    }
    return length;
  }

  /**
   * {@code ikcp_recv}: copies the next message into {@code dst}. Returns its size, -1 if nothing is
   * ready, -2 if the message is incomplete, -3 if {@code capacity} is too small.
   */
  public int recv(byte[] dst, int offset, int capacity) {
    if (rcvQueue.isEmptyHead()) {
      return -1;
    }
    int peeksize = peekSize();
    if (peeksize < 0) {
      return -2;
    }
    if (peeksize > capacity) {
      return -3;
    }
    boolean recover = nrcvQue >= rcvWnd;
    int length = 0;
    for (Segment p = rcvQueue.next; p != rcvQueue; ) {
      Segment seg = p;
      p = p.next;
      System.arraycopy(seg.data, 0, dst, offset + length, seg.len);
      length += seg.len;
      int fragment = seg.frg;
      seg.unlink();
      release(seg);
      nrcvQue--;
      if (fragment == 0) {
        break;
      }
    }
    moveReceivedToQueue();
    if (nrcvQue < rcvWnd && recover) {
      probe |= ASK_TELL;
    }
    return length;
  }

  /**
   * {@code ikcp_send}: queues {@code length} bytes. Returns the bytes queued (stream mode may queue
   * fewer), -1 for a negative length, -2 if the message needs 128 or more fragments.
   */
  public int send(byte[] src, int offset, int length) {
    if (length < 0) {
      return -1;
    }
    int position = offset;
    int remaining = length;
    int sent = 0;
    if (stream && !sndQueue.isEmptyHead()) {
      Segment old = sndQueue.prev;
      if (old.len < mss) {
        int extend = Math.min(remaining, mss - old.len);
        ensureCapacity(old, old.len + extend);
        System.arraycopy(src, position, old.data, old.len, extend);
        old.len += extend;
        old.frg = 0;
        position += extend;
        remaining -= extend;
        sent = extend;
      }
    }
    if (stream && remaining <= 0) {
      return sent;
    }
    int count = remaining <= mss ? 1 : (remaining + mss - 1) / mss;
    if (count >= WND_RCV) {
      return stream && sent > 0 ? sent : -2;
    }
    for (int i = 0; i < count; i++) {
      int size = Math.min(remaining, mss);
      Segment seg = obtain(size);
      if (size > 0) {
        System.arraycopy(src, position, seg.data, 0, size);
      }
      seg.len = size;
      seg.frg = stream ? 0 : count - i - 1;
      sndQueue.addTail(seg);
      nsndQue++;
      position += size;
      remaining -= size;
      sent += size;
    }
    return sent;
  }

  /**
   * {@code ikcp_input}: feeds one received datagram. Returns 0, -1 for a short datagram or a {@code
   * conv} mismatch, -2 for a truncated segment, -3 for an unknown command.
   */
  public int input(byte[] data, int offset, int size) {
    int prevUna = sndUna;
    int maxack = 0;
    int latestTs = 0;
    boolean flag = false;
    if (data == null || size < OVERHEAD) {
      return -1;
    }
    int position = offset;
    int remaining = size;
    while (remaining >= OVERHEAD) {
      int segConv = readInt(data, position);
      if (segConv != conv) {
        return -1;
      }
      int cmd = data[position + 4] & 0xff;
      int frg = data[position + 5] & 0xff;
      int wnd = (data[position + 6] & 0xff) | (data[position + 7] & 0xff) << 8;
      int ts = readInt(data, position + 8);
      int sn = readInt(data, position + 12);
      int una = readInt(data, position + 16);
      int len = readInt(data, position + 20);
      position += OVERHEAD;
      remaining -= OVERHEAD;
      if (len < 0 || remaining < len) {
        return -2;
      }
      if (cmd != CMD_PUSH && cmd != CMD_ACK && cmd != CMD_WASK && cmd != CMD_WINS) {
        return -3;
      }
      rmtWnd = wnd;
      parseUna(una);
      shrinkBuf();
      if (cmd == CMD_ACK) {
        if (current - ts >= 0) {
          updateAck(current - ts);
        }
        parseAck(sn);
        shrinkBuf();
        if (!flag) {
          flag = true;
          maxack = sn;
          latestTs = ts;
        } else if (sn - maxack > 0 && ts - latestTs > 0) {
          maxack = sn;
          latestTs = ts;
        }
      } else if (cmd == CMD_PUSH) {
        if (sn - (rcvNxt + rcvWnd) < 0) {
          pushAck(sn, ts);
          if (sn - rcvNxt >= 0) {
            Segment seg = obtain(len);
            seg.frg = frg;
            seg.wnd = wnd;
            seg.ts = ts;
            seg.sn = sn;
            seg.len = len;
            if (len > 0) {
              System.arraycopy(data, position, seg.data, 0, len);
            }
            parseData(seg);
          }
        }
      } else if (cmd == CMD_WASK) {
        probe |= ASK_TELL;
      }
      position += len;
      remaining -= len;
    }
    if (flag) {
      parseFastack(maxack, latestTs);
    }
    if (sndUna - prevUna > 0 && cwnd < rmtWnd) {
      if (cwnd < ssthresh) {
        cwnd++;
        incr += mss;
      } else {
        if (incr < mss) {
          incr = mss;
        }
        incr += (mss * mss) / incr + (mss / 16);
        if ((cwnd + 1) * mss <= incr) {
          cwnd = (incr + mss - 1) / (mss > 0 ? mss : 1);
        }
      }
      if (cwnd > rmtWnd) {
        cwnd = rmtWnd;
        incr = rmtWnd * mss;
      }
    }
    return 0;
  }

  /**
   * {@code ikcp_update}: call every {@code interval} ms or at {@link #check}; {@code now} is a
   * millisecond clock (wraps like the original 32-bit timestamp).
   */
  public void update(int now) {
    current = now;
    if (!updated) {
      updated = true;
      tsFlush = current;
    }
    int slap = current - tsFlush;
    if (slap >= CLOCK_JUMP_LIMIT || slap < -CLOCK_JUMP_LIMIT) {
      tsFlush = current;
      slap = 0;
    }
    if (slap >= 0) {
      tsFlush += interval;
      if (current - tsFlush >= 0) {
        tsFlush = current + interval;
      }
      flush();
    }
  }

  /** {@code ikcp_check}: the time at which {@link #update} should next be called. */
  public int check(int now) {
    int flushAt = tsFlush;
    if (!updated) {
      return now;
    }
    if (now - flushAt >= CLOCK_JUMP_LIMIT || now - flushAt < -CLOCK_JUMP_LIMIT) {
      flushAt = now;
    }
    if (now - flushAt >= 0) {
      return now;
    }
    int tmFlush = flushAt - now;
    int tmPacket = Integer.MAX_VALUE;
    for (Segment p = sndBuf.next; p != sndBuf; p = p.next) {
      int diff = p.resendts - now;
      if (diff <= 0) {
        return now;
      }
      if (diff < tmPacket) {
        tmPacket = diff;
      }
    }
    int minimal = Math.min(tmPacket, tmFlush);
    if (minimal >= interval) {
      minimal = interval;
    }
    return now + minimal;
  }

  /** {@code ikcp_flush}: sends pending acks, probes and data now (after the first update). */
  public void flush() {
    if (!updated) {
      return;
    }
    int now = current;
    int priorCwnd = cwnd;
    int unusedWindow = nrcvQue < rcvWnd ? rcvWnd - nrcvQue : 0;
    int ptr = 0;
    for (int i = 0; i < ackCount; i++) {
      ptr = flushIfFull(ptr, OVERHEAD);
      ptr = encodeHeader(ptr, CMD_ACK, 0, unusedWindow, ackList[i * 2 + 1], ackList[i * 2], 0);
    }
    ackCount = 0;
    if (rmtWnd == 0) {
      if (probeWait == 0) {
        probeWait = PROBE_INIT;
        tsProbe = current + probeWait;
      } else if (current - tsProbe >= 0) {
        if (probeWait < PROBE_INIT) {
          probeWait = PROBE_INIT;
        }
        probeWait += probeWait / 2;
        if (probeWait > PROBE_LIMIT) {
          probeWait = PROBE_LIMIT;
        }
        tsProbe = current + probeWait;
        probe |= ASK_SEND;
      }
    } else {
      tsProbe = 0;
      probeWait = 0;
    }
    if ((probe & ASK_SEND) != 0) {
      ptr = flushIfFull(ptr, OVERHEAD);
      ptr = encodeHeader(ptr, CMD_WASK, 0, unusedWindow, 0, 0, 0);
    }
    if ((probe & ASK_TELL) != 0) {
      ptr = flushIfFull(ptr, OVERHEAD);
      ptr = encodeHeader(ptr, CMD_WINS, 0, unusedWindow, 0, 0, 0);
    }
    probe = 0;

    int window = Math.min(sndWnd, rmtWnd);
    if (!nocwnd) {
      window = Math.min(cwnd, window);
    }
    while (sndNxt - (sndUna + window) < 0 && !sndQueue.isEmptyHead()) {
      Segment seg = sndQueue.next;
      seg.unlink();
      sndBuf.addTail(seg);
      nsndQue--;
      nsndBuf++;
      seg.wnd = unusedWindow;
      seg.ts = now;
      seg.sn = sndNxt++;
      seg.resendts = now;
      seg.rto = rxRto;
      seg.fastack = 0;
      seg.xmit = 0;
    }

    int resent = fastresend > 0 ? fastresend : Integer.MAX_VALUE;
    int rtomin = nodelay == 0 ? rxRto >> 3 : 0;
    int change = 0;
    boolean lost = false;
    for (Segment seg = sndBuf.next; seg != sndBuf; seg = seg.next) {
      boolean needsend = false;
      if (seg.xmit == 0) {
        needsend = true;
        seg.xmit++;
        seg.rto = rxRto;
        seg.resendts = now + seg.rto + rtomin;
      } else if (now - seg.resendts >= 0) {
        needsend = true;
        seg.xmit++;
        xmit++;
        if (nodelay == 0) {
          seg.rto += Math.max(seg.rto, rxRto);
        } else {
          int step = nodelay < 2 ? seg.rto : rxRto;
          seg.rto += step / 2;
        }
        seg.resendts = now + seg.rto;
        lost = true;
      } else if (seg.fastack >= resent && (seg.xmit <= fastlimit || fastlimit <= 0)) {
        needsend = true;
        seg.xmit++;
        seg.fastack = 0;
        seg.resendts = now + seg.rto;
        change++;
      }
      if (needsend) {
        seg.ts = now;
        seg.wnd = unusedWindow;
        ptr = flushIfFull(ptr, OVERHEAD + seg.len);
        ptr = encodeHeader(ptr, CMD_PUSH, seg.frg, seg.wnd, seg.ts, seg.sn, seg.len);
        if (seg.len > 0) {
          System.arraycopy(seg.data, 0, buffer, ptr, seg.len);
          ptr += seg.len;
        }
        if (seg.xmit >= deadLink) {
          state = -1;
        }
      }
    }
    if (ptr > 0) {
      output.output(buffer, ptr);
    }
    if (change != 0) {
      int inflight = sndNxt - sndUna;
      ssthresh = Math.max(inflight / 2, THRESH_MIN);
      cwnd = ssthresh + resent;
      incr = cwnd * mss;
    }
    if (lost) {
      ssthresh = Math.max(priorCwnd / 2, THRESH_MIN);
      cwnd = 1;
      incr = mss;
    }
    if (cwnd < 1) {
      cwnd = 1;
      incr = mss;
    }
  }

  /** Sends just the pending acks (the ack part of {@code ikcp_flush}). */
  private void flushAcks() {
    int unusedWindow = nrcvQue < rcvWnd ? rcvWnd - nrcvQue : 0;
    int ptr = 0;
    for (int i = 0; i < ackCount; i++) {
      ptr = flushIfFull(ptr, OVERHEAD);
      ptr = encodeHeader(ptr, CMD_ACK, 0, unusedWindow, ackList[i * 2 + 1], ackList[i * 2], 0);
    }
    ackCount = 0;
    if (ptr > 0) {
      output.output(buffer, ptr);
    }
  }

  /**
   * Pre-allocates {@code segments} pooled segments (data capacity {@code mss}) and room for {@code
   * acks} pending acks, so a stream whose queues never exceed those bounds allocates nothing after
   * this call, however late the caller gets to run. Not part of ikcp: it grows lazily.
   */
  public void reserve(int segments, int acks) {
    for (int i = 0; i < segments; i++) {
      release(new Segment(mss));
    }
    if (acks * 2 > ackList.length) {
      ackList = new int[acks * 2];
    }
  }

  private int flushIfFull(int ptr, int need) {
    if (ptr + need > mtu) {
      output.output(buffer, ptr);
      return 0;
    }
    return ptr;
  }

  private int encodeHeader(int ptr, int cmd, int frg, int wnd, int ts, int sn, int len) {
    writeInt(buffer, ptr, conv);
    buffer[ptr + 4] = (byte) cmd;
    buffer[ptr + 5] = (byte) frg;
    buffer[ptr + 6] = (byte) wnd;
    buffer[ptr + 7] = (byte) (wnd >>> 8);
    writeInt(buffer, ptr + 8, ts);
    writeInt(buffer, ptr + 12, sn);
    writeInt(buffer, ptr + 16, rcvNxt);
    writeInt(buffer, ptr + 20, len);
    return ptr + OVERHEAD;
  }

  private void updateAck(int rtt) {
    if (rxSrtt == 0) {
      rxSrtt = rtt;
      rxRttval = rtt / 2;
    } else {
      int delta = Math.abs(rtt - rxSrtt);
      rxRttval = (3 * rxRttval + delta) / 4;
      rxSrtt = (7 * rxSrtt + rtt) / 8;
      if (rxSrtt < 1) {
        rxSrtt = 1;
      }
    }
    int rto = rxSrtt + Math.max(interval, 4 * rxRttval);
    rxRto = Math.min(Math.max(rxMinrto, rto), RTO_MAX);
  }

  private void shrinkBuf() {
    sndUna = sndBuf.isEmptyHead() ? sndNxt : sndBuf.next.sn;
  }

  private void parseAck(int sn) {
    if (sn - sndUna < 0 || sn - sndNxt >= 0) {
      return;
    }
    for (Segment p = sndBuf.next; p != sndBuf; p = p.next) {
      if (sn == p.sn) {
        p.unlink();
        release(p);
        nsndBuf--;
        break;
      }
      if (sn - p.sn < 0) {
        break;
      }
    }
  }

  private void parseUna(int una) {
    for (Segment p = sndBuf.next; p != sndBuf; ) {
      Segment next = p.next;
      if (una - p.sn > 0) {
        p.unlink();
        release(p);
        nsndBuf--;
      } else {
        break;
      }
      p = next;
    }
  }

  private void parseFastack(int sn, int ts) {
    if (sn - sndUna < 0 || sn - sndNxt >= 0) {
      return;
    }
    for (Segment p = sndBuf.next; p != sndBuf; p = p.next) {
      if (sn - p.sn < 0) {
        break;
      } else if (sn != p.sn && ts - p.ts >= 0) {
        p.fastack++;
      }
    }
  }

  private void pushAck(int sn, int ts) {
    if ((ackCount + 1) * 2 > ackList.length && updated) {
      // Full (a burst read after a stall): send the acks now instead of growing the list.
      flushAcks();
    }
    if ((ackCount + 1) * 2 > ackList.length) {
      int[] grown = new int[ackList.length * 2];
      System.arraycopy(ackList, 0, grown, 0, ackCount * 2);
      ackList = grown;
    }
    ackList[ackCount * 2] = sn;
    ackList[ackCount * 2 + 1] = ts;
    ackCount++;
  }

  private void parseData(Segment newseg) {
    int sn = newseg.sn;
    if (sn - (rcvNxt + rcvWnd) >= 0 || sn - rcvNxt < 0) {
      release(newseg);
      return;
    }
    boolean repeat = false;
    Segment p = rcvBuf.prev;
    for (; p != rcvBuf; p = p.prev) {
      if (p.sn == sn) {
        repeat = true;
        break;
      }
      if (sn - p.sn > 0) {
        break;
      }
    }
    if (repeat) {
      release(newseg);
    } else {
      p.addAfter(newseg);
    }
    moveReceivedToQueue();
  }

  private void moveReceivedToQueue() {
    while (!rcvBuf.isEmptyHead()) {
      Segment seg = rcvBuf.next;
      if (seg.sn != rcvNxt || nrcvQue >= rcvWnd) {
        break;
      }
      seg.unlink();
      rcvQueue.addTail(seg);
      nrcvQue++;
      rcvNxt++;
    }
  }

  private Segment obtain(int size) {
    Segment seg = pool;
    if (seg == null) {
      return new Segment(Math.max(size, mss));
    }
    pool = seg.next;
    seg.prev = seg;
    seg.next = seg;
    ensureCapacity(seg, size);
    return seg;
  }

  private void release(Segment seg) {
    seg.prev = seg;
    seg.next = pool;
    seg.len = 0;
    pool = seg;
  }

  private void ensureCapacity(Segment seg, int size) {
    if (seg.data.length < size) {
      byte[] grown = new byte[Math.max(size, mss)];
      System.arraycopy(seg.data, 0, grown, 0, seg.len);
      seg.data = grown;
    }
  }

  private static int clampInterval(int value) {
    return Math.min(Math.max(value, MIN_INTERVAL), MAX_INTERVAL);
  }

  private static int readInt(byte[] data, int offset) {
    return (data[offset] & 0xff)
        | (data[offset + 1] & 0xff) << 8
        | (data[offset + 2] & 0xff) << 16
        | (data[offset + 3] & 0xff) << 24;
  }

  private static void writeInt(byte[] data, int offset, int value) {
    data[offset] = (byte) value;
    data[offset + 1] = (byte) (value >>> 8);
    data[offset + 2] = (byte) (value >>> 16);
    data[offset + 3] = (byte) (value >>> 24);
  }
}
