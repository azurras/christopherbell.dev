package dev.christopherbell.whatsforlunch.restaurant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.christopherbell.configuration.mongo.domain.DomainMongoOperationsFactory;
import dev.christopherbell.configuration.mongo.domain.KindScopedMongoOperations;
import dev.christopherbell.whatsforlunch.restaurant.model.WhatsForLunchSession;
import dev.christopherbell.whatsforlunch.restaurant.model.WhatsForLunchSessionRestaurantsRequest;
import dev.christopherbell.whatsforlunch.restaurant.session.WhatsForLunchSessionMutationStore;
import dev.christopherbell.whatsforlunch.restaurant.session.WhatsForLunchSessionRepository;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

@ExtendWith(MockitoExtension.class)
class WhatsForLunchSessionMutationStoreTest {
  private static final Instant NOW = Instant.parse("2026-07-29T12:00:00Z");

  @Mock private DomainMongoOperationsFactory factory;
  @Mock private KindScopedMongoOperations<WhatsForLunchSession> sessions;
  @Mock private WhatsForLunchSessionRepository repository;
  private WhatsForLunchSessionMutationStore store;

  @BeforeEach
  void setUp() {
    when(factory.forType(WhatsForLunchSession.class)).thenReturn(sessions);
    store = new WhatsForLunchSessionMutationStore(factory, repository);
  }

  @Test
  void joinUsesOneKindScopedAtomicCappedUpdate() {
    when(sessions.findAndUpdate(any(Query.class), any(Update.class)))
        .thenReturn(Optional.of(activeSession()));

    var result = store.join("session-1", "friend-id", "friend", NOW, 20);

    assertThat(result.status()).isEqualTo(WhatsForLunchSessionMutationStore.Status.UPDATED);
    var query = ArgumentCaptor.forClass(Query.class);
    var update = ArgumentCaptor.forClass(Update.class);
    verify(sessions).findAndUpdate(query.capture(), update.capture());
    assertThat(query.getValue().getQueryObject().toString())
        .contains("id=session-1", "activeUntil", "participantAccountIds.19", "friend-id");
    assertThat(update.getValue().getUpdateObject().toString())
        .contains("$addToSet", "participantUsernamesByAccountId.friend-id", "revision");
  }

  @Test
  void voteTargetsOnlyTheCallersMapEntryAndAdvancesRevision() {
    when(sessions.findAndUpdate(any(Query.class), any(Update.class)))
        .thenReturn(Optional.of(activeSession()));

    var result = store.vote("session-1", "owner-id", "restaurant-2", NOW);

    assertThat(result.status()).isEqualTo(WhatsForLunchSessionMutationStore.Status.UPDATED);
    var update = ArgumentCaptor.forClass(Update.class);
    verify(sessions).findAndUpdate(any(Query.class), update.capture());
    assertThat(update.getValue().getUpdateObject().toString())
        .contains("votesByAccountId.owner-id", "restaurant-2", "revision")
        .doesNotContain("participantAccountIds=");
  }

  @Test
  void resetRequiresHostAndExpectedRevisionAndRecordsBoundedAudit() {
    var updated = activeSession();
    updated.setRevision(8);
    when(sessions.findAndUpdate(any(Query.class), any(Update.class)))
        .thenReturn(Optional.of(updated));

    var result = store.resetRestaurants(
        "session-1",
        "owner-id",
        "owner",
        new WhatsForLunchSessionRestaurantsRequest(
            List.of("restaurant-4", "restaurant-5", "restaurant-6"), 7),
        NOW);

    assertThat(result.status()).isEqualTo(WhatsForLunchSessionMutationStore.Status.UPDATED);
    var query = ArgumentCaptor.forClass(Query.class);
    var update = ArgumentCaptor.forClass(Update.class);
    verify(sessions).findAndUpdate(query.capture(), update.capture());
    assertThat(query.getValue().getQueryObject().toString())
        .contains("createdByAccountId=owner-id", "revision=7", "activeUntil");
    assertThat(update.getValue().getUpdateObject().toString())
        .contains("restaurantIds", "votesByAccountId", "restaurantResetAudit",
            "restaurantResetCount", "$slice", "revision");
  }

  @Test
  void failedAtomicJoinUsesThePortForStableClassification() {
    when(sessions.findAndUpdate(any(Query.class), any(Update.class))).thenReturn(Optional.empty());
    when(repository.findById("session-1")).thenReturn(Optional.of(activeSession()));

    var result = store.join("session-1", "owner-id", "owner", NOW, 20);

    assertThat(result.status()).isEqualTo(WhatsForLunchSessionMutationStore.Status.UNCHANGED);
  }

  private static WhatsForLunchSession activeSession() {
    return WhatsForLunchSession.builder()
        .id("session-1")
        .createdByAccountId("owner-id")
        .createdByUsername("owner")
        .participantAccountIds(List.of("owner-id"))
        .participantUsernamesByAccountId(Map.of("owner-id", "owner"))
        .restaurantIds(List.of("restaurant-1", "restaurant-2", "restaurant-3"))
        .votesByAccountId(Map.of())
        .revision(7)
        .activeUntil(NOW.plusSeconds(60))
        .build();
  }
}
