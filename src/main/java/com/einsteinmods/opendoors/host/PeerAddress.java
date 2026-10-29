package com.einsteinmods.opendoors.host;

import java.net.SocketAddress;
import java.util.UUID;

/**
 * Remote address of a friend connected through Open Doors. Carries the identity the backend
 * verified (Mojang-signed player certificate) before relaying the join, so the login can be checked
 * against it. {@code toString} has no IP: vanilla logs and IP bans read it.
 */
public final class PeerAddress extends SocketAddress {
  private final UUID profileId;
  private final String name;

  public PeerAddress(UUID profileId, String name) {
    this.profileId = profileId;
    this.name = name;
  }

  /** The Minecraft account the backend authenticated for this stream. */
  public UUID profileId() {
    return profileId;
  }

  public String name() {
    return name;
  }

  @Override
  public boolean equals(Object other) {
    return other instanceof PeerAddress address && address.profileId.equals(profileId);
  }

  @Override
  public int hashCode() {
    return profileId.hashCode();
  }

  @Override
  public String toString() {
    return "opendoors/" + name;
  }
}
