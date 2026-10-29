package com.einsteinmods.opendoors.core.signaling;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ValidationTest {
  @Test
  void acceptsOnlyIpLiterals() {
    assertTrue(Validation.isIpLiteral("192.168.0.1"));
    assertTrue(Validation.isIpLiteral("2001:db8::1"));
    assertFalse(Validation.isIpLiteral("256.1.1.1"));
    assertFalse(Validation.isIpLiteral("1.2.3"));
    assertFalse(Validation.isIpLiteral("localhost"));
    assertFalse(Validation.isIpLiteral("fe80::1%en0"));
    assertFalse(Validation.isIpLiteral(""));
  }

  @Test
  void convMustKeepItsFirstWireByteInTheApplicationRange() {
    assertTrue(Validation.isConv(0x80));
    assertTrue(Validation.isConv(0xFFFF_FFBFL));
    assertFalse(Validation.isConv(0));
    assertFalse(Validation.isConv(0x40));
    assertFalse(Validation.isConv(0xC0));
    assertFalse(Validation.isConv(0x1_0000_0080L));
  }

  @Test
  void turnUrlsAreUdpOnly() {
    assertTrue(Validation.isTurnUrl("turn:turn.cloudflare.com:3478?transport=udp"));
    assertTrue(Validation.isTurnUrl("turn:turn.cloudflare.com:53?transport=udp"));
    assertFalse(Validation.isTurnUrl("turn:turn.cloudflare.com:80?transport=tcp"));
    assertFalse(Validation.isTurnUrl("turn:turn.cloudflare.com:99999?transport=udp"));
  }
}
