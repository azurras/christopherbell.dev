package dev.christopherbell.configuration.mongo.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.christopherbell.whatsforlunch.restaurant.model.RestaurantVoteValue;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.UpdateDefinition;

@ExtendWith(MockitoExtension.class)
class V013ConvertRestaurantRatingsToVotesTest {
  private static final String COLLECTION = "whatsforlunch_ratings";

  @Mock private MongoTemplate mongo;
  @Captor private ArgumentCaptor<UpdateDefinition> update;
  @Captor private ArgumentCaptor<Query> queries;

  @Test
  void convertsOneAndTwoDownAndThreeThroughFiveUp() {
    assertThat(V013ConvertRestaurantRatingsToVotes.targetVote(
        new Document("_id", "one").append("rating", 1))).isEqualTo(RestaurantVoteValue.DOWN);
    assertThat(V013ConvertRestaurantRatingsToVotes.targetVote(
        new Document("_id", "two").append("rating", 2))).isEqualTo(RestaurantVoteValue.DOWN);
    assertThat(V013ConvertRestaurantRatingsToVotes.targetVote(
        new Document("_id", "three").append("rating", 3))).isEqualTo(RestaurantVoteValue.UP);
    assertThat(V013ConvertRestaurantRatingsToVotes.targetVote(
        new Document("_id", "four").append("rating", 4))).isEqualTo(RestaurantVoteValue.UP);
    assertThat(V013ConvertRestaurantRatingsToVotes.targetVote(
        new Document("_id", "five").append("rating", 5))).isEqualTo(RestaurantVoteValue.UP);
  }

  @Test
  void failsPreflightBeforeTheFirstWriteWhenAnyDocumentIsMalformed() {
    returnsBatches(List.of(List.of(
        new Document("_id", "valid").append("rating", 5),
        new Document("_id", "invalid").append("rating", 6))));

    assertThatThrownBy(() -> new V013ConvertRestaurantRatingsToVotes().apply(mongo))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("invalid");

    verify(mongo, never()).updateFirst(any(Query.class), any(UpdateDefinition.class), eq(COLLECTION));
  }

  @Test
  void acceptsRetrySafeConvertedDocumentsAndRejectsContradictions() {
    assertThatCode(() -> V013ConvertRestaurantRatingsToVotes.validateDocument(
        new Document("_id", "converted").append("vote", "UP"))).doesNotThrowAnyException();
    assertThatThrownBy(() -> V013ConvertRestaurantRatingsToVotes.validateDocument(
        new Document("_id", "conflict").append("rating", 2).append("vote", "UP")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("conflict");
  }

  @Test
  void convertsOnlyVoteFieldsAndLeavesDocumentIdentityAndTimestampsUntouched() {
    var legacy = new Document("_id", "rating-1")
        .append("restaurantId", "restaurant-1")
        .append("accountId", "account-1")
        .append("rating", 4)
        .append("createdOn", Instant.parse("2026-08-03T12:00:00Z"))
        .append("lastUpdatedOn", Instant.parse("2026-08-03T13:00:00Z"));
    returnsBatches(List.of(List.of(legacy), List.of(), List.of(legacy), List.of()));

    new V013ConvertRestaurantRatingsToVotes().apply(mongo);

    verify(mongo).updateFirst(any(Query.class), update.capture(), eq(COLLECTION));
    assertThat(update.getValue().getUpdateObject().toString())
        .contains("vote=UP", "type=restaurant_vote", "$unset", "rating")
        .doesNotContain("restaurantId", "accountId", "createdOn", "lastUpdatedOn");
  }

  @Test
  void pagesBothReadOnlyAndConversionPassesByStableId() {
    returnsBatches(List.of(
        List.of(new Document("_id", "001").append("rating", 1)),
        List.of(new Document("_id", "002").append("rating", 5)),
        List.of(),
        List.of(new Document("_id", "001").append("rating", 1)),
        List.of(new Document("_id", "002").append("rating", 5)),
        List.of()));

    new V013ConvertRestaurantRatingsToVotes().apply(mongo);

    verify(mongo, times(6)).find(queries.capture(), eq(Document.class), eq(COLLECTION));
    assertThat(queries.getAllValues())
        .allSatisfy(query -> assertThat(query.getLimit()).isEqualTo(250));
    assertThat(stableIdAfter(queries.getAllValues().get(1))).isEqualTo("001");
    assertThat(stableIdAfter(queries.getAllValues().get(2))).isEqualTo("002");
    assertThat(stableIdAfter(queries.getAllValues().get(4))).isEqualTo("001");
    assertThat(stableIdAfter(queries.getAllValues().get(5))).isEqualTo("002");
  }

  private static String stableIdAfter(Query query) {
    var idCriteria = (Document) query.getQueryObject().get("_id");
    return idCriteria.getString("$gt");
  }

  private void returnsBatches(List<List<Document>> batches) {
    var nextBatch = new AtomicInteger();
    when(mongo.find(any(Query.class), eq(Document.class), eq(COLLECTION)))
        .thenAnswer(invocation -> batches.get(nextBatch.getAndIncrement()));
  }
}
