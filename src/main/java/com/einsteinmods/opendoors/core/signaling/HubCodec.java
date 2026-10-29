package com.einsteinmods.opendoors.core.signaling;

import com.einsteinmods.opendoors.core.signaling.HubMessage.DirectoryQuery;
import com.einsteinmods.opendoors.core.signaling.HubMessage.DirectoryResult;
import com.einsteinmods.opendoors.core.signaling.HubMessage.DirectoryUpdate;
import com.einsteinmods.opendoors.core.signaling.HubMessage.ErrorMessage;
import com.einsteinmods.opendoors.core.signaling.HubMessage.HostClose;
import com.einsteinmods.opendoors.core.signaling.HubMessage.HostOpen;
import com.einsteinmods.opendoors.core.signaling.HubMessage.Inbound;
import com.einsteinmods.opendoors.core.signaling.HubMessage.InviteReceived;
import com.einsteinmods.opendoors.core.signaling.HubMessage.InviteSend;
import com.einsteinmods.opendoors.core.signaling.HubMessage.JoinAccepted;
import com.einsteinmods.opendoors.core.signaling.HubMessage.JoinAnswer;
import com.einsteinmods.opendoors.core.signaling.HubMessage.JoinOffer;
import com.einsteinmods.opendoors.core.signaling.HubMessage.JoinReject;
import com.einsteinmods.opendoors.core.signaling.HubMessage.JoinRejected;
import com.einsteinmods.opendoors.core.signaling.HubMessage.JoinRequest;
import com.einsteinmods.opendoors.core.signaling.HubMessage.Outbound;
import com.einsteinmods.opendoors.core.signaling.HubMessage.TurnCredentials;
import com.einsteinmods.opendoors.core.signaling.HubMessage.TurnDenied;
import com.einsteinmods.opendoors.core.signaling.HubMessage.TurnRequest;
import com.einsteinmods.opendoors.core.signaling.HubMessage.TurnRevoked;
import com.einsteinmods.opendoors.core.signaling.HubMessage.Welcome;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * JSON wire format of {@link HubMessage}. Decoding is whitelist-first: only known types are
 * accepted, only their known fields are read, and every field is validated; anything else decodes
 * to {@code null} and is dropped by the caller.
 */
public final class HubCodec {
  private static final String TYPE = "t";
  private static final String MONTHLY_LIMIT = "monthly_limit";
  private static final String UNAVAILABLE = "unavailable";
  private static final Pattern ERROR_CODE = Pattern.compile("[a-z_]{1,32}");

  private HubCodec() {}

  public static String encode(Outbound message) {
    JsonObject json = new JsonObject();
    switch (message) {
      case HostOpen open -> {
        json.addProperty(TYPE, open.update() ? "host.update" : "host.open");
        json.addProperty("mode", open.mode().wireName());
        json.add("allow", stringArray(open.allow()));
      }
      case HostClose _ -> json.addProperty(TYPE, "host.close");
      case DirectoryQuery query -> {
        json.addProperty(TYPE, "directory.query");
        json.add("uuids", stringArray(query.uuids()));
      }
      case InviteSend invite -> {
        json.addProperty(TYPE, "invite.send");
        json.addProperty("to", invite.to());
      }
      case JoinRequest request -> {
        json.addProperty(TYPE, "join.request");
        json.addProperty("host", request.host());
        writeIce(json, request.ice());
      }
      case JoinAnswer answer -> {
        json.addProperty(TYPE, "join.answer");
        json.addProperty("peer", answer.peer());
        json.addProperty("conv", answer.conv());
        writeIce(json, answer.ice());
      }
      case JoinReject reject -> {
        json.addProperty(TYPE, "join.reject");
        json.addProperty("peer", reject.peer());
        json.addProperty("reason", reject.reason().wireName());
      }
      case TurnRequest _ -> json.addProperty(TYPE, "turn.request");
    }
    return json.toString();
  }

  /** The validated message, or null when the text is not one well-formed hub message. */
  public static Inbound decode(String text) {
    if (text == null || text.length() > Validation.MAX_MESSAGE_CHARS) {
      return null;
    }
    try {
      JsonElement root = JsonParser.parseString(text);
      if (!root.isJsonObject()) {
        return null;
      }
      return decodeObject(root.getAsJsonObject());
    } catch (JsonParseException | IllegalArgumentException | IllegalStateException exception) {
      return null;
    }
  }

