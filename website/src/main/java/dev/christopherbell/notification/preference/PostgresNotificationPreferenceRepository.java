package dev.christopherbell.notification.preference;

import static dev.christopherbell.persistence.jooq.communication.Tables.NOTIFICATION_PREFERENCE;

import dev.christopherbell.configuration.persistence.PostgresPersistence;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import org.jooq.DSLContext;

/** PostgreSQL persistence for account notification preferences. */
@PostgresPersistence
public class PostgresNotificationPreferenceRepository
    implements NotificationPreferenceRepository {
  private final DSLContext database;

  public PostgresNotificationPreferenceRepository(DSLContext database) {
    this.database = database;
  }

  @Override
  public NotificationPreference save(NotificationPreference preference) {
    database.insertInto(NOTIFICATION_PREFERENCE)
        .set(NOTIFICATION_PREFERENCE.NOTIFICATION_PREFERENCE_ID, preference.getId())
        .set(NOTIFICATION_PREFERENCE.ACCOUNT_ID, preference.getAccountId())
        .set(NOTIFICATION_PREFERENCE.MENTIONS, preference.isMentions())
        .set(NOTIFICATION_PREFERENCE.LIKES, preference.isLikes())
        .set(NOTIFICATION_PREFERENCE.COMMENTS, preference.isComments())
        .set(NOTIFICATION_PREFERENCE.MESSAGES, preference.isMessages())
        .set(NOTIFICATION_PREFERENCE.WFL_SESSIONS, preference.isWflSessions())
        .set(NOTIFICATION_PREFERENCE.CREATED_ON, timestamp(preference.getCreatedOn()))
        .set(NOTIFICATION_PREFERENCE.LAST_UPDATED_ON, timestamp(preference.getLastUpdatedOn()))
        .onConflict(NOTIFICATION_PREFERENCE.ACCOUNT_ID)
        .doUpdate()
        .set(NOTIFICATION_PREFERENCE.MENTIONS, preference.isMentions())
        .set(NOTIFICATION_PREFERENCE.LIKES, preference.isLikes())
        .set(NOTIFICATION_PREFERENCE.COMMENTS, preference.isComments())
        .set(NOTIFICATION_PREFERENCE.MESSAGES, preference.isMessages())
        .set(NOTIFICATION_PREFERENCE.WFL_SESSIONS, preference.isWflSessions())
        .set(NOTIFICATION_PREFERENCE.LAST_UPDATED_ON, timestamp(preference.getLastUpdatedOn()))
        .set(NOTIFICATION_PREFERENCE.VERSION, NOTIFICATION_PREFERENCE.VERSION.plus(1L))
        .execute();
    return findByAccountId(preference.getAccountId()).orElseThrow();
  }

  @Override
  public Optional<NotificationPreference> findByAccountId(String accountId) {
    return database.selectFrom(NOTIFICATION_PREFERENCE)
        .where(NOTIFICATION_PREFERENCE.ACCOUNT_ID.eq(accountId))
        .fetchOptional(record -> NotificationPreference.builder()
            .id(record.getNotificationPreferenceId())
            .accountId(record.getAccountId())
            .mentions(record.getMentions())
            .likes(record.getLikes())
            .comments(record.getComments())
            .messages(record.getMessages())
            .wflSessions(record.getWflSessions())
            .createdOn(instant(record.getCreatedOn()))
            .lastUpdatedOn(instant(record.getLastUpdatedOn()))
            .build());
  }

  private static OffsetDateTime timestamp(java.time.Instant value) {
    return value == null ? null : value.atOffset(ZoneOffset.UTC);
  }

  private static java.time.Instant instant(OffsetDateTime value) {
    return value == null ? null : value.toInstant();
  }
}
