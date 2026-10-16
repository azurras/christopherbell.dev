package dev.christopherbell.configuration.mongo.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import dev.christopherbell.survive.model.SurviveSavedWorld;
import dev.christopherbell.survive.model.SurviveEnemy;
import dev.christopherbell.survive.model.SurviveSnapshot.Status;
import dev.christopherbell.survive.model.SurviveWeapon;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;

class SurviveWorldMongoCodecTest {
  private final MongoTemplate mongo = mock(MongoTemplate.class);

  @Test void approvedWorldRoundTripRetainsOwnershipCombatInventoriesAndCampWithoutChangingManifest() {
    var factory = DomainMongoOperationsTestFactory.create(mongo);
    assertEquals("application_runtime", factory.forType(SurviveSavedWorld.class).collectionName());
    var kind = DomainDocumentKindRegistry.of(Map.of("survive_world", "application_runtime"))
        .require("survive_world", 1, SurviveSavedWorld.class);
    var codec = new DomainDocumentCodec<>(kind, mongo.getConverter());
    var alice = new SurviveSavedWorld.Player("account-a", null, UUID.randomUUID().toString(),
        "Alice", Instant.parse("2026-10-06T12:00:00Z"), 8, 3, 11, 1, 2, 2, 1, 5,
        Status.COMBAT, 19, "A hog blocks your path.", null, null, null, null, null, null, null, null);
    var guest = new SurviveSavedWorld.Player(null, UUID.randomUUID().toString(),
        UUID.randomUUID().toString(), "Guest", alice.lastSeen(), 10, 2, 10,
        0, 1, 0, 0, 0, Status.EXPLORING, 0, "At camp.", 1, 6, null, SurviveWeapon.CLUB, 5, true, null, false);
    var saved = new SurviveSavedWorld("shared", 7L, 37, 1, 2, List.of("Shared journal"), List.of(alice, guest), 42L);
    var envelope = codec.encode(saved);
    assertEquals("survive_world", envelope.getString("_kind"));
    assertEquals(saved, codec.decode(envelope));
    assertTrue(DomainCollectionManifest.forKind("survive_world").isEmpty());
    assertThrows(IllegalArgumentException.class, () -> new SurviveSavedWorld("shared", 0L, 0, 0, 0,
        List.of(), List.of(alice, alice), null));
    assertThrows(IllegalArgumentException.class, () -> new SurviveSavedWorld("shared", 0L, 0, 1001, 0,
        List.of(), List.of(alice), null));
  }

  @Test void worldSavedBeforeTheClockLoadsWithDefaultsForEveryNewField() {
    DomainMongoOperationsTestFactory.create(mongo);
    var kind = DomainDocumentKindRegistry.of(Map.of("survive_world", "application_runtime"))
        .require("survive_world", 1, SurviveSavedWorld.class);
    var codec = new DomainDocumentCodec<>(kind, mongo.getConverter());
    var fighter = new SurviveSavedWorld.Player("account-a", null, UUID.randomUUID().toString(),
        "Alice", Instant.parse("2026-10-06T12:00:00Z"), 8, 3, 11, 1, 2, 2, 1, 5,
        Status.COMBAT, 19, "A hog blocks your path.", null, null, null, null, null, null, null, null);
    var envelope = codec.encode(new SurviveSavedWorld("shared", 7L, 37, 1, 2, List.of(), List.of(fighter), null));
    var newFields = List.of("stone", "hunger", "enemy", "weapon", "weaponDurability", "guarding", "sleptAt", "struckFirst");
    var payload = envelope.get("payload", Document.class);
    payload.remove("settledBoundaries");
    for (var storedPlayer : payload.getList("players", Document.class)) {
      newFields.forEach(storedPlayer::remove);
    }

    var restored = codec.decode(envelope);

    assertEquals(null, restored.settledBoundaries());
    var restoredFighter = restored.players().getFirst();
    assertEquals(0, restoredFighter.stone());
    assertEquals(10, restoredFighter.hunger());
    assertEquals(SurviveEnemy.HOG, restoredFighter.enemy());
    assertEquals(null, restoredFighter.weapon());
    assertEquals(0, restoredFighter.weaponDurability());
    assertEquals(false, restoredFighter.guarding());
    assertEquals(null, restoredFighter.sleptAt());
  }
}
