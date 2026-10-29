package com.einsteinmods.opendoors.core.signaling;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

/** Minimal HTTP + WebSocket (RFC 6455) server standing in for the backend in tests. */
final class FakeHub implements AutoCloseable {
  static final String SERVER_ID = "00112233445566778899aabbccddeeff00112233";
  private static final String WEBSOCKET_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";

  private final ServerSocket server;
  private final Thread acceptor;
  final BlockingQueue<String> socketQueries = new LinkedBlockingQueue<>();
  final BlockingQueue<String> received = new LinkedBlockingQueue<>();
  final BlockingQueue<Map<String, String>> socketHeaders = new LinkedBlockingQueue<>();
  volatile int challengeStatus = 200;
  volatile int retryAfterSeconds;
  volatile int challengeCount;
  private final List<Socket> sockets = new ArrayList<>();
  private volatile Socket current;

  FakeHub() throws IOException {
    server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
    acceptor = new Thread(this::acceptLoop, "FakeHub");
    acceptor.setDaemon(true);
    acceptor.start();
  }

  BackendEndpoint endpoint() {
    return BackendEndpoint.parse("http://127.0.0.1:" + server.getLocalPort()).orElseThrow();
  }

  /** Sends a text frame to the latest socket. */
  void sendText(String text) throws IOException {
    writeFrame(current.getOutputStream(), 0x1, text.getBytes(StandardCharsets.UTF_8));
  }

  /** Sends a close frame with {@code code} to the latest socket. */
  void closeWith(int code) throws IOException {
    writeFrame(current.getOutputStream(), 0x8, new byte[] {(byte) (code >> 8), (byte) code});
  }

  private void acceptLoop() {
    while (!server.isClosed()) {
      try {
        Socket socket = server.accept();
        synchronized (sockets) {
          sockets.add(socket);
        }
        Thread handler = new Thread(() -> handle(socket), "FakeHub connection");
        handler.setDaemon(true);
        handler.start();
      } catch (IOException exception) {
        return;
      }
    }
  }

  private void handle(Socket socket) {
    try (socket) {
      InputStream in = socket.getInputStream();
      OutputStream out = socket.getOutputStream();
      String requestLine = readLine(in);
      Map<String, String> headers = new HashMap<>();
      for (String line = readLine(in); !line.isEmpty(); line = readLine(in)) {
        int colon = line.indexOf(':');
        headers.put(
            line.substring(0, colon).trim().toLowerCase(), line.substring(colon + 1).trim());
      }
      if (requestLine.startsWith("POST /v1/auth/challenge")) {
        challengeCount++;
        String body = "{\"serverId\":\"" + SERVER_ID + "\",\"expiresAt\":1}";
        String status = challengeStatus == 200 ? "200 OK" : challengeStatus + " Error";
        String extra = retryAfterSeconds > 0 ? "Retry-After: " + retryAfterSeconds + "\r\n" : "";
        out.write(
            ("HTTP/1.1 "
                    + status
                    + "\r\nContent-Type: application/json\r\n"
                    + extra
                    + "Content-Length: "
                    + body.length()
                    + "\r\nConnection: close\r\n\r\n"
                    + body)
                .getBytes(StandardCharsets.US_ASCII));
        out.flush();
        return;
      }
      String target = requestLine.split(" ")[1];
      socketQueries.add(target.substring(target.indexOf('?') + 1));
      socketHeaders.add(Map.copyOf(headers));
      String accept = acceptKey(headers.get("sec-websocket-key"));
      out.write(
          ("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
                  + "Sec-WebSocket-Accept: "
                  + accept
                  + "\r\n\r\n")
              .getBytes(StandardCharsets.US_ASCII));
      out.flush();
      current = socket;
      writeFrame(
          out,
          0x1,
          "{\"t\":\"welcome\",\"uuid\":\"10920508-d5d8-3eed-93d2-92f193afe7d7\",\"name\":\"Alice\"}"
              .getBytes(StandardCharsets.UTF_8));
      readFrames(new DataInputStream(in), out);
    } catch (IOException | NoSuchAlgorithmException exception) {
      // Connection closed by the client or the test.
    }
  }

  private void readFrames(DataInputStream in, OutputStream out) throws IOException {
    while (true) {
      int first = in.readUnsignedByte();
      int second = in.readUnsignedByte();
      long length = second & 0x7F;
      if (length == 126) {
        length = in.readUnsignedShort();
      } else if (length == 127) {
        length = in.readLong();
      }
      byte[] mask = new byte[4];
      in.readFully(mask);
      byte[] payload = new byte[(int) length];
      in.readFully(payload);
      for (int i = 0; i < payload.length; i++) {
        payload[i] ^= mask[i & 3];
      }
      int opcode = first & 0x0F;
      if (opcode == 0x8) {
        writeFrame(out, 0x8, payload);
        return;
      }
      String text = new String(payload, StandardCharsets.UTF_8);
      if (text.equals("ping")) {
        writeFrame(out, 0x1, "pong".getBytes(StandardCharsets.UTF_8));
      } else {
        received.add(text);
      }
    }
  }

  private static synchronized void writeFrame(OutputStream out, int opcode, byte[] payload)
      throws IOException {
    ByteArrayOutputStream frame = new ByteArrayOutputStream();
    frame.write(0x80 | opcode);
    if (payload.length < 126) {
      frame.write(payload.length);
    } else {
      frame.write(126);
      frame.write(payload.length >> 8);
      frame.write(payload.length);
    }
    frame.write(payload);
    out.write(frame.toByteArray());
    out.flush();
  }

  private static String acceptKey(String key) throws NoSuchAlgorithmException {
    byte[] digest =
        MessageDigest.getInstance("SHA-1")
            .digest((key + WEBSOCKET_GUID).getBytes(StandardCharsets.US_ASCII));
    return Base64.getEncoder().encodeToString(digest);
  }

  private static String readLine(InputStream in) throws IOException {
    StringBuilder line = new StringBuilder();
    for (int c = in.read(); c != '\n'; c = in.read()) {
      if (c < 0) {
        throw new IOException("eof");
      }
      if (c != '\r') {
        line.append((char) c);
      }
    }
    return line.toString();
  }

  @Override
  public void close() throws IOException {
    server.close();
    synchronized (sockets) {
      for (Socket socket : sockets) {
        socket.close();
      }
    }
  }
}
