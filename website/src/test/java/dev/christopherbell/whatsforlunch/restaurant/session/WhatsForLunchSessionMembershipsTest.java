package dev.christopherbell.whatsforlunch.restaurant.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import dev.christopherbell.whatsforlunch.restaurant.model.WhatsForLunchSession;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.SimpleMongoClientDatabaseFactory;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

@ExtendWith(MockitoExtension.class)
class WhatsForLunchSessionMembershipsTest {
  private static final int MAX_PARTICIPANTS = 21;

  @Mock private MongoTemplate mongo;

  @Test
  void joinIfCapacityRemains_usesAtomicCapacityAndNonMemberPredicates() {
    var memberships = new WhatsForLunchSessionMemberships(mongo);
    when(mongo.findAndModify(any(Query.class), any(Update.class), any(FindAndModifyOptions.class),
        eq(WhatsForLunchSession.class)))
        .thenReturn(WhatsForLunchSession.builder().id("session-1").build());

    var outcome = memberships.joinIfCapacityRemains("session-1", "friend-id", "friend", MAX_PARTICIPANTS);

    assertEquals(SessionJoinOutcome.JOINED, outcome);
    var query = ArgumentCaptor.forClass(Query.class);
    var update = ArgumentCaptor.forClass(Update.class);
    var options = ArgumentCaptor.forClass(FindAndModifyOptions.class);
    verify(mongo).findAndModify(query.capture(), update.capture(), options.capture(), eq(WhatsForLunchSession.class));

    assertThat(query.getValue().getQueryObject().getString("_id")).isEqualTo("session-1");
    assertThat(query.getValue().getQueryObject()
        .get("participantAccountIds", Document.class).getString("$ne")).isEqualTo("friend-id");
    var capacityComparison = query.getValue().getQueryObject()
        .get("$expr", Document.class).getList("$lt", Object.class);
    assertThat(capacityComparison).hasSize(2);
    assertThat(capacityComparison.get(1)).isEqualTo(MAX_PARTICIPANTS);
    assertThat(capacityComparison.getFirst().toString()).contains("$size", "$ifNull", "participantAccountIds");
    assertThat(update.getValue().getUpdateObject()
        .get("$addToSet", Document.class).getString("participantAccountIds")).isEqualTo("friend-id");
    assertThat(update.getValue().getUpdateObject()
        .get("$set", Document.class).getString("participantUsernamesByAccountId.friend-id"))
        .isEqualTo("friend");
    assertThat(options.getValue().isReturnNew()).isTrue();
  }

  @Test
  @Timeout(value = 15, unit = TimeUnit.SECONDS)
  @EnabledIfEnvironmentVariable(named = "WFL_REAL_MONGO_TESTS", matches = "true")
  void joinIfCapacityRemains_withRealMongo_boundsConcurrentFinalSlotAndRetriesIdempotently()
      throws Exception {
    var databaseName = "task3_wfl_memberships_" + java.util.UUID.randomUUID().toString().replace("-", "");
    try (MongoClient client = MongoClients.create("mongodb://127.0.0.1:27017")) {
      var databaseFactory = new SimpleMongoClientDatabaseFactory(client, databaseName);
      var template = new MongoTemplate(databaseFactory);
      var memberships = new WhatsForLunchSessionMemberships(template);
      template.save(sessionWithTwentyParticipants());

      var executor = Executors.newFixedThreadPool(2);
      try {
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        var first = executor.submit(() -> joinAfterStart(memberships, ready, start, "final-a", "final-a"));
        var second = executor.submit(() -> joinAfterStart(memberships, ready, start, "final-b", "final-b"));
        assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
        start.countDown();

        var outcomes = List.of(first.get(5, TimeUnit.SECONDS), second.get(5, TimeUnit.SECONDS));
        assertThat(outcomes).containsExactlyInAnyOrder(SessionJoinOutcome.JOINED, SessionJoinOutcome.FULL);
        var stored = template.findById("session-1", WhatsForLunchSession.class);
        assertThat(stored).isNotNull();
        assertThat(stored.getParticipantAccountIds()).hasSize(MAX_PARTICIPANTS);
        var finalMember = stored.getParticipantAccountIds().contains("final-a") ? "final-a" : "final-b";
        assertThat(memberships.joinIfCapacityRemains("session-1", finalMember, finalMember, MAX_PARTICIPANTS))
            .isEqualTo(SessionJoinOutcome.ALREADY_MEMBER);
        assertThat(template.findById("session-1", WhatsForLunchSession.class).getParticipantAccountIds())
            .hasSize(MAX_PARTICIPANTS);
        assertThat(memberships.joinIfCapacityRemains("session-1", "member-22", "member-22", MAX_PARTICIPANTS))
            .isEqualTo(SessionJoinOutcome.FULL);
      } finally {
        executor.shutdownNow();
      }
    } finally {
      try (MongoClient cleanupClient = MongoClients.create("mongodb://127.0.0.1:27017")) {
        cleanupClient.getDatabase(databaseName).drop();
      }
    }
  }

  private static SessionJoinOutcome joinAfterStart(
      WhatsForLunchSessionMemberships memberships,
      CountDownLatch ready,
      CountDownLatch start,
      String accountId,
      String username
  ) throws InterruptedException {
    ready.countDown();
    if (!start.await(5, TimeUnit.SECONDS)) {
      throw new IllegalStateException("Concurrent join test did not start.");
    }
    return memberships.joinIfCapacityRemains("session-1", accountId, username, MAX_PARTICIPANTS);
  }

  private static WhatsForLunchSession sessionWithTwentyParticipants() {
    var participantIds = java.util.stream.IntStream.rangeClosed(1, 20)
        .mapToObj(index -> "member-" + index)
        .toList();
    var usernames = new LinkedHashMap<String, String>();
    participantIds.forEach(id -> usernames.put(id, id));
    return WhatsForLunchSession.builder()
        .id("session-1")
        .createdByAccountId("member-1")
        .createdByUsername("member-1")
        .participantAccountIds(participantIds)
        .participantUsernamesByAccountId(usernames)
        .restaurantIds(List.of("restaurant-1", "restaurant-2", "restaurant-3"))
        .votesByAccountId(new LinkedHashMap<>())
        .build();
  }
}
