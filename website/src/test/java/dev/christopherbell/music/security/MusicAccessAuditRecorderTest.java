package dev.christopherbell.music.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

class MusicAccessAuditRecorderTest {

  @Test
  void deniedAttemptsAggregateByIdentityAndHourWithThirtyDayExpiry() {
    var mongo = mock(MongoTemplate.class);
    var clock = Clock.fixed(Instant.parse("2026-07-28T12:34:56Z"), ZoneOffset.UTC);
    var recorder = new MusicAccessAuditRecorder(mongo, clock);
    var queries = ArgumentCaptor.forClass(Query.class);
    var updates = ArgumentCaptor.forClass(Update.class);
    when(mongo.findAndModify(
        queries.capture(), updates.capture(),
        org.mockito.ArgumentMatchers.any(FindAndModifyOptions.class),
        eq(MusicAccessAttempt.class))).thenReturn(mock(MusicAccessAttempt.class));

    recorder.deniedIp("203.0.113.7", "SIGN_IN_REQUIRED");
    recorder.deniedIp("203.0.113.7", "SIGN_IN_REQUIRED");

    verify(mongo, times(2)).findAndModify(
        org.mockito.ArgumentMatchers.any(Query.class),
        org.mockito.ArgumentMatchers.any(Update.class),
        org.mockito.ArgumentMatchers.any(FindAndModifyOptions.class),
        eq(MusicAccessAttempt.class));
    assertThat(queries.getAllValues().get(0).getQueryObject().getString("_id"))
        .isEqualTo(queries.getAllValues().get(1).getQueryObject().getString("_id"));
    Document update = updates.getAllValues().getFirst().getUpdateObject();
    assertThat(update.get("$inc", Document.class).get("count"))
        .isInstanceOf(Number.class)
        .extracting(value -> ((Number) value).longValue())
        .isEqualTo(1L);
    assertThat(update.get("$set", Document.class).get("expiresAt"))
        .isEqualTo(Instant.parse("2026-08-27T12:34:56Z"));
    assertThat(update.toString()).doesNotContain("Authorization", "Bearer", "cookie");
  }
}
