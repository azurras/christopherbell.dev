package dev.christopherbell.configuration.mongo.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;

import dev.christopherbell.survive.model.SurviveSavedWorld;
import dev.christopherbell.survive.model.SurviveEnemy;
import dev.christopherbell.survive.model.SurviveFieldFight;
import dev.christopherbell.survive.model.SurviveParty;
import dev.christopherbell.survive.model.SurviveSnapshot.Status;
import dev.christopherbell.survive.model.SurviveWeapon;
import dev.christopherbell.survive.model.SurviveStrike;
import dev.christopherbell.survive.persistence.MongoSurviveWorldRepository;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;

class SurviveWorldMongoCodecTest {
  private final MongoTemplate mongo = mock(MongoTemplate.class);

  @Test void movementReadsOnlyTheVersionUntilAnExternalWriteChangesIt() {
    var repository = new MongoSurviveWorldRepository(DomainMongoOperationsTestFactory.create(mongo));
    var kind = DomainDocumentKindRegistry.of(Map.of("survive_world", "application_runtime"))
        .require("survive_world", 1, SurviveSavedWorld.class);
    var codec = new DomainDocumentCodec<>(kind, mongo.getConverter());
    var initial = new SurviveSavedWorld("shared", 1L, 0, 0, 0, List.of(), List.of(), null, null, List.of(), List.of());
    var updated = new SurviveSavedWorld("shared", 2L, 1, 1, 0, List.of(), List.of(), null, null, List.of(), List.of());
    when(mongo.findOne(any(Query.class), eq(Document.class), eq("application_runtime")))
        .thenReturn(codec.encode(initial), codec.encode(updated), null);
    var storedVersion = new AtomicLong(1);
    when(mongo.exists(any(Query.class), eq(Document.class), eq("application_runtime")))
        .thenAnswer(call -> {
          Query query = call.getArgument(0);
          assertTrue(query.getQueryObject().toJson().contains("payload.version"));
          assertTrue(query.getQueryObject().toJson().contains("\"_id\": {\"kind\": \"survive_world\", \"legacyId\": \"shared\"}"));
          return query.getQueryObject().toJson().contains("\"payload.version\": " + storedVersion.get());
        });

    var cached = repository.loadForMovement().orElseThrow();
    for (int pulse = 0; pulse < 100; pulse++) {
      assertSame(cached, repository.loadForMovement().orElseThrow());
    }
    verify(mongo, times(1)).findOne(any(Query.class), eq(Document.class), eq("application_runtime"));
    storedVersion.set(2);
    assertEquals(updated, repository.loadForMovement().orElseThrow());
    assertEquals(updated, repository.loadForMovement().orElseThrow());
    storedVersion.set(-1);
    assertTrue(repository.loadForMovement().isEmpty());
  }

