package com.einsteinmods.opendoors.core.signaling;

import java.util.List;

/**
 * What one side of a join sends the other through the hub: ICE credentials and candidates (RFC 8445
 * §5.3).
 */
public record IceParameters(String ufrag, String password, List<Candidate> candidates) {
  public IceParameters {
    if (!Validation.isUfrag(ufrag)
        || !Validation.isPassword(password)
        || candidates == null
        || candidates.size() > Validation.MAX_CANDIDATES) {
      throw new IllegalArgumentException("invalid ICE parameters");
    }
    candidates = List.copyOf(candidates);
  }
}
