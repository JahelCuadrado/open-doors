package com.einsteinmods.opendoors.client;

import com.einsteinmods.opendoors.core.signaling.CertificateProof;
import com.einsteinmods.opendoors.core.signaling.SignalingClient;
import java.util.Optional;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.ProfileKeyPair;
import net.minecraft.world.entity.player.ProfilePublicKey;

/**
 * Proves the account with its Mojang-signed player certificate, the key pair vanilla uses to sign
 * chat ({@code ProfileKeyPairManager#prepareKeyPair}): the challenge is signed with the private key
 * ({@code SHA256withRSA}, as {@code LocalChatSession} does for chat), and the public key goes with
 * Mojang's signature over it ({@code ProfilePublicKey.Data}). Runs on the signaling thread; the key
 * fetch may block it briefly. Accounts without a certificate cannot play online.
 */
public final class CertificateAuthenticator implements SignalingClient.Authenticator {
  private final Minecraft minecraft;

  public CertificateAuthenticator(Minecraft minecraft) {
    this.minecraft = minecraft;
  }

  @Override
  public CertificateProof prove(String serverId) throws Exception {
    Optional<ProfileKeyPair> keyPair = minecraft.getProfileKeyPairManager().prepareKeyPair().join();
    if (keyPair.isEmpty()) {
      throw new SignalingClient.UnavailableException("no player certificate");
    }
    ProfilePublicKey.Data certificate = keyPair.get().publicKey().data();
    if (certificate.hasExpired()) {
      // prepareKeyPair refreshes due keys; an expired one means the refresh failed for now.
      throw new IllegalStateException("player certificate expired");
    }
    return CertificateProof.sign(
        keyPair.get().privateKey(),
        minecraft.getUser().getProfileId(),
        certificate.key().getEncoded(),
        certificate.expiresAt().toEpochMilli(),
        certificate.keySignature(),
        serverId);
  }
}
