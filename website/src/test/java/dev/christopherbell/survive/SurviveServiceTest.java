package dev.christopherbell.survive;

import dev.christopherbell.survive.model.SurviveAction;
import dev.christopherbell.survive.model.SurviveSnapshot;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class SurviveServiceTest {
  private final Clock clock = Clock.fixed(Instant.parse("2026-10-06T01:00:00Z"), ZoneOffset.UTC);

  @Test
  void survivorsShareCampButKeepSeparateInventoryAndRestartDoesNotResetWorld() {
    var service = new SurviveService(clock, () -> 2, 10);
    var alice = service.join(null, "Alice");
    var bob = service.join(null, "Bob");
    for (int count = 0; count < 5; count++) act(service, alice.token(), SurviveAction.GATHER);
    act(service, alice.token(), SurviveAction.BUILD_SHELTER);
    var bobState = service.find(bob.token()).orElseThrow();
    assertEquals(1, bobState.shelters());
    assertEquals(0, bobState.wood());
    assertEquals(10, bobState.health());
    assertEquals(2, bobState.strength());
    assertEquals(2, bobState.survivors().size());
    assertTrue(bobState.events().stream().anyMatch(event -> event.contains("Alice: built")));
    var restarted = service.join(alice.token(), "Alice again");
    assertTrue(service.find(alice.token()).isEmpty());
    assertEquals(1, restarted.state().shelters());
    assertEquals(2, restarted.state().survivors().size());
    assertEquals(0, restarted.state().wood());
    assertEquals("Bob", service.find(bob.token()).orElseThrow().name());
  }

  @Test
  void capacityAndIdleExpiryAreBoundedWithoutResettingTheCamp() {
    var mutableClock = mock(Clock.class);
    when(mutableClock.instant()).thenReturn(clock.instant());
    var service = new SurviveService(mutableClock, () -> 2, 1);
    var player = service.join(null, "Chris");
    assertEquals(503, assertThrows(ResponseStatusException.class,
        () -> service.join(null, "Extra")).getStatusCode().value());
    var replacement = service.join(player.token(), "Replacement");
    for (int count = 0; count < 5; count++) act(service, replacement.token(), SurviveAction.GATHER);
    act(service, replacement.token(), SurviveAction.BUILD_SHELTER);
    when(mutableClock.instant()).thenReturn(clock.instant().plus(Duration.ofHours(2)));
    assertTrue(service.find(replacement.token()).isEmpty());
    assertEquals(1, service.join(null, "Later").state().shelters());
    assertTrue(service.find("unknown-token").isEmpty());
  }

  @Test
  void twoSurvivorsCannotConsumeTheSameSharedBoat() throws Exception {
    var service = new SurviveService(clock, () -> 2, 10);
    var alice = service.join(null, "Alice");
    var bob = service.join(null, "Bob");
    for (int count = 0; count < 10; count++) act(service, alice.token(), SurviveAction.GATHER);
    act(service, alice.token(), SurviveAction.BUILD_BOAT);
    var start = new CountDownLatch(1);
    var aliceEscape = CompletableFuture.supplyAsync(() -> attemptEscape(service, alice.token(), start));
    var bobEscape = CompletableFuture.supplyAsync(() -> attemptEscape(service, bob.token(), start));
    start.countDown();
    assertEquals(1, aliceEscape.get(5, TimeUnit.SECONDS) + bobEscape.get(5, TimeUnit.SECONDS));
    assertEquals(0, service.find(alice.token()).orElseThrow().boats());
    assertEquals(0, service.find(bob.token()).orElseThrow().boats());
  }

  private int attemptEscape(SurviveService service, String token, CountDownLatch start) {
    try {
      if (!start.await(5, TimeUnit.SECONDS)) throw new AssertionError("Escape start timed out");
      act(service, token, SurviveAction.ESCAPE);
      return 1;
    } catch (ResponseStatusException rejected) {
      assertEquals(400, rejected.getStatusCode().value());
      return 0;
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new AssertionError(interrupted);
    }
  }

  @Test
  void staleActionsAreRejectedWithoutApplyingThemAgain() {
    var service = new SurviveService(clock, () -> 2, 10);
    var player = service.join(null, "Chris");
    service.act(player.token(), SurviveAction.GATHER, 0);
    assertEquals(409, assertThrows(ResponseStatusException.class,
        () -> service.act(player.token(), SurviveAction.GATHER, 0)).getStatusCode().value());
    assertEquals(1, service.find(player.token()).orElseThrow().wood());
    assertEquals(404, assertThrows(ResponseStatusException.class,
        () -> service.act("missing", SurviveAction.GATHER, 0)).getStatusCode().value());
  }

  @Test
  void rejectsInvalidNamesBeforeCreatingPlayers() {
    var service = new SurviveService(clock, () -> 2, 10);
    for (var name : new String[] {"", " ", "a".repeat(33), "bad\nname"}) {
      assertEquals(400, assertThrows(ResponseStatusException.class,
          () -> service.join(null, name)).getStatusCode().value());
    }
    assertEquals("Chris", service.join(null, " Chris ").state().name());
  }

  private SurviveSnapshot act(SurviveService service, String token, SurviveAction action) {
    return service.act(token, action, service.find(token).orElseThrow().revision());
  }
}
