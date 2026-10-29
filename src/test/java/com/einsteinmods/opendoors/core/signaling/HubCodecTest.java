package com.einsteinmods.opendoors.core.signaling;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.einsteinmods.opendoors.core.signaling.HubMessage.DirectoryResult;
import com.einsteinmods.opendoors.core.signaling.HubMessage.DirectoryUpdate;
import com.einsteinmods.opendoors.core.signaling.HubMessage.ErrorMessage;
import com.einsteinmods.opendoors.core.signaling.HubMessage.HostOpen;
import com.einsteinmods.opendoors.core.signaling.HubMessage.JoinAccepted;
import com.einsteinmods.opendoors.core.signaling.HubMessage.JoinOffer;
import com.einsteinmods.opendoors.core.signaling.HubMessage.JoinRejected;
import com.einsteinmods.opendoors.core.signaling.HubMessage.JoinRequest;
import com.einsteinmods.opendoors.core.signaling.HubMessage.TurnCredentials;
import com.einsteinmods.opendoors.core.signaling.HubMessage.TurnDenied;
import com.einsteinmods.opendoors.core.signaling.HubMessage.Welcome;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.List;
import org.junit.jupiter.api.Test;

class HubCodecTest {
  private static final String ALICE = "10920508-d5d8-3eed-93d2-92f193afe7d7";
  private static final String BOB = "faa5dca3-c3d4-354b-ae1b-dde9e5a14b3b";
  private static final String ICE =
      "\"ufrag\":\"abcd\",\"pwd\":\"abcdefghijklmnopqrstuvwx\","
          + "\"candidates\":[{\"k\":\"host\",\"a\":\"192.168.1.4\",\"p\":50000},"
          + "{\"k\":\"srflx\",\"a\":\"2001:db8::1\",\"p\":6000}]";

  @Test
  void encodesHostOpenAndUpdate() {
    JsonObject open = parse(HubCodec.encode(new HostOpen(false, HostMode.FRIENDS, List.of(BOB))));
    assertEquals("host.open", open.get("t").getAsString());
    assertEquals("friends", open.get("mode").getAsString());
    assertEquals(BOB, open.getAsJsonArray("allow").get(0).getAsString());
    assertEquals(3, open.size());
    JsonObject update = parse(HubCodec.encode(new HostOpen(true, HostMode.INVITED, List.of())));
    assertEquals("host.update", update.get("t").getAsString());
    assertEquals("invited", update.get("mode").getAsString());
  }

  @Test
  void encodesJoinRequestWithCandidates() {
    IceParameters ice =
        new IceParameters(
            "abcd",
            "abcdefghijklmnopqrstuvwx",
            List.of(new Candidate(Candidate.Kind.RELAY, "203.0.113.9", 3478)));
    JsonObject json = parse(HubCodec.encode(new JoinRequest(ALICE, ice)));
    assertEquals("join.request", json.get("t").getAsString());
    JsonObject candidate = json.getAsJsonArray("candidates").get(0).getAsJsonObject();
    assertEquals("relay", candidate.get("k").getAsString());
    assertEquals(3478, candidate.get("p").getAsInt());
  }