  private static Inbound decodeObject(JsonObject json) {
    String type = string(json, TYPE);
    if (type == null) {
      return null;
    }
    return switch (type) {
      case "welcome" -> {
        String uuid = string(json, "uuid");
        String name = string(json, "name");
        yield Validation.isUuid(uuid) && Validation.isName(name) ? new Welcome(uuid, name) : null;
      }
      case "directory.result" -> {
        List<String> joinable = uuidList(json, "joinable");
        yield joinable == null ? null : new DirectoryResult(joinable);
      }
      case "directory.update" -> {
        String host = string(json, "host");
        yield Validation.isUuid(host)
                && json.get("joinable") instanceof JsonPrimitive joinable
                && joinable.isBoolean()
            ? new DirectoryUpdate(host, joinable.getAsBoolean())
            : null;
      }
      case "invite.recv" -> {
        String from = string(json, "from");
        String name = string(json, "name");
        yield Validation.isUuid(from) && Validation.isName(name)
            ? new InviteReceived(from, name)
            : null;
      }
      case "join.offer" -> {
        String peer = string(json, "peer");
        String name = string(json, "name");
        long conv = integer(json, "conv");
        IceParameters ice = readIce(json);
        yield Validation.isUuid(peer)
                && Validation.isName(name)
                && Validation.isConv(conv)
                && ice != null
            ? new JoinOffer(peer, name, conv, ice)
            : null;
      }
      case "join.answer" -> {
        String host = string(json, "host");
        long conv = integer(json, "conv");
        IceParameters ice = readIce(json);
        yield Validation.isUuid(host) && Validation.isConv(conv) && ice != null
            ? new JoinAccepted(host, conv, ice)
            : null;
      }
      case "join.reject" -> {
        String host = string(json, "host");
        JoinRejectReason reason = JoinRejectReason.fromWireName(string(json, "reason"));
        yield Validation.isUuid(host) && reason != null ? new JoinRejected(host, reason) : null;
      }
      case "turn.creds" -> decodeTurnCredentials(json);
      case "turn.denied" -> {
        String reason = string(json, "reason");
        yield MONTHLY_LIMIT.equals(reason) || UNAVAILABLE.equals(reason)
            ? new TurnDenied(MONTHLY_LIMIT.equals(reason))
            : null;
      }
      case "turn.revoked" -> new TurnRevoked(MONTHLY_LIMIT.equals(string(json, "reason")));
      case "error" -> {
        String code = string(json, "code");
        long retryAfter = json.has("retryAfter") ? integer(json, "retryAfter") : 0;
        yield code != null
                && ERROR_CODE.matcher(code).matches()
                && retryAfter >= 0
                && retryAfter <= Validation.MAX_RETRY_AFTER_SECONDS
            ? new ErrorMessage(code, (int) retryAfter)
            : null;
      }
      default -> null;
    };
  }

  private static TurnCredentials decodeTurnCredentials(JsonObject json) {
    String username = string(json, "username");
    String credential = string(json, "credential");
    long ttl = integer(json, "ttl");
    JsonElement urlsElement = json.get("urls");
    if (!Validation.isTurnSecret(username)
        || !Validation.isTurnSecret(credential)
        || ttl < 1
        || ttl > Validation.MAX_RETRY_AFTER_SECONDS
        || urlsElement == null
        || !urlsElement.isJsonArray()) {
      return null;
    }
    JsonArray array = urlsElement.getAsJsonArray();
    if (array.isEmpty() || array.size() > Validation.MAX_TURN_URLS) {
      return null;
    }
    List<String> urls = new ArrayList<>(array.size());
    for (JsonElement element : array) {
      String url = primitiveString(element);
      if (!Validation.isTurnUrl(url)) {
        return null;
      }
      urls.add(url);
    }
    return new TurnCredentials(urls, username, credential, (int) ttl);
  }

  private static void writeIce(JsonObject json, IceParameters ice) {
    json.addProperty("ufrag", ice.ufrag());
    json.addProperty("pwd", ice.password());
    JsonArray candidates = new JsonArray(ice.candidates().size());
    for (Candidate candidate : ice.candidates()) {
      JsonObject entry = new JsonObject();
      entry.addProperty("k", candidate.kind().wireName());
      entry.addProperty("a", candidate.address());
      entry.addProperty("p", candidate.port());
      candidates.add(entry);
    }
    json.add("candidates", candidates);
  }

  private static IceParameters readIce(JsonObject json) {
    String ufrag = string(json, "ufrag");
    String password = string(json, "pwd");
    JsonElement candidatesElement = json.get("candidates");
    if (!Validation.isUfrag(ufrag)
        || !Validation.isPassword(password)
        || candidatesElement == null
        || !candidatesElement.isJsonArray()) {
      return null;
    }
    JsonArray array = candidatesElement.getAsJsonArray();
    if (array.size() > Validation.MAX_CANDIDATES) {
      return null;
    }
    List<Candidate> candidates = new ArrayList<>(array.size());
    for (JsonElement element : array) {
      if (!element.isJsonObject()) {
        return null;
      }
      JsonObject entry = element.getAsJsonObject();
      Candidate.Kind kind = Candidate.Kind.fromWireName(string(entry, "k"));
      String address = string(entry, "a");
      long port = integer(entry, "p");
      if (kind == null || !Validation.isIpLiteral(address) || !Validation.isPort(port)) {
        return null;
      }
      candidates.add(new Candidate(kind, address, (int) port));
    }
    return new IceParameters(ufrag, password, candidates);
  }

  private static List<String> uuidList(JsonObject json, String key) {
    JsonElement element = json.get(key);
    if (element == null || !element.isJsonArray()) {
      return null;
    }
    JsonArray array = element.getAsJsonArray();
    if (array.size() > Validation.MAX_UUID_LIST) {
      return null;
    }
    List<String> uuids = new ArrayList<>(array.size());
    for (JsonElement entry : array) {
      String uuid = primitiveString(entry);
      if (!Validation.isUuid(uuid)) {
        return null;
      }
      uuids.add(uuid);
    }
    return uuids;
  }

  private static JsonArray stringArray(List<String> values) {
    JsonArray array = new JsonArray(values.size());
    for (String value : values) {
      array.add(value);
    }
    return array;
  }

  private static String string(JsonObject json, String key) {
    return primitiveString(json.get(key));
  }

  private static String primitiveString(JsonElement element) {
    if (element instanceof JsonPrimitive primitive && primitive.isString()) {
      return primitive.getAsString();
    }
    return null;
  }

  /** Whole-number field, or -1 when missing, fractional or not a number. */
  private static long integer(JsonObject json, String key) {
    if (json.get(key) instanceof JsonPrimitive primitive && primitive.isNumber()) {
      double value = primitive.getAsDouble();
      if (value == Math.rint(value) && value >= 0 && value <= Validation.MAX_CONV) {
        return (long) value;
      }
    }
    return -1;
  }
}
