package dev.christopherbell.survive;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.christopherbell.survive.model.SurviveAction;
import dev.christopherbell.survive.model.SurviveMovement;
import dev.christopherbell.survive.model.SurviveSnapshot.Status;
import dev.christopherbell.survive.model.SurviveWeapon;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class SurviveMovementServiceTest {
  private final InMemorySurviveWorldRepository repository = new InMemorySurviveWorldRepository();
  private final SurviveService service = new SurviveService(
      Clock.fixed(Instant.parse("2026-11-04T12:00:00Z"), ZoneOffset.UTC), () -> 50, 10, repository);

  @Test
  void pulseDoesNotWriteProgressAndPublicIdsCannotAuthorizeMovement() {
    var joined = service.join(null, "Alice");
    var saved = repository.load().orElseThrow();
    var pulse = service.pulseForOwner(joined.token(), null, new SurviveMovement(0, 0, 0, 0));
    assertEquals(joined.state().survivorId(), pulse.self().survivorId());
    assertEquals(saved, repository.load().orElseThrow());
    assertEquals(404, assertThrows(ResponseStatusException.class, () -> service.pulseForOwner(
        joined.state().survivorId(), null, new SurviveMovement(0, 0, 0, 1))).getStatusCode().value());
    assertEquals(404, assertThrows(ResponseStatusException.class, () -> service.pulseForOwner(
        null, null, new SurviveMovement(0, 0, 0, 1))).getStatusCode().value());
  }

  @Test
  void observerSeesConfirmedWeaponAndStanceWithoutAnotherFighterPulse() {
    var fighter = service.join(null, "Fighter");
    var observer = service.join(null, "Observer");
    var gathered = service.act(fighter.token(), SurviveAction.GATHER, fighter.state().revision());
    gathered = service.act(fighter.token(), SurviveAction.GATHER, gathered.revision());
    var armed = service.act(fighter.token(), SurviveAction.CRAFT_CLUB, gathered.revision());
    var targeted = service.act(fighter.token(), SurviveAction.HUNT, armed.revision());
    service.pulseForOwner(fighter.token(), null, new SurviveMovement(0, 0, 0, 0));

    var idle = service.pulseForOwner(observer.token(), null, new SurviveMovement(0, 0, 0, 0)).others().getFirst();
    assertEquals(fighter.state().survivorId(), idle.survivorId());
    assertEquals(Status.COMBAT, idle.status());
    assertEquals(new SurvivePresence.Appearance(SurviveWeapon.CLUB, false, false), idle.appearance());

    var engaged = service.act(fighter.token(), SurviveAction.ENGAGE, targeted.revision());
    var ready = service.pulseForOwner(observer.token(), null, new SurviveMovement(0, 0, 0, 1)).others().getFirst();
    assertEquals(new SurvivePresence.Appearance(SurviveWeapon.CLUB, true, false), ready.appearance());
    var guarded = service.act(fighter.token(), SurviveAction.DEFEND, engaged.revision());
    var braced = service.pulseForOwner(observer.token(), null, new SurviveMovement(0, 0, 0, 2)).others().getFirst();
    assertEquals(new SurvivePresence.Appearance(SurviveWeapon.CLUB, true, true), braced.appearance());

    service.act(fighter.token(), SurviveAction.DISENGAGE, guarded.revision());
    var stowed = service.pulseForOwner(observer.token(), null, new SurviveMovement(0, 0, 0, 3)).others().getFirst();
    assertEquals(idle, stowed);
  }

  @Test
  void existingCommandEndpointEnforcesLandmarksAndReplacementDiscardsOldPresence() {
    var alice = service.join(null, "Alice");
    service.pulseForOwner(alice.token(), null, new SurviveMovement(0, 0, 0, 0));
    assertEquals(400, assertThrows(ResponseStatusException.class,
        () -> service.act(alice.token(), SurviveAction.GATHER, 0)).getStatusCode().value());
    assertEquals(0, service.find(alice.token()).orElseThrow().revision());
    service.leaveIslandForOwner(alice.token(), null);
    assertEquals(1, service.act(alice.token(), SurviveAction.GATHER, 0).wood());
    service.pulseForOwner(alice.token(), null, new SurviveMovement(0, 0, 0, 1));
    var replaced = service.join(alice.token(), "Replacement");
    var bob = service.join(null, "Bob");
    var view = service.pulseForOwner(bob.token(), null, new SurviveMovement(0, 0, 0, 0));
    assertEquals(0, view.others().size());
    assertEquals(404, assertThrows(ResponseStatusException.class, () -> service.pulseForOwner(
        alice.token(), null, new SurviveMovement(0, 0, 0, 1))).getStatusCode().value());
    assertEquals(1, service.act(replaced.token(), SurviveAction.GATHER, 0).wood());
  }

  @Test
  void waystationTalkRequiresAnAcknowledgedNearbyPose() {
    var joined = service.join(null, "Scout");
    assertEquals(400, assertThrows(ResponseStatusException.class,
        () -> service.act(joined.token(), SurviveAction.TALK_WAYSTATION, 0)).getStatusCode().value());
    service.pulseForOwner(joined.token(), null, new SurviveMovement(0, 0, 0, 0));
    assertEquals(400, assertThrows(ResponseStatusException.class,
        () -> service.act(joined.token(), SurviveAction.TALK_WAYSTATION, 0)).getStatusCode().value());
    assertEquals("AVAILABLE", service.find(joined.token()).orElseThrow().waystationAssignment().name());
  }

  @Test
  void authenticatedOwnershipOverridesAnUnrelatedGuestCookie() {
    var guest = service.join(null, "Guest");
    var account = service.joinForOwner(null, "Account", "owner");
    var pulse = service.pulseForOwner(guest.token(), "owner", new SurviveMovement(0, 0, 0, 1));
    assertEquals(account.state().survivorId(), pulse.self().survivorId());
    var guestPulse = service.pulseForOwner(guest.token(), null, new SurviveMovement(0, 0, 0, 1));
    assertEquals(guest.state().survivorId(), guestPulse.self().survivorId());
  }
}