  @Test
  void decodesEveryInboundType() {
    assertEquals(
        new Welcome(ALICE, "Alice"),
        HubCodec.decode("{\"t\":\"welcome\",\"uuid\":\"" + ALICE + "\",\"name\":\"Alice\"}"));
    assertEquals(
        new DirectoryResult(List.of(ALICE)),
        HubCodec.decode("{\"t\":\"directory.result\",\"joinable\":[\"" + ALICE + "\"]}"));
    assertEquals(
        new DirectoryUpdate(ALICE, true),
        HubCodec.decode(
            "{\"t\":\"directory.update\",\"host\":\"" + ALICE + "\",\"joinable\":true}"));
    assertEquals(
        new DirectoryUpdate(ALICE, false),
        HubCodec.decode(
            "{\"t\":\"directory.update\",\"host\":\"" + ALICE + "\",\"joinable\":false}"));
    JoinOffer offer =
        assertInstanceOf(
            JoinOffer.class,
            HubCodec.decode(
                "{\"t\":\"join.offer\",\"peer\":\""
                    + BOB
                    + "\",\"name\":\"Bob\",\"conv\":2897295744,"
                    + ICE
                    + "}"));
    assertEquals(2, offer.ice().candidates().size());
    assertInstanceOf(
        JoinAccepted.class,
        HubCodec.decode(
            "{\"t\":\"join.answer\",\"host\":\"" + ALICE + "\",\"conv\":128," + ICE + "}"));
    assertEquals(
        new JoinRejected(ALICE, JoinRejectReason.CLOSED),
        HubCodec.decode(
            "{\"t\":\"join.reject\",\"host\":\"" + ALICE + "\",\"reason\":\"closed\"}"));
    TurnCredentials creds =
        assertInstanceOf(
            TurnCredentials.class,
            HubCodec.decode(
                "{\"t\":\"turn.creds\",\"urls\":[\"turn:turn.cloudflare.com:3478?transport=udp\"],"
                    + "\"username\":\"u1\",\"credential\":\"c/2+=\",\"ttl\":3600}"));
    assertEquals(3600, creds.ttlSeconds());
    assertEquals(
        new TurnDenied(true),
        HubCodec.decode("{\"t\":\"turn.denied\",\"reason\":\"monthly_limit\"}"));
    assertEquals(
        new ErrorMessage("rate_limited", 1),
        HubCodec.decode("{\"t\":\"error\",\"code\":\"rate_limited\",\"retryAfter\":1}"));
  }

  @Test
  void rejectsInvalidMessages() {
    assertNull(HubCodec.decode("not json"));
    assertNull(HubCodec.decode("[]"));
    assertNull(HubCodec.decode("{\"t\":\"unknown\"}"));
    assertNull(HubCodec.decode("{\"t\":\"welcome\",\"uuid\":\"ABC\",\"name\":\"Alice\"}"));
    assertNull(
        HubCodec.decode("{\"t\":\"welcome\",\"uuid\":\"" + ALICE + "\",\"name\":\"§cEvil\"}"));
    // conv low byte outside 0x80-0xBF would collide with STUN/TURN demultiplexing.
    assertNull(
        HubCodec.decode(
            "{\"t\":\"join.answer\",\"host\":\"" + ALICE + "\",\"conv\":1," + ICE + "}"));
    assertNull(
        HubCodec.decode(
            "{\"t\":\"join.answer\",\"host\":\"" + ALICE + "\",\"conv\":128.5," + ICE + "}"));
    // Hostnames are never accepted as candidates (no DNS lookups from hub data).
    assertNull(
        HubCodec.decode(
            "{\"t\":\"join.answer\",\"host\":\""
                + ALICE
                + "\",\"conv\":128,\"ufrag\":\"abcd\",\"pwd\":\"abcdefghijklmnopqrstuvwx\","
                + "\"candidates\":[{\"k\":\"host\",\"a\":\"evil.example\",\"p\":1}]}"));
    assertNull(
        HubCodec.decode(
            "{\"t\":\"turn.creds\",\"urls\":[\"turns:x.example:5349?transport=tcp\"],"
                + "\"username\":\"u\",\"credential\":\"c\",\"ttl\":3600}"));
    assertNull(HubCodec.decode("{\"t\":\"error\",\"code\":\"x\",\"retryAfter\":-1}"));
    assertNull(
        HubCodec.decode(
            "{\"t\":\"directory.update\",\"host\":\"" + ALICE + "\",\"joinable\":\"true\"}"));
    assertNull(HubCodec.decode("{\"t\":\"directory.update\",\"host\":\"" + ALICE + "\"}"));
    assertNull(
        HubCodec.decode("{\"t\":\"directory.update\",\"host\":\"Alice\",\"joinable\":true}"));
    assertNull(HubCodec.decode("{\"t\":\"x\",\"pad\":\"" + "a".repeat(5000) + "\"}"));
  }

  private static JsonObject parse(String text) {
    return JsonParser.parseString(text).getAsJsonObject();
  }
}
