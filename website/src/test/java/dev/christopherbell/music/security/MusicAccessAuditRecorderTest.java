package dev.christopherbell.music.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.christopherbell.configuration.mongo.domain.DomainMongoOperationsFactory;
import dev.christopherbell.configuration.mongo.domain.KindScopedMongoOperations;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

class MusicAccessAuditRecorderTest {
  @Test
  @SuppressWarnings("unchecked")
  void deniedAttemptsAggregateByIdentityAndHourWithThirtyDayExpiry() {
    var factory = mock(DomainMongoOperationsFactory.class);
    var attempts = (KindScopedMongoOperations<MusicAccessAttempt>) mock(KindScopedMongoOperations.class);
    when(factory.forType(MusicAccessAttempt.class)).thenReturn(attempts);
    var clock = Clock.fixed(Instant.parse("2026-07-28T12:34:56Z"), ZoneOffset.UTC);
    var recorder = new MusicAccessAuditRecorder(factory, clock);
    var inserts = ArgumentCaptor.forClass(MusicAccessAttempt.class);
    var updates = ArgumentCaptor.forClass(Update.class);
    var aggregated = mock(MusicAccessAttempt.class);
    when(attempts.findAndUpdate(any(Query.class), updates.capture()))
        .thenReturn(Optional.empty(), Optional.of(aggregated));
    when(attempts.insert(inserts.capture())).thenAnswer(call -> call.getArgument(0));

    var inserted = recorder.deniedIp("203.0.113.7", "SIGN_IN_REQUIRED");
    var updated = recorder.deniedIp("203.0.113.7", "SIGN_IN_REQUIRED");

    verify(attempts, times(2)).findAndUpdate(any(Query.class), any(Update.class));
    verify(attempts).insert(any(MusicAccessAttempt.class));
    assertThat(inserted).isEqualTo(inserts.getValue());
    assertThat(updated).isSameAs(aggregated);
    assertThat(inserts.getValue().count()).isEqualTo(1);
    Document update = updates.getAllValues().getFirst().getUpdateObject();
    assertThat(update.get("$inc", Document.class).get("count"))
        .isInstanceOf(Number.class)
        .extracting(value -> ((Number) value).longValue())
        .isEqualTo(1L);
    assertThat(update.get("$set", Document.class).get("expiresAt"))
        .isEqualTo(Instant.parse("2026-08-27T12:34:56Z"));
  }

  @Test
  @SuppressWarnings("unchecked")
  void insertRaceFallsBackToTheAtomicExistingRecordUpdate() {
    var factory = mock(DomainMongoOperationsFactory.class);
    var attempts = (KindScopedMongoOperations<MusicAccessAttempt>) mock(KindScopedMongoOperations.class);
    when(factory.forType(MusicAccessAttempt.class)).thenReturn(attempts);
    var recorder = new MusicAccessAuditRecorder(
        factory,
        Clock.fixed(Instant.parse("2026-07-28T12:34:56Z"), ZoneOffset.UTC));
    var winner = mock(MusicAccessAttempt.class);
    when(attempts.findAndUpdate(any(Query.class), any(Update.class)))
        .thenReturn(Optional.empty(), Optional.of(winner));
    when(attempts.insert(any(MusicAccessAttempt.class)))
        .thenThrow(new DuplicateKeyException("concurrent insert"));

    assertThat(recorder.deniedIp("203.0.113.7", "SIGN_IN_REQUIRED")).isSameAs(winner);

    verify(attempts, times(2)).findAndUpdate(any(Query.class), any(Update.class));
    verify(attempts).insert(any(MusicAccessAttempt.class));
  }
}
