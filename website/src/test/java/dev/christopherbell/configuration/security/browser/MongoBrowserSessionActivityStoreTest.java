package dev.christopherbell.configuration.security.browser;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Optional;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

class MongoBrowserSessionActivityStoreTest {
  private static final Instant OBSERVED = Instant.parse("2026-07-29T12:00:00Z");
  private static final Instant NOW = OBSERVED.plusSeconds(300);

  @Test
  void touchUpdatesOnlyActivityFieldsWhenTheObservedLiveSessionStillMatches() {
    var mongo = mock(MongoTemplate.class);
    var expected = BrowserSession.builder().id("session-1").build();
    when(mongo.findAndModify(
        any(Query.class), any(Update.class), any(FindAndModifyOptions.class), eq(BrowserSession.class)))
        .thenReturn(expected);
    var store = new MongoBrowserSessionActivityStore(mongo);
    Instant idleExpiresOn = NOW.plusSeconds(600);

    var result = store.touch("session-1", OBSERVED, NOW, idleExpiresOn);

    assertThat(result).containsSame(expected);
    var query = ArgumentCaptor.forClass(Query.class);
    var update = ArgumentCaptor.forClass(Update.class);
    verify(mongo).findAndModify(
        query.capture(), update.capture(), any(FindAndModifyOptions.class), eq(BrowserSession.class));
    assertThat(query.getValue().getQueryObject()).containsEntry("lastSeenOn", OBSERVED);
    assertLiveSessionPredicate(query.getValue(), idleExpiresOn);
    assertThat(update.getValue().getUpdateObject())
        .isEqualTo(new Document("$set", new Document("lastSeenOn", NOW)
            .append("idleExpiresOn", idleExpiresOn)));
  }

  @Test
  void rotationUpdatesCredentialsAndActivityOnlyWhenTheObservedLiveSessionStillMatches() {
    var mongo = mock(MongoTemplate.class);
    var expected = BrowserSession.builder().id("session-1").build();
    when(mongo.findAndModify(
        any(Query.class), any(Update.class), any(FindAndModifyOptions.class), eq(BrowserSession.class)))
        .thenReturn(expected);
    var store = new MongoBrowserSessionActivityStore(mongo);
    Instant previousTokenExpiresOn = NOW.plusSeconds(120);
    Instant idleExpiresOn = NOW.plusSeconds(600);

    Optional<BrowserSession> result = store.rotate(
        "session-1", "old-token-hash", OBSERVED, "next-token-hash", NOW,
        previousTokenExpiresOn, idleExpiresOn);

    assertThat(result).containsSame(expected);
    var query = ArgumentCaptor.forClass(Query.class);
    var update = ArgumentCaptor.forClass(Update.class);
    verify(mongo).findAndModify(
        query.capture(), update.capture(), any(FindAndModifyOptions.class), eq(BrowserSession.class));
    assertThat(query.getValue().getQueryObject())
        .containsEntry("tokenHash", "old-token-hash")
        .containsEntry("rotatedOn", OBSERVED);
    assertLiveSessionPredicate(query.getValue(), idleExpiresOn);
    assertThat(update.getValue().getUpdateObject())
        .isEqualTo(new Document("$set", new Document("previousTokenHash", "old-token-hash")
            .append("previousTokenExpiresOn", previousTokenExpiresOn)
            .append("tokenHash", "next-token-hash")
            .append("rotatedOn", NOW)
            .append("lastSeenOn", NOW)
            .append("idleExpiresOn", idleExpiresOn)));
  }

  private static void assertLiveSessionPredicate(Query query, Instant idleExpiresOn) {
    var clauses = query.getQueryObject().getList("$and", Document.class);
    assertThat(clauses)
        .contains(new Document("_id", "session-1"))
        .contains(new Document("idleExpiresOn", new Document("$gt", NOW)))
        .contains(new Document("absoluteExpiresOn", new Document("$gte", idleExpiresOn)));
  }
}
