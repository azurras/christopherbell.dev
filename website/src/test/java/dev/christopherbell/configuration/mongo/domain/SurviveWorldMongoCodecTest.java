package dev.christopherbell.configuration.mongo.domain;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import dev.christopherbell.survive.model.SurviveSavedWorld;
import dev.christopherbell.survive.model.SurviveSnapshot.Status;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
        Status.COMBAT, 19, "A hog blocks your path.");
    var guest = new SurviveSavedWorld.Player(null, UUID.randomUUID().toString(),
        UUID.randomUUID().toString(), "Guest", alice.lastSeen(), 10, 2, 10,
        0, 1, 0, 0, 0, Status.EXPLORING, 0, "At camp.");
    var saved = new SurviveSavedWorld("shared", 7L, 37, 1, 2, List.of("Shared journal"), List.of(alice, guest));
    var envelope = codec.encode(saved);
    assertEquals("survive_world", envelope.getString("_kind"));
    assertEquals(saved, codec.decode(envelope));
    assertTrue(DomainCollectionManifest.forKind("survive_world").isEmpty());
    assertThrows(IllegalArgumentException.class, () -> new SurviveSavedWorld("shared", 0L, 0, 0, 0,
        List.of(), List.of(alice, alice)));
    assertThrows(IllegalArgumentException.class, () -> new SurviveSavedWorld("shared", 0L, 0, 1001, 0,
        List.of(), List.of(alice)));
  }
}
