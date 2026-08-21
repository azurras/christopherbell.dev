package dev.christopherbell.notification.preference;

import static org.assertj.core.api.Assertions.assertThat;

import dev.christopherbell.account.model.Account;
import dev.christopherbell.libs.pagination.StableCursorCodec;
import dev.christopherbell.notification.NotificationRepository;
import dev.christopherbell.notification.inbox.NotificationQueryPort;
import dev.christopherbell.notification.model.Notification;
import dev.christopherbell.notification.model.NotificationType;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Shared notification query/preference behavior for real MongoDB and PostgreSQL. */
interface NotificationReadModelParityContract {
  String OWNER = "notification-query-owner";
  String OTHER = "notification-query-other";
  Instant NOW = Instant.parse("2026-08-13T19:00:00Z");

  NotificationRepository notifications();

  NotificationQueryPort queries();

  NotificationPreferenceRepository preferences();

  StableCursorCodec cursors();

  void ensureAccount(Account account);

  @BeforeEach
  default void seedNotificationReadModel() {
    ensureAccount(account(OWNER));
    ensureAccount(account(OTHER));
    notifications().save(notification("notification-query-a", OWNER, NOW, false));
    notifications().save(notification("notification-query-b", OWNER, NOW.plusSeconds(1), false));
    notifications().save(notification("notification-query-other", OTHER, NOW.plusSeconds(2), false));
  }

  @Test
  default void inboxCursorAndMarkReadRemainOwnerScopedAndIdempotent() throws Exception {
    var first = queries().page(OWNER, Optional.empty(), 1);
    assertThat(first.items()).extracting("id").containsExactly("notification-query-b");
    assertThat(first.nextCursor()).isNotBlank();

    var second = queries().page(OWNER, cursors().decode(first.nextCursor()), 1);
    assertThat(second.items()).extracting("id").containsExactly("notification-query-a");
    assertThat(queries().markAllRead(OWNER).updatedCount()).isEqualTo(2);
    assertThat(queries().markAllRead(OWNER).updatedCount()).isZero();
    assertThat(notifications().countByAccountIdAndReadFalse(OTHER)).isOne();
  }

  @Test
  default void preferenceUpsertRoundTripsEveryCategory() {
    var initial = NotificationPreference.builder().id("notification-preference-contract")
        .accountId(OWNER).mentions(true).likes(false).comments(true).messages(false)
        .wflSessions(true).createdOn(NOW).lastUpdatedOn(NOW).build();
    preferences().save(initial);
    initial.setMentions(false);
    initial.setLikes(true);
    initial.setLastUpdatedOn(NOW.plusSeconds(1));

    preferences().save(initial);

    assertThat(preferences().findByAccountId(OWNER)).get()
        .extracting(NotificationPreference::isMentions, NotificationPreference::isLikes,
            NotificationPreference::isComments, NotificationPreference::isMessages,
            NotificationPreference::isWflSessions)
        .containsExactly(false, true, true, false, true);
  }

  private static Account account(String id) {
    return Account.builder().id(id).createdOn(NOW).email(id + "@example.test")
        .passwordHash("hash").role(dev.christopherbell.account.model.Role.USER)
        .status(dev.christopherbell.account.model.AccountStatus.ACTIVE).username(id).build();
  }

  private static Notification notification(
      String id, String accountId, Instant createdOn, boolean read) {
    return Notification.builder().id(id).accountId(accountId).actorAccountId(OTHER)
        .actorUsername(OTHER).notificationType(NotificationType.MENTION).read(read)
        .createdOn(createdOn).build();
  }
}
