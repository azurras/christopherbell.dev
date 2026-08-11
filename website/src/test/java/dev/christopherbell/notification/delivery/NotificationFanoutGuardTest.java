package dev.christopherbell.notification.delivery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.christopherbell.notification.model.NotificationType;
import java.time.Duration;
import java.time.Instant;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.UpdateDefinition;

@ExtendWith(MockitoExtension.class)
class NotificationFanoutGuardTest {
  private static final Instant NOW = Instant.parse("2026-07-26T12:00:00Z");
  @Mock private MongoTemplate mongo;
  private NotificationFanoutGuard guard;

  @BeforeEach
  void setUp() {
    var properties = new NotificationDeliveryProperties(Duration.ofMinutes(5), Duration.ofMinutes(1), 2);
    guard = new NotificationFanoutGuard(
        dev.christopherbell.configuration.mongo.domain.DomainMongoOperationsTestFactory.create(mongo),
        properties);
    org.mockito.Mockito.lenient().when(mongo.insert(any(Document.class), eq("communications")))
        .thenAnswer(invocation -> invocation.getArgument(0));
  }

  @Test
  @DisplayName("A repeated event is rejected by its unique dedupe claim")
  void tryAcquire_whenDuplicate_returnsEmpty() {
    var identity = identity("recipient", "LIKE", "post-1");
    doThrow(new DuplicateKeyException("duplicate"))
        .when(mongo).insert(any(Document.class), eq("communications"));

    assertThat(guard.tryAcquire(identity, NOW)).isEmpty();
  }

  @Test
  @DisplayName("An expired claim is atomically replaceable before TTL cleanup")
  void tryAcquire_whenClaimExpired_replacesItAtomically() {
    doThrow(new DuplicateKeyException("expired claim"))
        .when(mongo).insert(any(Document.class), eq("communications"));
    stubAtomicResults(1L, true);

    assertThat(guard.tryAcquire(identity("recipient", "LIKE", "post-1"), NOW)).isPresent();

    var query = ArgumentCaptor.forClass(Query.class);
    verify(mongo, org.mockito.Mockito.times(2)).findAndModify(
        query.capture(), any(UpdateDefinition.class), any(FindAndModifyOptions.class),
        eq(Document.class), eq("communications"));
    assertThat(query.getAllValues().stream()
        .map(value -> value.getQueryObject().toString()).toList().toString())
        .contains("_kind=notification_delivery_guard", "payload.expiresAt", "$lte");
  }

  @Test
  @DisplayName("The actor-recipient-type counter rejects events above the configured rate")
  void tryAcquire_whenRateIsSaturated_releasesDedupeClaim() {
    stubAtomicResults(3L, false);

    assertThat(guard.tryAcquire(identity("recipient", "LIKE", "post-1"), NOW)).isEmpty();

    verify(mongo).updateFirst(any(Query.class), any(UpdateDefinition.class),
        eq(Document.class), eq("communications"));
    verify(mongo).remove(any(Query.class), eq(Document.class), eq("communications"));
  }

  @Test
  @DisplayName("Unrelated recipients and event types use distinct rate keys")
  void tryAcquire_scopesRateByActorRecipientAndType() {
    stubAtomicResults(1L, false);

    guard.tryAcquire(identity("recipient-a", "LIKE", "post-1"), NOW);
    guard.tryAcquire(identity("recipient-b", "LIKE", "post-1"), NOW);
    guard.tryAcquire(identity("recipient-a", "COMMENT", "post-1"), NOW);

    var queries = ArgumentCaptor.forClass(Query.class);
    verify(mongo, org.mockito.Mockito.times(3)).findAndModify(
        queries.capture(), any(UpdateDefinition.class), any(FindAndModifyOptions.class),
        eq(Document.class), eq("communications"));
    assertThat(queries.getAllValues())
        .extracting(query -> query.getQueryObject().toString())
        .doesNotHaveDuplicates();
  }

  @Test
  @DisplayName("Releasing a failed delivery refunds its rate reservation and dedupe claim")
  void release_refundsRateReservationAndDedupeClaim() {
    guard.release(new NotificationDeliveryPermit("claim-id", "rate-id"));

    var rateQuery = ArgumentCaptor.forClass(Query.class);
    var rateUpdate = ArgumentCaptor.forClass(UpdateDefinition.class);
    verify(mongo).updateFirst(
        rateQuery.capture(), rateUpdate.capture(), eq(Document.class), eq("communications"));
    assertThat(rateQuery.getValue().getQueryObject().toString())
        .contains("rate-id", "count", "$gt");
    assertThat(rateUpdate.getValue().getUpdateObject().toString())
        .contains("$inc", "-1");
    verify(mongo).remove(any(Query.class), eq(Document.class), eq("communications"));
  }

  private void stubAtomicResults(long count, boolean includeClaim) {
    var claim = NotificationDeliveryGuard.builder().id("claim").expiresAt(NOW.plusSeconds(60)).build();
    var rate = NotificationRateLimit.builder().id("rate").count(count).build();
    var claimEnvelope =
        dev.christopherbell.configuration.mongo.domain.DomainMongoOperationsTestFactory
            .envelope(mongo, claim);
    var rateEnvelope =
        dev.christopherbell.configuration.mongo.domain.DomainMongoOperationsTestFactory
            .envelope(mongo, rate);
    when(mongo.findAndModify(
        any(Query.class), any(UpdateDefinition.class), any(FindAndModifyOptions.class),
        eq(Document.class), eq("communications")))
        .thenAnswer(invocation -> {
          var query = ((Query) invocation.getArgument(0)).getQueryObject().toString();
          return query.contains("_kind=notification_delivery_guard")
              ? (includeClaim ? claimEnvelope : null)
              : rateEnvelope;
        });
  }

  private NotificationEventIdentity identity(String recipient, String type, String target) {
    return new NotificationEventIdentity(
        recipient, "actor", NotificationType.valueOf(type), target);
  }
}
