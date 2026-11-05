package dev.christopherbell.survive;

import dev.christopherbell.survive.model.SurviveParty;
import dev.christopherbell.survive.model.SurviveParty.State;
import dev.christopherbell.survive.model.SurviveRequests;
import dev.christopherbell.survive.model.SurviveSnapshot.Status;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** Owns invitation transitions inside the same world transaction as survivor state. */
final class SurviveParties {
  private static final Duration INVITATION_LIFETIME = Duration.ofMinutes(2);
  private final List<SurviveParty> parties = new ArrayList<>();

  void restore(List<SurviveParty> savedParties) {
    parties.addAll(savedParties);
  }

  List<SurviveParty> save() {
    return List.copyOf(parties);
  }

  SurviveParty.View viewFor(SurvivePlayer survivor, Collection<SurvivePlayer> players, Instant now) {
    var party = partyFor(survivor.survivorId);
    if (party == null || !available(survivor) || !current(party, now)) {
      return null;
    }
    boolean sentInvite = party.inviterId().equals(survivor.survivorId);
    var partner = find(players, sentInvite ? party.inviteeId() : party.inviterId());
    if (!available(partner)) {
      return null;
    }
    var state = party.accepted() ? State.MEMBER : sentInvite ? State.OUTGOING : State.INCOMING;
    return new SurviveParty.View(party.id(), partner.name, state);
  }

  void apply(SurvivePlayer survivor, SurviveRequests.Party request, Collection<SurvivePlayer> players, Instant now) {
    var availableIds = players.stream().filter(SurviveParties::available)
        .map(player -> player.survivorId).collect(Collectors.toSet());
    parties.removeIf(party -> !current(party, now) || !availableIds.contains(party.inviterId())
        || !availableIds.contains(party.inviteeId()));
    var party = partyFor(survivor.survivorId);
    if (request.command() == SurviveParty.Command.INVITE) {
      invite(survivor, find(players, request.targetId().toString()), party, now);
      return;
    }
    if (party == null || !party.id().equals(request.targetId())) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "That party invitation or membership is no longer current.");
    }
    boolean isInviter = party.inviterId().equals(survivor.survivorId);
    boolean allowed = switch (request.command()) {
      case ACCEPT, DECLINE -> !party.accepted() && !isInviter;
      case CANCEL -> !party.accepted() && isInviter;
      case LEAVE -> party.accepted();
      case INVITE -> false;
    };
    if (!allowed) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "That party command is no longer available.");
    }
    if (request.command() == SurviveParty.Command.ACCEPT
        && (survivor.status != Status.EXPLORING || find(players, party.inviterId()).status != Status.EXPLORING)) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "Both survivors must be exploring to form a party.");
    }
    parties.remove(party);
    if (request.command() == SurviveParty.Command.ACCEPT) {
      parties.add(party.accept());
    }
  }

  private void invite(SurvivePlayer inviter, SurvivePlayer invitee, SurviveParty existingParty, Instant now) {
    if (invitee == null || invitee == inviter || invitee.status != Status.EXPLORING
        || inviter.status != Status.EXPLORING || !invitee.lastSeen.isAfter(now.minus(SurviveService.IDLE_TIMEOUT))) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose another active survivor who is exploring.");
    }
    if (existingParty != null || partyFor(invitee.survivorId) != null) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "One survivor already has a party or invitation.");
    }
    parties.add(new SurviveParty(UUID.randomUUID(), inviter.survivorId, invitee.survivorId, now.plus(INVITATION_LIFETIME)));
  }

  private SurviveParty partyFor(String survivorId) {
    return parties.stream().filter(party -> party.includes(survivorId)).findFirst().orElse(null);
  }

  private static SurvivePlayer find(Collection<SurvivePlayer> players, String survivorId) {
    return players.stream().filter(player -> player.survivorId.equals(survivorId)).findFirst().orElse(null);
  }

  private static boolean current(SurviveParty party, Instant now) {
    return party.accepted() || party.expiresAt().isAfter(now);
  }

  private static boolean available(SurvivePlayer player) {
    return player != null && player.status != Status.DEAD && player.status != Status.ESCAPED;
  }
}
