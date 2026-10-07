package dev.christopherbell.survive;

import static org.junit.jupiter.api.Assertions.*;
import dev.christopherbell.survive.model.SurviveAction;
import dev.christopherbell.survive.model.SurviveResource;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class SavedSurviveServiceTest {
  private final Clock clock = Clock.fixed(Instant.parse("2026-10-06T12:00:00Z"), ZoneOffset.UTC);
  private SurviveService service(InMemorySurviveWorldRepository repository) {
    return new SurviveService(clock, () -> 50, 1000, repository);
  }

  @Test void twoSessionsAndRestartsResumeOneAccountWithoutCookieOwnership() {
    var repository = new InMemorySurviveWorldRepository();
    var first = service(repository);
    var joined = first.joinForOwner(null, "Saved", "alice");
    assertNull(joined.token());
    var moved = first.actForOwner(null, "alice", SurviveAction.GATHER, 0);
    var resumed = service(repository).findForOwner(null, "alice").orElseThrow();
    assertEquals(moved, resumed);
    assertTrue(resumed.saved());
    assertEquals(resumed.survivorId(), first.joinForOwner(null, "Different", "alice").state().survivorId());
    assertTrue(first.findForOwner(null, "bob").isEmpty());
    assertTrue(first.find("account:alice").isEmpty());
    assertTrue(first.find(resumed.survivorId()).isEmpty());
  }

  @Test void failedGiftSaveLeavesBothInventoriesAndJournalUnchanged() {
    var repository = new InMemorySurviveWorldRepository();
    var world = service(repository);
    world.joinForOwner(null, "Alice", "alice");
    var bob = world.joinForOwner(null, "Bob", "bob").state();
    var alice = world.actForOwner(null, "alice", SurviveAction.GATHER, 0);
    repository.failNextSave = true;
    assertThrows(org.springframework.dao.DataAccessResourceFailureException.class,
        () -> world.giveSuppliesForOwner(null, "alice", bob.survivorId(), SurviveResource.WOOD, 1, alice.revision()));
    assertEquals(alice, world.findForOwner(null, "alice").orElseThrow());
    assertEquals(0, world.findForOwner(null, "bob").orElseThrow().wood());
    var given = world.giveSuppliesForOwner(null, "alice", bob.survivorId(), SurviveResource.WOOD, 1, alice.revision());
    assertEquals(0, service(repository).findForOwner(null, "alice").orElseThrow().wood());
    var received = service(repository).findForOwner(null, "bob").orElseThrow();
    assertEquals(1, received.wood());
    assertEquals(given.worldRevision(), received.worldRevision());
    assertEquals(409, assertThrows(ResponseStatusException.class,
        () -> world.actForOwner(null, "bob", SurviveAction.GATHER, bob.revision())).getStatusCode().value());
  }

  @Test void savedCombatAndCampStructuresResumeAndGuestsRemainSeparate() {
    var repository = new InMemorySurviveWorldRepository();
    var world = service(repository);
    var guest = world.join(null, "Guest");
    world.joinForOwner(guest.token(), "Alice", "alice");
    for (int move = 0; move < 5; move++) {
      var state = world.findForOwner(null, "alice").orElseThrow();
      world.actForOwner(null, "alice", SurviveAction.GATHER, state.revision());
    }
    var gathered = world.findForOwner(null, "alice").orElseThrow();
    var camp = world.actForOwner(null, "alice", SurviveAction.BUILD_SHELTER, gathered.revision());
    var combat = world.actForOwner(null, "alice", SurviveAction.HUNT, camp.revision());
    assertEquals(combat, service(repository).findForOwner(null, "alice").orElseThrow());
    assertEquals(1, combat.shelters());
    assertEquals(5, combat.enemyHealth());
    assertEquals(0, world.find(guest.token()).orElseThrow().wood());
    assertFalse(world.find(guest.token()).orElseThrow().saved());
  }

  @Test void inactiveAccountSurvivorsAreRetainedButGuestsExpireAndTargetsDisappear() {
    var repository = new InMemorySurviveWorldRepository();
    var world = service(repository);
    world.joinForOwner(null, "Alice", "alice");
    var bob = world.joinForOwner(null, "Bob", "bob").state();
    var guest = world.join(null, "Guest");
    var later = new SurviveService(Clock.offset(clock, java.time.Duration.ofHours(3)), () -> 2, 1000, repository);
    assertTrue(later.find(guest.token()).isEmpty());
    var alice = later.findForOwner(null, "alice").orElseThrow();
    assertEquals(java.util.List.of("Alice"), alice.survivors());
    assertTrue(alice.recipients().isEmpty());
    assertEquals(bob.survivorId(), later.findForOwner(null, "bob").orElseThrow().survivorId());
    assertEquals(2, later.findForOwner(null, "alice").orElseThrow().survivors().size());
  }
  @Test void competingProcessesCannotOverwriteAnAcknowledgedMove() throws Exception {
    var loaded = new java.util.concurrent.CountDownLatch(2);
    var release = new java.util.concurrent.CountDownLatch(1);
    var racing = new java.util.concurrent.atomic.AtomicBoolean(false);
    var repository = new InMemorySurviveWorldRepository() {
      @Override public java.util.Optional<dev.christopherbell.survive.model.SurviveSavedWorld> load() {
        var snapshot = super.load();
        if (racing.get()) {
          loaded.countDown();
          try { if (!release.await(5, java.util.concurrent.TimeUnit.SECONDS)) throw new AssertionError("Race not released"); }
          catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new AssertionError(interrupted); }
        }
        return snapshot;
      }
    };
    var first = service(repository);
    var second = service(repository);
    first.joinForOwner(null, "Alice", "alice");
    racing.set(true);
    java.util.function.Supplier<Integer> moveFirst = () -> {
      try { first.actForOwner(null, "alice", SurviveAction.GATHER, 0); return 200; }
      catch (ResponseStatusException rejected) { return rejected.getStatusCode().value(); }
    };
    var one = java.util.concurrent.CompletableFuture.supplyAsync(moveFirst);
    var two = java.util.concurrent.CompletableFuture.supplyAsync(() -> {
      try { second.actForOwner(null, "alice", SurviveAction.GATHER, 0); return 200; }
      catch (ResponseStatusException rejected) { return rejected.getStatusCode().value(); }
    });
    try { assertTrue(loaded.await(5, java.util.concurrent.TimeUnit.SECONDS)); }
    finally { release.countDown(); }
    assertEquals(java.util.Set.of(200, 409), java.util.Set.of(one.get(5, java.util.concurrent.TimeUnit.SECONDS), two.get(5, java.util.concurrent.TimeUnit.SECONDS)));
    racing.set(false);
    var resumed = service(repository).findForOwner(null, "alice").orElseThrow();
    assertEquals(1, resumed.wood());
    assertEquals(1, resumed.revision());
  }

  @Test void terminalAccountCanReplaceItsCharacterWithoutResettingCamp() {
    var repository = new InMemorySurviveWorldRepository();
    var world = service(repository);
    world.joinForOwner(null, "Alice", "alice");
    var old = world.findForOwner(null, "alice").orElseThrow();
    for (int hit = 0; hit < 10; hit++) {
      var state = world.findForOwner(null, "alice").orElseThrow();
      if (state.status() != dev.christopherbell.survive.model.SurviveSnapshot.Status.COMBAT) {
        state = world.actForOwner(null, "alice", SurviveAction.HUNT, state.revision());
      }
      world.actForOwner(null, "alice", SurviveAction.ATTACK, state.revision());
    }
    assertEquals(dev.christopherbell.survive.model.SurviveSnapshot.Status.DEAD,
        world.findForOwner(null, "alice").orElseThrow().status());
    var replacement = world.joinForOwner(null, "New Alice", "alice").state();
    assertNotEquals(old.survivorId(), replacement.survivorId());
    assertEquals(10, replacement.health());
    assertTrue(replacement.revision() > old.revision());
    assertEquals(409, assertThrows(ResponseStatusException.class,
        () -> world.actForOwner(null, "alice", SurviveAction.GATHER, old.revision())).getStatusCode().value());
    assertEquals(1, repository.load().orElseThrow().players().size());
  }

}
