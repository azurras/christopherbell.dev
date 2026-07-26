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
    guard = new NotificationFanoutGuard(mongo, properties);
    org.mockito.Mockito.lenient().when(mongo.findAndModify(
        any(Query.class), any(UpdateDefinition.class), any(FindAndModifyOptions.class),
        eq(NotificationDeliveryGuard.class)))
        .thenReturn(NotificationDeliveryGuard.builder().id("claim").build());
  }

  @Test
  @DisplayName("A repeated event is rejected by its unique dedupe claim")
  void tryAcquire_whenDuplicate_returnsEmpty() {
    var identity = identity("recipient", "LIKE", "post-1");
    doThrow(new DuplicateKeyException("duplicate"))
        .when(mongo).findAndModify(
            any(Query.class), any(UpdateDefinition.class), any(FindAndModifyOptions.class),
            eq(NotificationDeliveryGuard.class));

    assertThat(guard.tryAcquire(identity, NOW)).isEmpty();
  }

  @Test
  @DisplayName("An expired claim is atomically replaceable before TTL cleanup")
  void tryAcquire_whenClaimExpired_replacesItAtomically() {
    when(mongo.findAndModify(
        any(Query.class), any(UpdateDefinition.class), any(FindAndModifyOptions.class),
        eq(NotificationRateLimit.class)))
        .thenReturn(NotificationRateLimit.builder().count(1L).build());

    assertThat(guard.tryAcquire(identity("recipient", "LIKE", "post-1"), NOW)).isPresent();

    var query = ArgumentCaptor.forClass(Query.class);
    verify(mongo).findAndModify(
        query.capture(), any(UpdateDefinition.class), any(FindAndModifyOptions.class),
        eq(NotificationDeliveryGuard.class));
    assertThat(query.getValue().getQueryObject().toString()).contains("expiresAt", "$lte");
  }

  @Test
  @DisplayName("The actor-recipient-type counter rejects events above the configured rate")
  void tryAcquire_whenRateIsSaturated_releasesDedupeClaim() {
    when(mongo.findAndModify(
        any(Query.class), any(UpdateDefinition.class), any(FindAndModifyOptions.class),
        eq(NotificationRateLimit.class)))
        .thenReturn(NotificationRateLimit.builder().count(3L).build());

    assertThat(guard.tryAcquire(identity("recipient", "LIKE", "post-1"), NOW)).isEmpty();

    verify(mongo).remove(any(Query.class), eq(NotificationDeliveryGuard.class));
  }

  @Test
  @DisplayName("Unrelated recipients and event types use distinct rate keys")
  void tryAcquire_scopesRateByActorRecipientAndType() {
    when(mongo.findAndModify(
        any(Query.class), any(UpdateDefinition.class), any(FindAndModifyOptions.class),
        eq(NotificationRateLimit.class)))
        .thenReturn(NotificationRateLimit.builder().count(1L).build());

    guard.tryAcquire(identity("recipient-a", "LIKE", "post-1"), NOW);
    guard.tryAcquire(identity("recipient-b", "LIKE", "post-1"), NOW);
    guard.tryAcquire(identity("recipient-a", "COMMENT", "post-1"), NOW);

    var queries = ArgumentCaptor.forClass(Query.class);
    verify(mongo, org.mockito.Mockito.times(3)).findAndModify(
        queries.capture(), any(UpdateDefinition.class), any(FindAndModifyOptions.class),
        eq(NotificationRateLimit.class));
    assertThat(queries.getAllValues())
        .extracting(query -> query.getQueryObject().getString("_id"))
        .doesNotHaveDuplicates();
  }

  private NotificationEventIdentity identity(String recipient, String type, String target) {
    return new NotificationEventIdentity(
        recipient, "actor", NotificationType.valueOf(type), target);
  }
}
