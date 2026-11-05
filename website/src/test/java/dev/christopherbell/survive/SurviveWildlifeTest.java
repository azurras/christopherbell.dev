package dev.christopherbell.survive;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.christopherbell.survive.model.SurviveAction;
import dev.christopherbell.survive.model.SurviveEnemy;
import dev.christopherbell.survive.model.SurviveMovement;
import dev.christopherbell.survive.model.SurviveSnapshot.Status;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class SurviveWildlifeTest {
  private static final Instant START = Instant.parse("2026-11-05T14:00:00Z");

  @Test
  void animalsMoveOnlyHostilesAggroAndClaimedAnimalsRespawnAfterCooldown() {
    var wildlife = new SurviveWildlife();
    var hog = wildlife.roaming(START).stream().filter(animal -> animal.kind() == SurviveEnemy.HOG).findFirst().orElseThrow();
    var wolf = wildlife.roaming(START).stream().filter(animal -> animal.kind() == SurviveEnemy.WOLF).findFirst().orElseThrow();
    assertNotEquals(wildlife.roaming(START), wildlife.roaming(START.plusSeconds(1)));
    assertNull(wildlife.nearest(at(hog.x(), hog.z()), START, true));
    assertEquals(hog, wildlife.nearest(at(hog.x(), hog.z()), START, false));
    assertNull(wildlife.nearest(at(0, 0), START, true));
    assertEquals(wolf, wildlife.nearest(at(wolf.x() + 6, wolf.z()), START, true));
    wildlife.claim("scout", wolf);
    assertFalse(wildlife.roaming(START).contains(wolf));
    wildlife.releaseFinished(Set.of("scout"), START);
    assertEquals(wolf, wildlife.encounter("scout"));
    wildlife.releaseFinished(Set.of(), START);
    assertNull(wildlife.encounter("scout"));
    assertEquals(4, wildlife.roaming(START.plusSeconds(34)).size());
    assertEquals(5, wildlife.roaming(START.plusSeconds(35)).size());
  }

  @Test
  void acceptedMovementStartsDurableCombatAndRejectsFishingFromCamp() {
    var clock = new TravelClock();
    var service = new SurviveService(clock, () -> 1, 10, new InMemorySurviveWorldRepository());
    var joined = service.join(null, "Scout");
    var view = service.pulseForOwner(joined.token(), null, new SurviveMovement(0, 0, 0, 0));
    assertThrows(ResponseStatusException.class, () -> service.act(joined.token(), SurviveAction.FISH, 0));
    assertThrows(ResponseStatusException.class, () -> service.act(joined.token(), SurviveAction.HUNT, 0));
    var rejected = service.pulseForOwner(joined.token(), null, new SurviveMovement(-38, -26, 0, 1));
    assertFalse(rejected.accepted());
    assertNull(rejected.encounter());
    for (int step = 2; step < 35 && view.encounter() == null; step++) {
      clock.now = clock.now.plusSeconds(1);
      var wolf = view.wildlife().stream().filter(animal -> animal.id().equals("pine-wolf")).findFirst().orElseThrow();
      double dx = wolf.x() - view.self().x();
      double dz = wolf.z() - view.self().z();
      double fraction = Math.min(1, 5.5 / Math.hypot(dx, dz));
      view = service.pulseForOwner(joined.token(), null,
          new SurviveMovement(view.self().x() + dx * fraction, view.self().z() + dz * fraction, 0, step));
    }
    assertNotNull(view.encounter());
    assertEquals(Status.COMBAT, view.self().status());
    var saved = service.find(joined.token()).orElseThrow();
    assertEquals(SurviveEnemy.WOLF, saved.enemy());
    assertNotNull(saved.nextStrikeAt());
    assertTrue(saved.message().contains("spots you"));
  }

  @Test
  void fishingAwardsFoodOnlyOnCatch() {
    var rolls = new AtomicInteger(60);
    var service = new SurviveService(Clock.fixed(START, ZoneOffset.UTC), rolls::get, 10, new InMemorySurviveWorldRepository());
    var joined = service.join(null, "Angler");
    var caught = service.act(joined.token(), SurviveAction.FISH, 0);
    assertEquals(1, caught.food());
    assertEquals(10, caught.hunger());
    assertTrue(caught.message().contains("reel in a fish"));
    rolls.set(61);
    var missed = service.act(joined.token(), SurviveAction.FISH, caught.revision());
    assertEquals(1, missed.food());
    assertTrue(missed.message().contains("got away"));
  }

  private static SurvivePresence.Pose at(double x, double z) {
    return new SurvivePresence.Pose("scout", "Scout", x, z, 0, 0, Status.EXPLORING);
  }

  private static final class TravelClock extends Clock {
    private Instant now = START;

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return Clock.fixed(now, zone);
    }

    @Override
    public Instant instant() {
      return now;
    }
  }
}
