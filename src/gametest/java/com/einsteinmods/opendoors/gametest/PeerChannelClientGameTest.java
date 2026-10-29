package com.einsteinmods.opendoors.gametest;

import com.einsteinmods.opendoors.core.net.NetLoop;
import com.einsteinmods.opendoors.core.net.StreamEndpoint;
import com.einsteinmods.opendoors.host.PeerAddress;
import com.einsteinmods.opendoors.host.PeerChannel;
import com.einsteinmods.opendoors.host.PeerConnections;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.SharedConstants;

/**
 * A {@link PeerChannel} handed to the integrated server answers a vanilla status request: the
 * channel, the rings and the pipeline wiring of {@link PeerConnections} carry real packets. The
 * test plays the network thread's side of the rings.
 */
public final class PeerChannelClientGameTest implements FabricClientGameTest {
  private static final int STATUS_INTENTION = 1;
  private static final int PACKET_ID = 0;
  private static final int WAIT_TICKS = 200;

  @Override
  public void runTest(ClientGameTestContext context) {
    if (E2eFriends.active()) {
      // The two-client run plays only its own scenario: hosting here would reach the other client.
      return;
    }
    try (TestSingleplayerContext world = context.worldBuilder().create();
        NetLoop loop =
            NetLoop.open(new NetLoop.Options(List.of(InetAddress.getLoopbackAddress()), null))) {
      world.getConnection().waitForChunksDownload();
      // The server answers status requests only once it has built its status (every 5 s).
      for (int tick = 0; tick < WAIT_TICKS; tick++) {
        if (world.getServer().computeOnServer(server -> server.getStatus() != null)) {
          break;
        }
        context.waitTick();
      }
      PeerChannel channel = new PeerChannel(loop, new PeerAddress(UUID.randomUUID(), "Tester"));
      world.getServer().runOnServer(server -> PeerConnections.accept(server, channel));
      StreamEndpoint network = channel.endpoint();

      ByteArrayOutputStream request = new ByteArrayOutputStream();
      frame(request, handshake());
      frame(request, new byte[] {PACKET_ID});
      ByteBuffer outgoing = ByteBuffer.wrap(request.toByteArray());
      network.write(outgoing);
      if (outgoing.hasRemaining()) {
        throw new AssertionError("ring refused the request");
      }

      ByteBuffer response = ByteBuffer.allocate(64 * 1024);
      context.waitFor(
          ignored -> network.read(response) >= 0 && containsStatus(response), WAIT_TICKS);
      channel.close().syncUninterruptibly();
    } catch (IOException exception) {
      throw new AssertionError("network thread did not start", exception);
    }
  }

  private static boolean containsStatus(ByteBuffer response) {
    String text = new String(response.array(), 0, response.position(), StandardCharsets.UTF_8);
    return text.contains("\"version\"") && text.contains("\"description\"");
  }

  private static byte[] handshake() {
    ByteArrayOutputStream packet = new ByteArrayOutputStream();
    packet.write(PACKET_ID);
    writeVarInt(packet, SharedConstants.getProtocolVersion());
    byte[] host = "localhost".getBytes(StandardCharsets.UTF_8);
    writeVarInt(packet, host.length);
    packet.writeBytes(host);
    packet.write(0x63);
    packet.write(0xDD);
    writeVarInt(packet, STATUS_INTENTION);
    return packet.toByteArray();
  }

  private static void frame(ByteArrayOutputStream out, byte[] packet) {
    writeVarInt(out, packet.length);
    out.writeBytes(packet);
  }

  private static void writeVarInt(ByteArrayOutputStream out, int value) {
    while ((value & ~0x7F) != 0) {
      out.write((value & 0x7F) | 0x80);
      value >>>= 7;
    }
    out.write(value);
  }
}
