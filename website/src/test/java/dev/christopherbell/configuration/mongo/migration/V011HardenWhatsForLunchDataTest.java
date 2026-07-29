package dev.christopherbell.configuration.mongo.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.IndexOperations;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.UpdateDefinition;

@ExtendWith(MockitoExtension.class)
class V011HardenWhatsForLunchDataTest {
  @Mock private MongoTemplate mongo;
  @Mock private IndexOperations sessionIndexes;
  @Mock private IndexOperations restaurantIndexes;

  @Test
  void backfillsBoundedSessionAndIndexedSafeRestaurantState() {
    var created = Instant.parse("2026-07-29T12:00:00Z");
    when(mongo.indexOps("whatsforlunch_sessions")).thenReturn(sessionIndexes);
    when(mongo.indexOps("whatsforlunch")).thenReturn(restaurantIndexes);
    when(mongo.find(any(Query.class), eq(Document.class), eq("whatsforlunch_sessions")))
        .thenReturn(
            List.of(new Document("_id", "session-1").append("createdOn", created)),
            List.of());
    when(mongo.find(any(Query.class), eq(Document.class), eq("whatsforlunch")))
        .thenReturn(
            List.of(new Document("_id", "restaurant-1")
                .append("name", "  Example   Cafe ")
                .append("website", "javascript:alert(1)")
                .append("address", new Document("city", " Austin ").append("state", " TX "))),
            List.of());

    new V011HardenWhatsForLunchData().apply(mongo);

    var sessionUpdate = ArgumentCaptor.forClass(UpdateDefinition.class);
    verify(mongo).updateFirst(any(Query.class), sessionUpdate.capture(),
        eq("whatsforlunch_sessions"));
    assertThat(sessionUpdate.getValue().getUpdateObject().toString())
        .contains("revision", "activeUntil", "deleteOn", "restaurantResetCount",
            "restaurantResetAudit");
    var restaurantUpdate = ArgumentCaptor.forClass(UpdateDefinition.class);
    verify(mongo).updateFirst(any(Query.class), restaurantUpdate.capture(), eq("whatsforlunch"));
    assertThat(restaurantUpdate.getValue().getUpdateObject().toString())
        .contains("dedupeKey=example cafe", "searchCity=austin", "searchState=tx",
            "$unset", "website");
    verify(sessionIndexes, times(2)).createIndex(any());
    verify(restaurantIndexes, times(4)).createIndex(any());
  }
}
