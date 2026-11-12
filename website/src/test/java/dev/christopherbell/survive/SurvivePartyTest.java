package dev.christopherbell.survive;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.christopherbell.survive.model.SurviveAction;
import dev.christopherbell.survive.model.SurviveParty.MemberVitals;
import dev.christopherbell.survive.model.SurviveParty.Command;
import dev.christopherbell.survive.model.SurviveParty.State;
import dev.christopherbell.survive.model.SurviveRequests.Party;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class SurvivePartyTest {
  private final Clock clock = mock(Clock.class);
  private final Instant now = Instant.parse("2026-10-06T01:00:00Z");
  private final InMemorySurviveWorldRepository repository = new InMemorySurviveWorldRepository();

  @Test
  void onlyInviteeCanAcceptAndBothMembersResumeAfterRestart() {
    var service = startService();
    var scout = service.join(null, "Scout");
    var guard = service.join(null, "Guard");
    var stranger = service.join(null, "Stranger");
    var invitation = service.changePartyForOwner(scout.token(), null,
        new Party(Command.INVITE, UUID.fromString(guard.state().survivorId()))).party();
    assertEquals(State.OUTGOING, invitation.state());
    assertEquals(State.INCOMING, service.find(guard.token()).orElseThrow().party().state());
    assertNull(service.find(stranger.token()).orElseThrow().party());

    var accept = new Party(Command.ACCEPT, invitation.id());
    assertEquals(HttpStatus.CONFLICT, assertThrows(ResponseStatusException.class,
        () -> service.changePartyForOwner(scout.token(), null, accept)).getStatusCode());
    assertEquals(HttpStatus.CONFLICT, assertThrows(ResponseStatusException.class,
        () -> service.changePartyForOwner(stranger.token(), null, accept)).getStatusCode());
    assertEquals(State.MEMBER, service.changePartyForOwner(guard.token(), null, accept).party().state());

    var restarted = startService();
    var membership = restarted.find(scout.token()).orElseThrow().party();
    assertEquals(invitation.id(), membership.id());
    assertEquals("Guard", membership.name());
    assertEquals(State.MEMBER, membership.state());
    assertEquals(HttpStatus.CONFLICT, assertThrows(ResponseStatusException.class,
        () -> restarted.changePartyForOwner(guard.token(), null, accept)).getStatusCode());
    assertNull(restarted.changePartyForOwner(scout.token(), null, new Party(Command.LEAVE, invitation.id())).party());
    assertNull(restarted.find(guard.token()).orElseThrow().party());
  }

  @Test
  void onlyAcceptedPartySeesCurrentPartnerVitals() {
    when(clock.instant()).thenReturn(SurviveClock.WORLD_EPOCH);
    var service = new SurviveService(clock, () -> 50, 10, repository);
    var scout = service.join(null, "Scout");
    var guard = service.join(null, "Guard");
    var invitation = service.changePartyForOwner(scout.token(), null,
        new Party(Command.INVITE, UUID.fromString(guard.state().survivorId()))).party();
    assertNull(invitation.vitals());
    assertNull(service.find(guard.token()).orElseThrow().party().vitals());
    service.changePartyForOwner(guard.token(), null, new Party(Command.ACCEPT, invitation.id()));

    assertEquals(new MemberVitals(10, 10, 0), service.find(scout.token()).orElseThrow().party().vitals());
    var guardState = service.find(guard.token()).orElseThrow();
    var targeted = service.act(guard.token(), SurviveAction.HUNT, guardState.revision());
    var engaged = service.act(guard.token(), SurviveAction.ENGAGE, targeted.revision());
    when(clock.instant()).thenReturn(engaged.nextStrikeAt());

    assertEquals(new MemberVitals(9, 10, 50), service.find(scout.token()).orElseThrow().party().vitals());
    service.changePartyForOwner(scout.token(), null, new Party(Command.LEAVE, invitation.id()));
    assertNull(service.find(guard.token()).orElseThrow().party());
  }

  @Test
  void expiredInvitationCannotAcceptItsReplacement() {
    var service = startService();
    var scout = service.join(null, "Scout");
    var guard = service.join(null, "Guard");
    var invite = new Party(Command.INVITE, UUID.fromString(guard.state().survivorId()));
    var expired = service.changePartyForOwner(scout.token(), null, invite).party();
    when(clock.instant()).thenReturn(now.plusSeconds(120));
    assertNull(service.find(guard.token()).orElseThrow().party());
    var current = service.changePartyForOwner(scout.token(), null, invite).party();
    assertEquals(HttpStatus.CONFLICT, assertThrows(ResponseStatusException.class,
        () -> service.changePartyForOwner(guard.token(), null, new Party(Command.ACCEPT, expired.id()))).getStatusCode());
    assertEquals(current.id(), service.find(guard.token()).orElseThrow().party().id());
  }

  private SurviveService startService() {
    when(clock.instant()).thenReturn(now);
    return new SurviveService(clock, () -> 50, 10, repository);
  }
}
