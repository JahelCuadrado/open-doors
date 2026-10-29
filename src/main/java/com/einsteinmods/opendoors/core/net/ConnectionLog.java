package com.einsteinmods.opendoors.core.net;

import java.util.function.Consumer;

/**
 * Opt-in connection diagnostics ("Connection diagnostics" setting): one line per connection step,
 * with the addresses needed to tell why two players could not connect. Off by default; while off,
 * every call site costs one volatile read ({@link #on}) and builds no strings. Never pass secrets
 * (ICE passwords, TURN credentials, tokens).
 */
public final class ConnectionLog {
  private static volatile Consumer<String> sink;

  private ConnectionLog() {}

  /** Whether diagnostics are on; check it before building a message. */
  public static boolean on() {
    return sink != null;
  }

  /** Turns diagnostics on (lines go to {@code target}) or off ({@code null}). */
  public static void setSink(Consumer<String> target) {
    sink = target;
  }

  public static void log(String message) {
    Consumer<String> target = sink;
    if (target != null) {
      target.accept(message);
    }
  }
}
