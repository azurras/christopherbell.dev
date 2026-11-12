package dev.christopherbell.survive.model;

import java.time.Instant;
import java.util.UUID;

/** A two-survivor invitation or accepted membership. The ID distinguishes successive invites. */
public record SurviveParty(String id, String inviterId, String inviteeId, Instant expiresAt) {
  public SurviveParty {
    UUID.fromString(id);
    UUID.fromString(inviterId);
    UUID.fromString(inviteeId);
    if (inviterId.equals(inviteeId)) {
      throw new IllegalArgumentException("A survivor cannot form a party with themselves.");
    }
  }

  public boolean includes(String survivorId) {
    return inviterId.equals(survivorId) || inviteeId.equals(survivorId);
  }

  public boolean accepted() {
    return expiresAt == null;
  }

  public SurviveParty accept() {
    return new SurviveParty(id, inviterId, inviteeId, null);
  }

  public enum Command { INVITE, ACCEPT, DECLINE, CANCEL, LEAVE }

  /** The other survivor and the relationship visible to the requesting player. */
  public record View(UUID id, String name, State state, MemberVitals vitals) {}

  /** Confirmed battle resources visible only to the accepted partner. */
  public record MemberVitals(int health, int maxHealth, int tacticalPoints) {}

  public enum State { INCOMING, OUTGOING, MEMBER }
}
