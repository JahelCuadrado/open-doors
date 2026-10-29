package com.einsteinmods.opendoors.core.net.portmap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

/** UPnP, NAT-PMP and PCP against fake in-process routers on loopback. */
class PortMappingProtocolsTest {
  private static final InetAddress LOOPBACK = InetAddress.getLoopbackAddress();
  private static final int LOCAL_PORT = 52_000;
  private static final byte[] PUBLIC_IP = {(byte) 203, 0, 113, 7};

  /** A UDP server answering each request with {@code answer} (null = silence). */
  private static final class FakeUdp implements AutoCloseable {
    final DatagramSocket socket;
    final List<byte[]> requests = new CopyOnWriteArrayList<>();

    FakeUdp(Function<byte[], byte[]> answer) throws IOException {
      socket = new DatagramSocket(0, LOOPBACK);
      Thread thread =
          new Thread(
              () -> {
                byte[] buffer = new byte[2048];
                while (!socket.isClosed()) {
                  DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                  try {
                    socket.receive(packet);
                    byte[] request = java.util.Arrays.copyOf(buffer, packet.getLength());
                    requests.add(request);
                    byte[] reply = answer.apply(request);
                    if (reply != null) {
                      socket.send(
                          new DatagramPacket(reply, reply.length, packet.getSocketAddress()));
                    }
                  } catch (IOException exception) {
                    return;
                  }
                }
              });
      thread.setDaemon(true);
      thread.start();
    }

    InetSocketAddress address() {
      return new InetSocketAddress(LOOPBACK, socket.getLocalPort());
    }

    @Override
    public void close() {
      socket.close();
    }
  }

  /** NAT-PMP router: external address and UDP mappings to port + 1. */
  private static byte[] natPmpRouter(byte[] request) {
    if (request[0] != 0) {
      // A PCP request: answer like a NAT-PMP-only router (UNSUPP_VERSION).
      return new byte[] {0, (byte) (128 + request[1]), 0, 1, 0, 0, 0, 0};
    }
    if (request[1] == 0) {
      return ByteBuffer.allocate(12)
          .put((byte) 0)
          .put((byte) 128)
          .putShort((short) 0)
          .putInt(1)
          .put(PUBLIC_IP)
          .array();
    }
    ByteBuffer in = ByteBuffer.wrap(request);
    in.position(4);
    int internal = in.getShort() & 0xFFFF;
    int suggested = in.getShort() & 0xFFFF;
    int lifetime = in.getInt();
    return ByteBuffer.allocate(16)
        .put((byte) 0)
        .put((byte) 129)
        .putShort((short) 0)
        .putInt(1)
        .putShort((short) internal)
        .putShort((short) (lifetime == 0 ? 0 : suggested + 1))
        .putInt(lifetime)
        .array();
  }

  /** PCP router: MAP to port + 2 on the public address. */
  private static byte[] pcpRouter(byte[] request) {
    ByteBuffer in = ByteBuffer.wrap(request);
    in.get();
    in.get();
    in.getShort();
    int lifetime = in.getInt();
    byte[] nonce = new byte[12];
    in.position(24);
    in.get(nonce);
    in.position(40);
    int internal = in.getShort() & 0xFFFF;
    int suggested = in.getShort() & 0xFFFF;
    ByteBuffer out = ByteBuffer.allocate(60);
    out.put((byte) 2).put((byte) 0x81).put((byte) 0).put((byte) 0).putInt(lifetime).putInt(7);
    out.put(new byte[12]);
    out.put(nonce)
        .put((byte) 17)
        .put(new byte[3])
        .putShort((short) internal)
        .putShort((short) (suggested + 2));
    out.put(new byte[10]).put((byte) 0xFF).put((byte) 0xFF).put(PUBLIC_IP);
    return out.array();
  }

