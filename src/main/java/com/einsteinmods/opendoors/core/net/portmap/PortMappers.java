package com.einsteinmods.opendoors.core.net.portmap;

import com.einsteinmods.opendoors.core.net.ConnectionLog;
import java.io.IOException;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Asks the home router to forward a UDP port to the game: PCP, then NAT-PMP (both to the default
 * gateway, port 5351), and UPnP IGD in parallel, all within one time budget. The first that works
 * wins (PCP, NAT-PMP, then UPnP); extra mappings are deleted. Blocking: call it off the render and
 * network threads.
 */
public final class PortMappers {
  /**
   * @param ssdpTarget where UPnP discovery goes (the SSDP multicast group; tests use a fake)
   * @param gateways NAT-PMP/PCP servers to ask (the default gateways; tests use a fake)
   * @param timeoutMillis total budget
   * @param leaseSeconds requested lease
   */
  public record Config(
      InetSocketAddress ssdpTarget,
      List<InetSocketAddress> gateways,
      int timeoutMillis,
      int leaseSeconds) {
    public static final int DEFAULT_TIMEOUT_MILLIS = 2_000;
    public static final int DEFAULT_LEASE_SECONDS = 3_600;

    /** The real network: SSDP multicast and the likely gateway of each private IPv4 network. */
    public static Config defaults() {
      return new Config(
          Upnp.SSDP_MULTICAST, likelyGateways(), DEFAULT_TIMEOUT_MILLIS, DEFAULT_LEASE_SECONDS);
    }
  }

  /** A mapping in place and how to delete it. */
  public record Active(PortMapping mapping, Runnable release) {}

  private static final SecureRandom RANDOM = new SecureRandom();

  private PortMappers() {}

  /** Maps {@code localPort}; null when no method worked within the budget. */
  public static Active map(int localPort, Config config) {
    ExecutorService workers =
        Executors.newFixedThreadPool(
            2,
            runnable -> {
              Thread thread = new Thread(runnable, "OpenDoors PortMap");
              thread.setDaemon(true);
              return thread;
            });
    try {
      CompletableFuture<Active> gateway =
          CompletableFuture.supplyAsync(() -> gatewayProtocols(localPort, config), workers);
      CompletableFuture<Active> upnp =
          CompletableFuture.supplyAsync(() -> upnp(localPort, config), workers);
      long start = System.nanoTime();
      Active fromGateway = await(gateway, config.timeoutMillis());
      Active chosen = fromGateway;
      if (fromGateway == null) {
        chosen = await(upnp, config.timeoutMillis() - NatPmp.elapsed(start));
      } else {
        // A UPnP mapping that also succeeded is not needed: delete it once it is there.
        upnp.thenAccept(
            extra -> {
              if (extra != null) {
                extra.release().run();
              }
            });
      }
      if (ConnectionLog.on()) {
        ConnectionLog.log(
            chosen == null
                ? "port mapping: none (no UPnP, NAT-PMP or PCP answer in "
                    + config.timeoutMillis()
                    + " ms)"
                : "port mapping: "
                    + chosen.mapping().method()
                    + " "
                    + chosen.mapping().external()
                    + " -> local UDP "
                    + localPort
                    + ", lease "
                    + chosen.mapping().lifetimeSeconds()
                    + " s");
      }
      return chosen;
    } finally {
      workers.shutdown();
    }
  }

  private static Active await(CompletableFuture<Active> future, int timeoutMillis) {
    try {
      return future.get(Math.max(1, timeoutMillis), TimeUnit.MILLISECONDS);
    } catch (TimeoutException exception) {
      // Too late to be used: delete it if it still succeeds, so no mapping is left behind.
      future.thenAccept(
          late -> {
            if (late != null) {
              late.release().run();
            }
          });
      return null;
    } catch (ExecutionException exception) {
      return null;
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      return null;
    }
  }

