package com.einsteinmods.opendoors.core.signaling;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class BackendEndpointTest {
  private static final String SERVER_ID = "0123456789abcdef0123456789abcdef01234567";

  @Test
  void derivesChallengeAndSocketUrls() {
    BackendEndpoint endpoint =
        BackendEndpoint.parse("https://open-doors.example.workers.dev/").orElseThrow();
    assertEquals(
        "https://open-doors.example.workers.dev/v1/auth/challenge",
        endpoint.challenge().toString());
    assertEquals(
        "wss://open-doors.example.workers.dev/v1/ws?name=Alice&serverId=" + SERVER_ID,
        endpoint.socket("Alice", SERVER_ID).toString());
    BackendEndpoint dev = BackendEndpoint.parse("http://127.0.0.1:8787").orElseThrow();
    assertEquals("ws://127.0.0.1:8787/v1/ws?dev=1&name=Bob", dev.socket("Bob", null).toString());
  }

  @Test
  void refusesInsecureOrOddUrls() {
    assertTrue(BackendEndpoint.parse("http://example.com").isEmpty());
    assertTrue(BackendEndpoint.parse("ftp://example.com").isEmpty());
    assertTrue(BackendEndpoint.parse("https://user@example.com").isEmpty());
    assertTrue(BackendEndpoint.parse("https://example.com/?a=1").isEmpty());
    assertTrue(BackendEndpoint.parse(" ").isEmpty());
  }

  @Test
  void devPropertyThenConfigThenBuild() {
    assertEquals(
        "http://127.0.0.1:8787",
        BackendUrls.resolve("http://127.0.0.1:8787", "https://a.example", "https://b.example")
            .orElseThrow()
            .base()
            .toString());
    assertEquals(
        "https://b.example",
        BackendUrls.resolve(null, " ", "https://b.example").orElseThrow().base().toString());
    assertTrue(BackendUrls.resolve(null, "", "").isEmpty());
  }
}