  @Test
  void natPmpMapsAndDeletes() throws Exception {
    try (FakeUdp router = new FakeUdp(PortMappingProtocolsTest::natPmpRouter)) {
      PortMapping mapping = NatPmp.map(router.address(), LOCAL_PORT, 3600, 1000);
      assertNotNull(mapping);
      assertEquals("NAT-PMP", mapping.method());
      assertEquals(
          new InetSocketAddress(InetAddress.getByAddress(PUBLIC_IP), LOCAL_PORT + 1),
          mapping.external());
      assertEquals(3600, mapping.lifetimeSeconds());
      NatPmp.release(router.address(), LOCAL_PORT);
      Thread.sleep(100);
      byte[] last = router.requests.get(router.requests.size() - 1);
      assertEquals(0, ByteBuffer.wrap(last, 8, 4).getInt(), "delete = lifetime 0");
    }
  }

  @Test
  void pcpMapsWithOneRequest() throws Exception {
    try (FakeUdp router = new FakeUdp(PortMappingProtocolsTest::pcpRouter)) {
      PortMapping mapping =
          Pcp.map(router.address(), LOOPBACK, LOCAL_PORT, 3600, 1000, new SecureRandom());
      assertNotNull(mapping);
      assertEquals("PCP", mapping.method());
      assertEquals(LOCAL_PORT + 2, mapping.external().getPort());
      assertEquals(InetAddress.getByAddress(PUBLIC_IP), mapping.external().getAddress());
      byte[] request = router.requests.get(0);
      assertEquals(60, request.length);
      assertEquals(17, request[36], "UDP");
    }
  }

  @Test
  void silentGatewayGivesUpWithinTheBudget() throws Exception {
    try (FakeUdp router = new FakeUdp(request -> null)) {
      long start = System.nanoTime();
      assertNull(NatPmp.map(router.address(), LOCAL_PORT, 3600, 400));
      assertTrue((System.nanoTime() - start) / 1_000_000L < 900);
    }
  }

  @Test
  void parsesSsdpAndDescriptions() throws Exception {
    assertEquals(
        "http://192.168.1.1:5000/rootDesc.xml",
        Upnp.parseLocation(
            "HTTP/1.1 200 OK\r\nCACHE-CONTROL: max-age=120\r\nlocation: http://192.168.1.1:5000/rootDesc.xml\r\n\r\n"));
    URI location = new URI("http://192.168.1.1:5000/rootDesc.xml");
    String xml =
        "<root><device><serviceList><service><serviceType>urn:schemas-upnp-org:service:Layer3Forwarding:1"
            + "</serviceType><controlURL>/l3f</controlURL></service></serviceList><deviceList><device>"
            + "<serviceList><service><serviceType>urn:schemas-upnp-org:service:WANIPConnection:2</serviceType>"
            + "<controlURL>/ctl/IPConn</controlURL></service></serviceList></device></deviceList></device></root>";
    Upnp.Gateway gateway = Upnp.parseDescription(xml, location);
    assertEquals(new URI("http://192.168.1.1:5000/ctl/IPConn"), gateway.controlUrl());
    assertEquals("urn:schemas-upnp-org:service:WANIPConnection:2", gateway.serviceType());
    // A control URL outside the local network is never used.
    assertNull(
        Upnp.parseDescription(
            xml.replace("/ctl/IPConn", "http://198.51.100.1/ctl/IPConn"), location));
    assertEquals(
        "203.0.113.7",
        Upnp.parseExternalIp("<x><NewExternalIPAddress>203.0.113.7</NewExternalIPAddress></x>"));
    assertEquals(718, Upnp.parseErrorCode("<UPnPError><errorCode>718</errorCode></UPnPError>"));
    assertTrue(
        Upnp.addPortMappingArguments(1, 2, "192.168.1.5", 3600)
            .contains("<NewProtocol>UDP</NewProtocol>"));
  }

