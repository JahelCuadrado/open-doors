package com.einsteinmods.opendoors.core.net.portmap;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * UPnP Internet Gateway Device client: SSDP discovery (M-SEARCH to 239.255.255.250:1900), the
 * device description, and the WANIPConnection / WANPPPConnection SOAP actions AddPortMapping,
 * GetExternalIPAddress and DeletePortMapping. Only gateways on the local network are used, and
 * documents are read up to a size limit with plain string matching (no XML parser, no entities).
 */
public final class Upnp {
  public static final InetSocketAddress SSDP_MULTICAST =
      new InetSocketAddress(InetAddressHolder.SSDP, 1900);

  private static final String[] SEARCH_TARGETS = {
    "urn:schemas-upnp-org:device:InternetGatewayDevice:1",
    "urn:schemas-upnp-org:device:InternetGatewayDevice:2",
    "urn:schemas-upnp-org:service:WANIPConnection:1"
  };
  private static final Pattern LOCATION = Pattern.compile("(?im)^location:\\s*(\\S+)\\s*$");
  private static final Pattern SERVICE = Pattern.compile("(?s)<service>(.*?)</service>");
  private static final Pattern SERVICE_TYPE =
      Pattern.compile(
          "(?s)<serviceType>\\s*(urn:schemas-upnp-org:service:(WANIPConnection|WANPPPConnection):\\d)\\s*</serviceType>");
  private static final Pattern CONTROL_URL =
      Pattern.compile("(?s)<controlURL>\\s*(\\S+?)\\s*</controlURL>");
  private static final Pattern URL_BASE = Pattern.compile("(?s)<URLBase>\\s*(\\S+?)\\s*</URLBase>");
  private static final Pattern EXTERNAL_IP =
      Pattern.compile("(?s)<NewExternalIPAddress>\\s*([0-9.]+)\\s*</NewExternalIPAddress>");
  private static final Pattern ERROR_CODE =
      Pattern.compile("(?s)<errorCode>\\s*(\\d+)\\s*</errorCode>");
  private static final int MAX_DOCUMENT_BYTES = 64 * 1024;
  private static final String DESCRIPTION = "Open Doors";
  static final int ERROR_CONFLICT = 718;
  static final int ERROR_ONLY_PERMANENT = 725;

  /** A gateway's WAN connection service. */
  public record Gateway(URI controlUrl, String serviceType) {}

  private Upnp() {}

  /** Holder so the constant resolves without a lookup (literal address). */
  private static final class InetAddressHolder {
    static final InetAddress SSDP;

    static {
      try {
        SSDP =
            InetAddress.getByAddress(new byte[] {(byte) 239, (byte) 255, (byte) 255, (byte) 250});
      } catch (IOException exception) {
        throw new ExceptionInInitializerError(exception);
      }
    }
  }

  static String searchRequest(String target) {
    return "M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: \"ssdp:discover\"\r\nMX: 1\r\nST: "
        + target
        + "\r\n\r\n";
  }

  /** The LOCATION of an SSDP answer, or null. */
  static String parseLocation(String response) {
    Matcher matcher = LOCATION.matcher(response);
    return matcher.find() ? matcher.group(1) : null;
  }

  /** The first WAN connection service of a device description, or null. */
  static Gateway parseDescription(String xml, URI location) {
    Matcher base = URL_BASE.matcher(xml);
    URI baseUri = location;
    if (base.find()) {
      try {
        baseUri = new URI(base.group(1));
      } catch (URISyntaxException exception) {
        baseUri = location;
      }
    }
    Matcher services = SERVICE.matcher(xml);
    while (services.find()) {
      String service = services.group(1);
      Matcher type = SERVICE_TYPE.matcher(service);
      Matcher control = CONTROL_URL.matcher(service);
      if (type.find() && control.find()) {
        try {
          URI controlUrl = baseUri.resolve(new URI(control.group(1)));
          if (isLocalHttp(controlUrl)) {
            return new Gateway(controlUrl, type.group(1));
          }
        } catch (URISyntaxException exception) {
          // Next service.
        }
      }
    }
    return null;
  }

  /** Only plain HTTP to an address on the local network (the router), never elsewhere. */
  static boolean isLocalHttp(URI uri) {
    if (!"http".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null) {
      return false;
    }
    String host = uri.getHost();
    if (!host.matches("[0-9.]+")) {
      return false;
    }
    try {
      InetAddress address = InetAddress.getByName(host);
      return address.isSiteLocalAddress()
          || address.isLinkLocalAddress()
          || address.isLoopbackAddress();
    } catch (IOException exception) {
      return false;
    }
  }

  static String soapBody(String serviceType, String action, String arguments) {
    return "<?xml version=\"1.0\"?>\r\n"
        + "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\""
        + " s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\"><s:Body><u:"
        + action
        + " xmlns:u=\""
        + serviceType
        + "\">"
        + arguments
        + "</u:"
        + action
        + "></s:Body></s:Envelope>\r\n";
  }

  static String addPortMappingArguments(
      int externalPort, int internalPort, String internalClient, int leaseSeconds) {
    return "<NewRemoteHost></NewRemoteHost><NewExternalPort>"
        + externalPort
        + "</NewExternalPort><NewProtocol>UDP</NewProtocol><NewInternalPort>"
        + internalPort
        + "</NewInternalPort><NewInternalClient>"
        + internalClient
        + "</NewInternalClient><NewEnabled>1</NewEnabled><NewPortMappingDescription>"
        + DESCRIPTION
        + "</NewPortMappingDescription><NewLeaseDuration>"
        + leaseSeconds
        + "</NewLeaseDuration>";
  }

