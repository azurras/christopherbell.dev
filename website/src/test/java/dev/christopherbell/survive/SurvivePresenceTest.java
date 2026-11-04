package dev.christopherbell.survive;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.christopherbell.survive.model.SurviveAction;
import dev.christopherbell.survive.model.SurviveMovement;
import dev.christopherbell.survive.model.SurviveSnapshot.Status;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class SurvivePresenceTest {
  private final SurvivePresence presence = new SurvivePresence();
  private final Instant now = Instant.parse("2026-11-04T12:00:00Z");

  @Test
  void spawnsAtCampAndBoundsSpeedWithoutChangingNewerSequences() {
    var first = presence.pulse("alice", "Alice", Status.EXPLORING, move(50, 0, 1), now);
    assertEquals(0, first.self().x());
    var walked = presence.pulse("alice", "Alice", Status.EXPLORING, move(3, 0, 2), now.plusMillis(500));
    assertEquals(3, walked.self().x());
    var stale = presence.pulse("alice", "Alice", Status.EXPLORING, move(0, 0, 1), now.plusSeconds(1));
    assertEquals(3, stale.self().x());
    var teleport = presence.pulse("alice", "Alice", Status.EXPLORING, move(70, 0, 3), now.plusSeconds(1));
    assertEquals(3, teleport.self().x());
    assertFalse(teleport.accepted());
  }

  @Test
  void returnsOtherPublicSurvivorsAndExpiresIdlePresence() {
    presence.pulse("alice", "<Alice>", Status.EXPLORING, move(0, 0, 1), now);
    var bob = presence.pulse("bob", "Bob", Status.EXPLORING, move(0, 0, 1), now);
    assertEquals("alice", bob.others().getFirst().survivorId());
    assertEquals("<Alice>", bob.others().getFirst().name());
    var later = presence.pulse("bob", "Bob", Status.EXPLORING, move(0, 0, 2), now.plusSeconds(31));
    assertTrue(later.others().isEmpty());
    assertEquals(0, later.self().x());
  }

  @Test
  void validatesFiniteBoundsAndStopsCombatMovement() {
    assertThrows(IllegalArgumentException.class, () -> move(Double.NaN, 0, 1));
    assertThrows(IllegalArgumentException.class, () -> move(90, 0, 1));
    assertThrows(IllegalArgumentException.class, () -> move(0, 0, -1));
    presence.pulse("alice", "Alice", Status.EXPLORING, move(0, 0, 1), now);
    var fighting = presence.pulse("alice", "Alice", Status.COMBAT, move(3, 0, 2), now.plusSeconds(1));
    assertEquals(0, fighting.self().x());
  }

  @Test
  void appliesLandmarksOnlyToSpatialSessionsAndFleeReturnsToCamp() {
    assertTrue(presence.permits("legacy", SurviveAction.GATHER, now));
    presence.pulse("alice", "Alice", Status.EXPLORING, move(0, 0, 1), now);
    assertFalse(presence.permits("alice", SurviveAction.GATHER, now));
    assertTrue(presence.permits("alice", SurviveAction.BUILD_SHELTER, now));
    double distance = Math.hypot(-24, -15);
    for (int step = 1; step <= 6; step++) {
      double traveled = Math.min(distance, step * 5);
      presence.pulse("alice", "Alice", Status.EXPLORING,
          move(-24 * traveled / distance, -15 * traveled / distance, step + 1), now.plusSeconds(step));
    }
    assertTrue(presence.permits("alice", SurviveAction.GATHER, now.plusSeconds(6)));
    presence.returnToCamp("alice");
    assertTrue(presence.permits("alice", SurviveAction.REST, now.plusSeconds(6)));
  }

  private static SurviveMovement move(double x, double z, long sequence) {
    return new SurviveMovement(x, z, 0, sequence);
  }
}
