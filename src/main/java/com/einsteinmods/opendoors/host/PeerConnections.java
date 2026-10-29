package com.einsteinmods.opendoors.host;

import io.netty.channel.ChannelPipeline;
import io.netty.handler.timeout.ReadTimeoutHandler;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.network.Connection;
import net.minecraft.network.RateKickingConnection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.EventLoopGroupHolder;
import net.minecraft.server.network.LegacyQueryHandler;
import net.minecraft.server.network.ServerHandshakePacketListenerImpl;

/**
 * Hands a friend's {@link PeerChannel} to the server exactly like an accepted TCP connection
 * (vanilla {@code ServerConnectionListener#startTcpServerListener}): same timeout, serialization,
 * rate limit and handshake listener, so login runs the full online-mode path (Mojang session check
 * and encryption). Deliberately not the memory-channel path: {@code
 * ServerLoginPacketListenerImpl#handleHello} skips authentication for memory connections.
 */
public final class PeerConnections {
  private static final int READ_TIMEOUT_SECONDS = 30;

  private static final Set<PeerChannel> LIVE = ConcurrentHashMap.newKeySet();

  private PeerConnections() {}

  /** Friends' channels currently open on this host. */
  public static List<PeerChannel> live() {
    return List.copyOf(LIVE);
  }

  public static void accept(MinecraftServer server, PeerChannel channel) {
    ChannelPipeline pipeline =
        channel.pipeline().addLast("timeout", new ReadTimeoutHandler(READ_TIMEOUT_SECONDS));
    if (server.repliesToStatus()) {
      pipeline.addLast("legacy_query", new LegacyQueryHandler(server));
    }
    Connection.configureSerialization(pipeline, PacketFlow.SERVERBOUND, false, null);
    int rateLimit = server.getRateLimitPacketsPerSecond();
    Connection connection =
        rateLimit > 0
            ? new RateKickingConnection(rateLimit)
            : new Connection(PacketFlow.SERVERBOUND);
    connection.configurePacketHandler(pipeline);
    connection.setListenerForServerboundHandshake(
        new ServerHandshakePacketListenerImpl(server, connection));
    server.getConnection().getConnections().add(connection);
    LIVE.add(channel);
    channel.closeFuture().addListener(closed -> LIVE.remove(channel));
    // Runs on the loop that also serves the host's own in-memory connection; the channel only
    // uses it as an executor.
    EventLoopGroupHolder.local().eventLoopGroup().register(channel);
  }
}