  static String parseExternalIp(String xml) {
    Matcher matcher = EXTERNAL_IP.matcher(xml);
    return matcher.find() ? matcher.group(1) : null;
  }

  static int parseErrorCode(String xml) {
    Matcher matcher = ERROR_CODE.matcher(xml);
    return matcher.find() ? Integer.parseInt(matcher.group(1)) : -1;
  }

  /** Finds a gateway: SSDP to {@code target} (normally {@link #SSDP_MULTICAST}). */
  public static Gateway discover(InetSocketAddress target, int timeoutMillis) throws IOException {
    long start = System.nanoTime();
    try (DatagramSocket socket = new DatagramSocket()) {
      for (String searchTarget : SEARCH_TARGETS) {
        byte[] request = searchRequest(searchTarget).getBytes(StandardCharsets.US_ASCII);
        socket.send(new DatagramPacket(request, request.length, target));
      }
      byte[] buffer = new byte[2048];
      while (NatPmp.elapsed(start) < timeoutMillis) {
        int length = NatPmp.receive(socket, buffer, timeoutMillis - NatPmp.elapsed(start));
        if (length < 0) {
          return null;
        }
        String location = parseLocation(new String(buffer, 0, length, StandardCharsets.US_ASCII));
        if (location == null) {
          continue;
        }
        try {
          URI uri = new URI(location);
          if (!isLocalHttp(uri)) {
            continue;
          }
          int left = timeoutMillis - NatPmp.elapsed(start);
          Gateway gateway = parseDescription(httpGet(uri, left), uri);
          if (gateway != null) {
            return gateway;
          }
        } catch (URISyntaxException | IOException exception) {
          // Try the next answer.
        }
      }
      return null;
    }
  }

  /** Maps UDP {@code internalPort} on {@code gateway}; null when the router refuses. */
  public static PortMapping map(
      Gateway gateway,
      InetAddress internalClient,
      int internalPort,
      int leaseSeconds,
      int timeoutMillis)
      throws IOException {
    long start = System.nanoTime();
    int lease = leaseSeconds;
    int externalPort = internalPort;
    for (int attempt = 0; attempt < 3 && NatPmp.elapsed(start) < timeoutMillis; attempt++) {
      Response response =
          soap(
              gateway,
              "AddPortMapping",
              addPortMappingArguments(
                  externalPort, internalPort, internalClient.getHostAddress(), lease),
              timeoutMillis - NatPmp.elapsed(start));
      if (response.status() == HttpURLConnection.HTTP_OK) {
        Response ip =
            soap(gateway, "GetExternalIPAddress", "", timeoutMillis - NatPmp.elapsed(start));
        String external =
            ip.status() == HttpURLConnection.HTTP_OK ? parseExternalIp(ip.body()) : null;
        if (external == null) {
          return null;
        }
        return new PortMapping(
            "UPnP", new InetSocketAddress(InetAddress.getByName(external), externalPort), lease);
      }
      int error = parseErrorCode(response.body());
      if (error == ERROR_ONLY_PERMANENT) {
        lease = 0;
      } else if (error == ERROR_CONFLICT) {
        externalPort = 20_000 + Math.floorMod(internalPort * 31 + attempt * 7919, 40_000);
      } else {
        return null;
      }
    }
    return null;
  }

  public static void release(Gateway gateway, int externalPort, int timeoutMillis) {
    try {
      soap(
          gateway,
          "DeletePortMapping",
          "<NewRemoteHost></NewRemoteHost><NewExternalPort>"
              + externalPort
              + "</NewExternalPort><NewProtocol>UDP</NewProtocol>",
          timeoutMillis);
    } catch (IOException exception) {
      // The lease runs out by itself.
    }
  }

  record Response(int status, String body) {}

  private static Response soap(Gateway gateway, String action, String arguments, int timeoutMillis)
      throws IOException {
    if (timeoutMillis <= 0) {
      throw new IOException("out of time");
    }
    HttpURLConnection connection =
        (HttpURLConnection) gateway.controlUrl().toURL().openConnection();
    connection.setConnectTimeout(timeoutMillis);
    connection.setReadTimeout(timeoutMillis);
    connection.setRequestMethod("POST");
    connection.setDoOutput(true);
    connection.setInstanceFollowRedirects(false);
    connection.setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"");
    connection.setRequestProperty("SOAPAction", "\"" + gateway.serviceType() + "#" + action + "\"");
    byte[] body =
        soapBody(gateway.serviceType(), action, arguments).getBytes(StandardCharsets.UTF_8);
    try (OutputStream out = connection.getOutputStream()) {
      out.write(body);
    }
    int status = connection.getResponseCode();
    InputStream in = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
    return new Response(status, in == null ? "" : readLimited(in));
  }

  private static String httpGet(URI uri, int timeoutMillis) throws IOException {
    if (timeoutMillis <= 0) {
      throw new IOException("out of time");
    }
    HttpURLConnection connection = (HttpURLConnection) uri.toURL().openConnection();
    connection.setConnectTimeout(timeoutMillis);
    connection.setReadTimeout(timeoutMillis);
    connection.setInstanceFollowRedirects(false);
    try (InputStream in = connection.getInputStream()) {
      return readLimited(in);
    }
  }

  private static String readLimited(InputStream in) throws IOException {
    try (in) {
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      byte[] chunk = new byte[4096];
      int read;
      while ((read = in.read(chunk)) >= 0) {
        if (out.size() + read > MAX_DOCUMENT_BYTES) {
          throw new IOException("document too large");
        }
        out.write(chunk, 0, read);
      }
      return out.toString(StandardCharsets.UTF_8);
    }
  }
}
