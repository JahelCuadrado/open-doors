package com.einsteinmods.opendoors.core.net.portmap;

import com.einsteinmods.opendoors.core.net.ConnectionLog;
import com.einsteinmods.opendoors.core.net.NetLoop;
import com.einsteinmods.opendoors.core.signaling.Candidate;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Keeps a router port mapping for the host's UDP socket while the world is open online: maps on
 * open (one background thread, at most the configured budget), offers the result as a candidate to
 * new joins, renews at half the lease, and deletes it when hosting closes. The thread exists only
 * while hosting.
 */
public final class PortMapService {
  private static final int MIN_RENEW_SECONDS = 60;

  private final Supplier<PortMappers.Config> config;
  private ScheduledExecutorService thread;
  private volatile PortMappers.Active active;
  private NetLoop loop;

  /** {@code config} null disables mapping (tests). */
  public PortMapService(Supplier<PortMappers.Config> config) {
    this.config = config;
  }

  /** Starts mapping {@code netLoop}'s port (idempotent while running). */
  public synchronized void start(NetLoop netLoop) {
    if (config == null || (thread != null && loop == netLoop)) {
      return;
    }
    stop();
    loop = netLoop;
    ScheduledThreadPoolExecutor executor =
        new ScheduledThreadPoolExecutor(
            1,
            runnable -> {
              Thread created = new Thread(runnable, "OpenDoors PortMap");
              created.setDaemon(true);
              return created;
            });
    executor.setRemoveOnCancelPolicy(true);
    thread = executor;
    executor.execute(() -> mapNow(netLoop));
  }

  /** Deletes the mapping and stops the thread. */
  public synchronized void stop() {
    ScheduledExecutorService current = thread;
    thread = null;
    NetLoop previous = loop;
    loop = null;
    if (current == null) {
      return;
    }
    current.shutdownNow();
    if (previous != null) {
      previous.setMappedCandidate(null);
    }
    PortMappers.Active mapping = active;
    active = null;
    if (mapping != null) {
      Thread releaser =
          new Thread(
              () -> {
                mapping.release().run();
                if (ConnectionLog.on()) {
                  ConnectionLog.log("port mapping released: " + mapping.mapping().external());
                }
              },
              "OpenDoors PortMap release");
      releaser.setDaemon(true);
      releaser.start();
    }
  }

  /** Private, carrier-grade NAT (100.64.0.0/10) or link-local: not reachable from the internet. */
  static boolean isPrivate(java.net.InetAddress address) {
    byte[] raw = address.getAddress();
    boolean carrierGrade = raw.length == 4 && (raw[0] & 0xFF) == 100 && (raw[1] & 0xC0) == 64;
    return address.isSiteLocalAddress()
        || address.isLinkLocalAddress()
        || address.isLoopbackAddress()
        || address.isAnyLocalAddress()
        || carrierGrade;
  }

  private void mapNow(NetLoop netLoop) {
    PortMappers.Active mapped = PortMappers.map(netLoop.port(), config.get());
    synchronized (this) {
      if (thread == null || loop != netLoop) {
        if (mapped != null) {
          mapped.release().run();
        }
        return;
      }
      active = mapped;
      if (mapped == null) {
        return;
      }
      PortMapping mapping = mapped.mapping();
      if (isPrivate(mapping.external().getAddress())) {
        // The router itself is behind another NAT (carrier-grade NAT or a second router).
        if (ConnectionLog.on()) {
          ConnectionLog.log(
              "port mapping: router's public address "
                  + mapping.external().getAddress().getHostAddress()
                  + " is private (double NAT / carrier-grade NAT): mapping not offered");
        }
        return;
      }
      String address = mapping.external().getAddress().getHostAddress();
      netLoop.setMappedCandidate(
          new Candidate(Candidate.Kind.SRFLX, address, mapping.external().getPort()));
      int lease = mapping.lifetimeSeconds();
      if (lease > 0) {
        thread.schedule(
            () -> mapNow(netLoop), Math.max(MIN_RENEW_SECONDS, lease / 2), TimeUnit.SECONDS);
      }
    }
  }
}
