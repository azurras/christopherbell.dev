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
import dev.christopherbell.survive.model.SurviveParty.Command;
import dev.christopherbell.survive.model.SurviveRequests.Party;
import dev.christopherbell.survive.model.SurviveSnapshot.Status;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class SurviveWildlifeTest {
  private static final Instant START = Instant.parse("2026-11-05T14:00:00Z");

  @Test
  void engageUsesTheSelectedCreatureAndNeverSubstitutesAnother() {
    var clock = new TravelClock();
    clock.now = SurviveClock.WORLD_EPOCH.plusSeconds(300);
    var service = new SurviveService(clock, () -> 1, 10, new InMemorySurviveWorldRepository());
    var joined = service.join(null, "Scout");
    var view = service.pulseForOwner(joined.token(), null, new SurviveMovement(0, 0, 0, 0));
    for (int step = 1; step <= 20; step++) {
      var hog = view.wildlife().stream().filter(animal -> animal.id().equals("meadow-hog")).findFirst().orElseThrow();
      double dx = hog.x() - view.self().x();
      double dz = hog.z() - view.self().z();
      double distance = Math.hypot(dx, dz);
      if (distance < 3) {
        break;
      }
      clock.now = clock.now.plusSeconds(1);
      double fraction = Math.min(1, 5.5 / distance);
      view = service.pulseForOwner(joined.token(), null,
          new SurviveMovement(view.self().x() + dx * fraction, view.self().z() + dz * fraction, 0, step));
    }
    var current = service.find(joined.token()).orElseThrow();
    assertThrows(ResponseStatusException.class,
        () -> service.actForOwner(joined.token(), null, SurviveAction.ENGAGE, current.revision(), "shore-hog"));
    var engaged = service.actForOwner(joined.token(), null, SurviveAction.ENGAGE, current.revision(), "meadow-hog");
    assertEquals(Status.COMBAT, engaged.status());
    assertNotNull(engaged.nextStrikeAt());
    var encounter = service.pulseForOwner(joined.token(), null,
        new SurviveMovement(view.self().x(), view.self().z(), 0, 21)).encounter();
    assertEquals("meadow-hog", encounter.id());
  }

  @Test
  void partySharesDamageAndOneDefeatAcrossRestart() {
    var clock = new TravelClock();
    clock.now = SurviveClock.WORLD_EPOCH.plusSeconds(300);
    var repository = new InMemorySurviveWorldRepository();
    var service = new SurviveService(clock, () -> 1, 10, repository);
    var scout = service.join(null, "Scout");
    var guard = service.join(null, "Guard");
    var observer = service.join(null, "Observer");
    var invite = service.changePartyForOwner(scout.token(), null,
        new Party(Command.INVITE, UUID.fromString(guard.state().survivorId()))).party();
    service.changePartyForOwner(guard.token(), null, new Party(Command.ACCEPT, invite.id()));
    var tokens = List.of(scout.token(), guard.token(), observer.token());
    var view = service.pulseForOwner(scout.token(), null, new SurviveMovement(0, 0, 0, 0));
    for (var token : tokens.subList(1, 3)) {
      service.pulseForOwner(token, null, new SurviveMovement(0, 0, 0, 0));
    }
    for (int sequence = 1; sequence <= 20; sequence++) {
      var hog = view.wildlife().stream().filter(animal -> animal.id().equals("meadow-hog")).findFirst().orElseThrow();
      double dx = hog.x() - view.self().x();
      double dz = hog.z() - view.self().z();
      double distance = Math.hypot(dx, dz);
      if (distance < 2) break;
      clock.now = clock.now.plusSeconds(1);
      double fraction = Math.min(1, 5.5 / distance);
      var movement = new SurviveMovement(view.self().x() + dx * fraction, view.self().z() + dz * fraction, 0, sequence);
      for (var token : tokens) view = service.pulseForOwner(token, null, movement);
    }
    service.actForOwner(scout.token(), null, SurviveAction.ENGAGE, scout.state().revision(), "meadow-hog");
    var outsiderClaim = service.pulseForOwner(observer.token(), null,
        new SurviveMovement(view.self().x(), view.self().z(), 0, 21)).wildlife().stream()
        .filter(animal -> animal.id().equals("meadow-hog")).findFirst().orElseThrow();
    assertTrue(outsiderClaim.claimed());
    assertFalse(outsiderClaim.joinable());
    assertThrows(ResponseStatusException.class, () -> service.actForOwner(observer.token(), null,
        SurviveAction.ENGAGE, observer.state().revision(), "meadow-hog"));
    service.actForOwner(guard.token(), null, SurviveAction.ENGAGE, guard.state().revision(), "meadow-hog");
    clock.now = clock.now.plusSeconds(3);
    assertEquals(1, service.find(scout.token()).orElseThrow().enemyHealth());
    assertEquals(1, service.find(guard.token()).orElseThrow().enemyHealth());

    var restarted = new SurviveService(clock, () -> 1, 10, repository);
    var guardBeforeDefeat = restarted.find(guard.token()).orElseThrow();
    assertEquals(1, guardBeforeDefeat.enemyHealth());
    var resumed = restarted.pulseForOwner(guard.token(), null, new SurviveMovement(0, 0, 0, 0));
    assertEquals(outsiderClaim.x(), resumed.self().x());
    assertEquals("meadow-hog", resumed.encounter().id());
    clock.now = clock.now.plusSeconds(3);
    var finished = restarted.find(scout.token()).orElseThrow();
    assertEquals(Status.EXPLORING, finished.status());
    assertEquals(Status.EXPLORING, restarted.find(guard.token()).orElseThrow().status());
    assertEquals(1, finished.events().stream().filter(event -> event.contains("The party defeated the hog")).count());
    assertEquals(1, restarted.find(scout.token()).orElseThrow().food() + restarted.find(guard.token()).orElseThrow().food());
    assertEquals(409, assertThrows(ResponseStatusException.class, () -> restarted.act(
        guard.token(), SurviveAction.ATTACK, guardBeforeDefeat.revision())).getStatusCode().value());
    var afterDefeat = restarted.pulseForOwner(observer.token(), null, new SurviveMovement(0, 0, 0, 0));
    assertFalse(afterDefeat.wildlife().stream().anyMatch(animal -> animal.id().equals("meadow-hog")));
  }

  @Test
  void nightRaidApproachesCampAndCanBeDefeatedAfterRestart() {
    var clock = new TravelClock();
    var nightfall = SurviveClock.WORLD_EPOCH.plusSeconds(18 * 60);
    clock.now = nightfall.minusSeconds(1);
    var repository = new InMemorySurviveWorldRepository();
    var service = new SurviveService(clock, () -> 1, 10, repository);
    var joined = service.join(null, "Scout");
    clock.now = nightfall;
    var approach = service.pulseForOwner(joined.token(), null, new SurviveMovement(0, 0, 0, 0));
    assertTrue(approach.wildlife().stream().anyMatch(animal -> animal.kind() == SurviveEnemy.RAIDER));
    assertNull(approach.encounter());
    clock.now = nightfall.plusSeconds(30);
    var fight = service.pulseForOwner(joined.token(), null, new SurviveMovement(0, 0, 0, 1));
    assertEquals(SurviveEnemy.RAIDER, fight.encounter().kind());
    assertEquals(Status.COMBAT, fight.self().status());

    clock.now = nightfall.plusSeconds(42);
    var restarted = new SurviveService(clock, () -> 1, 10, repository);
    var won = restarted.find(joined.token()).orElseThrow();
    assertEquals(Status.EXPLORING, won.status());
    assertTrue(won.combatOutcome().contains("defeated"));
    assertTrue(won.events().stream().anyMatch(event -> event.contains("repelled the raid")));
    clock.now = nightfall.plusSeconds(61);
    var settled = restarted.find(joined.token()).orElseThrow();
    assertFalse(settled.events().stream().anyMatch(event -> event.contains("overran")));
  }

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