  private static Active gatewayProtocols(int localPort, Config config) {
    long start = System.nanoTime();
    for (InetSocketAddress gateway : config.gateways()) {
      int left = config.timeoutMillis() - NatPmp.elapsed(start);
      if (left <= 0) {
        return null;
      }
      try {
        InetAddress client = localAddressTowards(gateway);
        PortMapping pcp =
            Pcp.map(gateway, client, localPort, config.leaseSeconds(), Math.min(left, 600), RANDOM);
        if (pcp != null) {
          return new Active(pcp, () -> Pcp.release(gateway, client, localPort, RANDOM));
        }
        left = config.timeoutMillis() - NatPmp.elapsed(start);
        PortMapping pmp = NatPmp.map(gateway, localPort, config.leaseSeconds(), left);
        if (pmp != null) {
          return new Active(pmp, () -> NatPmp.release(gateway, localPort));
        }
        if (ConnectionLog.on()) {
          ConnectionLog.log("port mapping: no PCP/NAT-PMP answer from " + gateway);
        }
      } catch (IOException exception) {
        if (ConnectionLog.on()) {
          ConnectionLog.log(
              "port mapping: PCP/NAT-PMP to " + gateway + " failed: " + exception.getMessage());
        }
      }
    }
    return null;
  }

  private static Active upnp(int localPort, Config config) {
    long start = System.nanoTime();
    try {
      Upnp.Gateway gateway = Upnp.discover(config.ssdpTarget(), config.timeoutMillis() * 2 / 3);
      if (gateway == null) {
        if (ConnectionLog.on()) {
          ConnectionLog.log("port mapping: no UPnP gateway answered");
        }
        return null;
      }
      if (ConnectionLog.on()) {
        ConnectionLog.log(
            "port mapping: UPnP gateway "
                + gateway.controlUrl()
                + " ("
                + gateway.serviceType()
                + ")");
      }
      InetAddress client =
          localAddressTowards(
              new InetSocketAddress(
                  gateway.controlUrl().getHost(), Math.max(1, gateway.controlUrl().getPort())));
      int left = config.timeoutMillis() - NatPmp.elapsed(start);
      PortMapping mapping = Upnp.map(gateway, client, localPort, config.leaseSeconds(), left);
      if (mapping == null) {
        if (ConnectionLog.on()) {
          ConnectionLog.log("port mapping: the UPnP gateway refused AddPortMapping");
        }
        return null;
      }
      int external = mapping.external().getPort();
      return new Active(mapping, () -> Upnp.release(gateway, external, config.timeoutMillis()));
    } catch (IOException exception) {
      if (ConnectionLog.on()) {
        ConnectionLog.log("port mapping: UPnP failed: " + exception.getMessage());
      }
      return null;
    }
  }

  /** The local address the system would use to reach {@code target} (no packet is sent). */
  static InetAddress localAddressTowards(InetSocketAddress target) throws IOException {
    try (DatagramSocket probe = new DatagramSocket()) {
      probe.connect(target);
      return probe.getLocalAddress();
    }
  }

  /** The first host (x.y.z.1 style) of each private IPv4 network the machine is on. */
  static List<InetSocketAddress> likelyGateways() {
    List<InetSocketAddress> gateways = new ArrayList<>();
    try {
      for (NetworkInterface network : Collections.list(NetworkInterface.getNetworkInterfaces())) {
        if (!network.isUp() || network.isLoopback() || network.isPointToPoint()) {
          continue;
        }
        for (InterfaceAddress address : network.getInterfaceAddresses()) {
          if (address.getAddress() instanceof Inet4Address ipv4
              && ipv4.isSiteLocalAddress()
              && address.getNetworkPrefixLength() < 31) {
            InetSocketAddress gateway =
                new InetSocketAddress(
                    firstHost(ipv4, address.getNetworkPrefixLength()), NatPmp.PORT);
            if (!gateways.contains(gateway)) {
              gateways.add(gateway);
            }
          }
        }
      }
    } catch (IOException exception) {
      return gateways;
    }
    return gateways;
  }

  static InetAddress firstHost(Inet4Address address, int prefixLength) throws SocketException {
    byte[] raw = address.getAddress();
    int value =
        (raw[0] & 0xFF) << 24 | (raw[1] & 0xFF) << 16 | (raw[2] & 0xFF) << 8 | (raw[3] & 0xFF);
    int mask = prefixLength == 0 ? 0 : -1 << (32 - prefixLength);
    int first = (value & mask) + 1;
    try {
      return InetAddress.getByAddress(
          new byte[] {
            (byte) (first >>> 24), (byte) (first >>> 16), (byte) (first >>> 8), (byte) first
          });
    } catch (java.net.UnknownHostException exception) {
      throw new SocketException(exception.getMessage());
    }
  }
}
