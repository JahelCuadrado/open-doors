package com.einsteinmods.opendoors.harness;

import com.einsteinmods.opendoors.core.net.ConnectionLog;
import com.einsteinmods.opendoors.core.net.NetLoop;
import com.einsteinmods.opendoors.core.net.PeerSession;
import com.einsteinmods.opendoors.core.net.portmap.PortMappers;
import com.einsteinmods.opendoors.core.online.RelayResolver;
import com.einsteinmods.opendoors.core.signaling.IceParameters;
import java.net.InetSocketAddress;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Dev-only network report (no friend needed): opens the game's UDP socket, gathers candidates with
 * the game's STUN probes (Cloudflare on ports 3478 and 53, for the NAT type), then tries a router
 * port mapping and deletes it. Prints the same diagnostics lines the game writes.
 */
final class NatReport {
  private NatReport() {}

  static void run() throws Exception {
    ConnectionLog.setSink(line -> System.out.println("[diagnostics] " + line));
    InetSocketAddress stun = RelayResolver.resolveStun("stun.cloudflare.com", 3478);
    InetSocketAddress probe = stun == null ? null : new InetSocketAddress(stun.getAddress(), 53);
    try (NetLoop loop = NetLoop.open(new NetLoop.Options(null, stun, probe))) {
      CompletableFuture<IceParameters> gathered = new CompletableFuture<>();
      loop.execute(
          () ->
              loop.startSession(
                  PeerSession.Role.HOST,
                  new PeerSession.Listener() {
                    @Override
                    public void onGathered(PeerSession session, IceParameters local) {
                      gathered.complete(local);
                    }

                    @Override
                    public void onConnected(PeerSession session, boolean relayed) {}

                    @Override
                    public void onEnded(PeerSession session, PeerSession.End end) {}
                  },
                  null));
      System.out.println("candidates: " + gathered.get(5, TimeUnit.SECONDS).candidates());
      Thread.sleep(1_000);
      PortMappers.Active mapping = PortMappers.map(loop.port(), PortMappers.Config.defaults());
      System.out.println(
          "router port mapping: " + (mapping == null ? "none" : mapping.mapping().toString()));
      if (mapping != null) {
        mapping.release().run();
        System.out.println("mapping deleted");
      }
    } finally {
      ConnectionLog.setSink(null);
    }
  }
}