  @Test
  void upnpDiscoversMapsAndDeletesThroughAFakeRouter() throws Exception {
    List<String> actions = new CopyOnWriteArrayList<>();
    HttpServer http = HttpServer.create(new InetSocketAddress(LOOPBACK, 0), 0);
    int httpPort = http.getAddress().getPort();
    http.createContext(
        "/desc.xml",
        exchange ->
            reply(
                exchange,
                200,
                "<root><URLBase>http://127.0.0.1:"
                    + httpPort
                    + "</URLBase><service><serviceType>"
                    + "urn:schemas-upnp-org:service:WANPPPConnection:1</serviceType><controlURL>ctl"
                    + "</controlURL></service></root>"));
    int[] conflicts = {1};
    http.createContext(
        "/ctl",
        exchange -> {
          String action = exchange.getRequestHeaders().getFirst("SOAPAction");
          String body = new String(readAll(exchange.getRequestBody()), StandardCharsets.UTF_8);
          actions.add(action + " " + body);
          if (action.contains("AddPortMapping") && conflicts[0]-- > 0) {
            reply(exchange, 500, "<UPnPError><errorCode>718</errorCode></UPnPError>");
          } else if (action.contains("GetExternalIPAddress")) {
            reply(exchange, 200, "<NewExternalIPAddress>203.0.113.7</NewExternalIPAddress>");
          } else {
            reply(exchange, 200, "<ok/>");
          }
        });
    http.start();
    try (FakeUdp ssdp =
        new FakeUdp(
            request ->
                ("HTTP/1.1 200 OK\r\nST: x\r\nLOCATION: http://127.0.0.1:"
                        + httpPort
                        + "/desc.xml\r\n\r\n")
                    .getBytes(StandardCharsets.US_ASCII))) {
      PortMappers.Config config = new PortMappers.Config(ssdp.address(), List.of(), 2000, 3600);
      PortMappers.Active active = PortMappers.map(LOCAL_PORT, config);
      assertNotNull(active);
      assertEquals("UPnP", active.mapping().method());
      assertEquals("203.0.113.7", active.mapping().external().getAddress().getHostAddress());
      // The first port conflicted (718): another external port was tried.
      assertTrue(active.mapping().external().getPort() != LOCAL_PORT);
      assertTrue(actions.get(0).contains("WANPPPConnection:1#AddPortMapping"));
      active.release().run();
      assertTrue(actions.get(actions.size() - 1).contains("DeletePortMapping"));
    } finally {
      http.stop(0);
    }
  }

  @Test
  void gatewayProtocolsWinOverUpnpAndPcpFallsBackToNatPmp() throws Exception {
    try (FakeUdp router = new FakeUdp(PortMappingProtocolsTest::natPmpRouter);
        FakeUdp silentSsdp = new FakeUdp(request -> null)) {
      PortMappers.Config config =
          new PortMappers.Config(silentSsdp.address(), List.of(router.address()), 1500, 3600);
      PortMappers.Active active = PortMappers.map(LOCAL_PORT, config);
      assertNotNull(active);
      assertEquals("NAT-PMP", active.mapping().method());
    }
  }

  @Test
  void privateOrCarrierGradeAddressesAreNotOffered() throws Exception {
    assertTrue(PortMapService.isPrivate(InetAddress.getByName("192.168.1.1")));
    assertTrue(PortMapService.isPrivate(InetAddress.getByName("100.72.3.4")));
    assertTrue(!PortMapService.isPrivate(InetAddress.getByName("203.0.113.7")));
    assertEquals(
        InetAddress.getByName("192.168.1.1"),
        PortMappers.firstHost((java.net.Inet4Address) InetAddress.getByName("192.168.1.37"), 24));
  }

  private static void reply(com.sun.net.httpserver.HttpExchange exchange, int status, String body)
      throws IOException {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    exchange.sendResponseHeaders(status, bytes.length);
    try (OutputStream out = exchange.getResponseBody()) {
      out.write(bytes);
    }
  }

  private static byte[] readAll(InputStream in) throws IOException {
    return in.readAllBytes();
  }
}