  @Test void approvedWorldRoundTripRetainsOwnershipCombatInventoriesAndCampWithoutChangingManifest() {
    var factory = DomainMongoOperationsTestFactory.create(mongo);
    assertEquals("application_runtime", factory.forType(SurviveSavedWorld.class).collectionName());
    var kind = DomainDocumentKindRegistry.of(Map.of("survive_world", "application_runtime"))
        .require("survive_world", 1, SurviveSavedWorld.class);
    var codec = new DomainDocumentCodec<>(kind, mongo.getConverter());
    var alice = new SurviveSavedWorld.Player("account-a", null, UUID.randomUUID().toString(),
        "Alice", Instant.parse("2026-10-06T12:00:00Z"), 8, 3, 11, 1, 2, 2, 1, 5,
        Status.COMBAT, 19, "A hog blocks your path.", null, null, null, null, null, null, null, null,
        Instant.parse("2026-10-06T12:00:03Z"), 150, true, "Previous fight ended.", 123_000, null, null,
        new SurviveStrike(19, Instant.parse("2026-10-06T12:00:00Z"), SurviveWeapon.CLUB, true));
    var guest = new SurviveSavedWorld.Player(null, UUID.randomUUID().toString(),
        UUID.randomUUID().toString(), "Guest", alice.lastSeen(), 10, 2, 10,
        0, 1, 0, 0, 0, Status.EXPLORING, 0, "At camp.", 1, 6, null, SurviveWeapon.CLUB, 5, true, null, false,
        null, null, null, null, null, null, null, null);
    var party = new SurviveParty(UUID.randomUUID().toString(),
        alice.survivorId(), guest.survivorId(), null);
    var fight = new SurviveFieldFight("meadow-hog", UUID.randomUUID().toString(), party.id(), SurviveEnemy.HOG,
        -34, 24, 0, 5, List.of(alice.survivorId()), null,
        new SurviveFieldFight.SkillOpening(alice.survivorId(), alice.lastSeen()));
    var saved = new SurviveSavedWorld("shared", 7L, 37, 1, 2, List.of("Shared journal"),
        List.of(alice, guest), 43L, 21L, List.of(party), List.of(fight));
    var envelope = codec.encode(saved);
    assertEquals(party.id(), envelope.get("payload", Document.class)
        .getList("parties", Document.class).getFirst().getString("_id"));
    assertEquals("survive_world", envelope.getString("_kind"));
    assertEquals(saved, codec.decode(envelope));
    assertTrue(DomainCollectionManifest.forKind("survive_world").isEmpty());
    assertThrows(IllegalArgumentException.class, () -> new SurviveSavedWorld("shared", 0L, 0, 0, 0,
        List.of(), List.of(alice, alice), null, null, List.of(), List.of()));
    assertThrows(IllegalArgumentException.class, () -> new SurviveSavedWorld("shared", 0L, 0, 1001, 0,
        List.of(), List.of(alice), null, null, List.of(), List.of()));
  }

  @Test void worldSavedBeforeTheClockLoadsWithDefaultsForEveryNewField() {
    DomainMongoOperationsTestFactory.create(mongo);
    var kind = DomainDocumentKindRegistry.of(Map.of("survive_world", "application_runtime"))
        .require("survive_world", 1, SurviveSavedWorld.class);
    var codec = new DomainDocumentCodec<>(kind, mongo.getConverter());
    var fighter = new SurviveSavedWorld.Player("account-a", null, UUID.randomUUID().toString(),
        "Alice", Instant.parse("2026-10-06T12:00:00Z"), 8, 3, 11, 1, 2, 2, 1, 5,
        Status.COMBAT, 19, "A hog blocks your path.", null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);
    var envelope = codec.encode(new SurviveSavedWorld("shared", 7L, 37, 1, 2, List.of(), List.of(fighter), null, null,
        List.of(), List.of()));
    var newFields = List.of("stone", "hunger", "enemy", "weapon", "weaponDurability", "guarding", "sleptAt", "struckFirst", "nextStrikeAt", "tacticalPoints", "defending", "combatOutcome", "activeFullnessMilliseconds", "waystationAssignment");
    var payload = envelope.get("payload", Document.class);
    payload.remove("settledBoundaries");
    payload.remove("activeRaidNight");
    payload.remove("parties");
    payload.remove("fieldFights");
    for (var storedPlayer : payload.getList("players", Document.class)) {
      newFields.forEach(storedPlayer::remove);
    }

    var restored = codec.decode(envelope);

    assertEquals(null, restored.settledBoundaries());
    assertEquals(List.of(), restored.parties());
    assertEquals(List.of(), restored.fieldFights());
    var restoredFighter = restored.players().getFirst();
    assertEquals("AVAILABLE", restoredFighter.waystationAssignment().name());
    assertEquals(List.of(), restoredFighter.packedWeapons());
    assertEquals(0, restoredFighter.stone());
    assertEquals(10, restoredFighter.hunger());
    assertEquals(0, restoredFighter.activeFullnessMilliseconds());
    assertEquals(SurviveEnemy.HOG, restoredFighter.enemy());
    assertEquals(null, restoredFighter.weapon());
    assertEquals(0, restoredFighter.weaponDurability());
    assertEquals(false, restoredFighter.guarding());
    assertEquals(null, restoredFighter.sleptAt());
    assertEquals(null, restoredFighter.nextStrikeAt());
    assertEquals(0, restoredFighter.tacticalPoints());
    assertEquals(false, restoredFighter.defending());
  }
}
