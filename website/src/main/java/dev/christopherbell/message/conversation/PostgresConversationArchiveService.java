package dev.christopherbell.message.conversation;

import static dev.christopherbell.persistence.jooq.communication.Tables.CONVERSATION_ARCHIVE_PARTICIPANT;
import static dev.christopherbell.persistence.jooq.communication.Tables.CONVERSATION_ARCHIVE_STATE;
import static dev.christopherbell.persistence.jooq.communication.Tables.MESSAGE;

import dev.christopherbell.configuration.persistence.PostgresPersistence;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.Set;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;

/** PostgreSQL owner-scoped conversation archive transition. */
@PostgresPersistence
public final class PostgresConversationArchiveService implements ConversationArchivePort {
  private final DSLContext database;
  private final Clock clock;

  public PostgresConversationArchiveService(DSLContext database) {
    this(database, Clock.systemUTC());
  }

  PostgresConversationArchiveService(DSLContext database, Clock clock) {
    this.database = database;
    this.clock = clock;
  }

  @Override
  public ConversationArchiveResult archive(
      String ownerAccountId, String conversationKey, Set<String> participantIds) {
    var archivedAt = clock.instant();
    database.transaction(configuration -> {
      var transaction = DSL.using(configuration);
      var latestId = transaction.select(MESSAGE.MESSAGE_ID).from(MESSAGE)
          .where(MESSAGE.CONVERSATION_KEY.eq(conversationKey))
          .orderBy(MESSAGE.CREATED_ON.desc(), MESSAGE.MESSAGE_ID.desc())
          .limit(1).fetchOne(MESSAGE.MESSAGE_ID);
      var archiveId = ownerAccountId + ":" + conversationKey;
      transaction.insertInto(CONVERSATION_ARCHIVE_STATE)
          .set(CONVERSATION_ARCHIVE_STATE.ARCHIVE_STATE_ID, archiveId)
          .set(CONVERSATION_ARCHIVE_STATE.OWNER_ACCOUNT_ID, ownerAccountId)
          .set(CONVERSATION_ARCHIVE_STATE.CONVERSATION_KEY, conversationKey)
          .set(CONVERSATION_ARCHIVE_STATE.ARCHIVED_THROUGH_MESSAGE_ID, latestId)
          .set(CONVERSATION_ARCHIVE_STATE.ARCHIVED_AT,
              archivedAt.atOffset(ZoneOffset.UTC))
          .onConflict(CONVERSATION_ARCHIVE_STATE.OWNER_ACCOUNT_ID,
              CONVERSATION_ARCHIVE_STATE.CONVERSATION_KEY)
          .doUpdate()
          .set(CONVERSATION_ARCHIVE_STATE.ARCHIVED_THROUGH_MESSAGE_ID, latestId)
          .set(CONVERSATION_ARCHIVE_STATE.ARCHIVED_AT,
              archivedAt.atOffset(ZoneOffset.UTC))
          .set(CONVERSATION_ARCHIVE_STATE.VERSION,
              CONVERSATION_ARCHIVE_STATE.VERSION.plus(1L))
          .execute();
      transaction.deleteFrom(CONVERSATION_ARCHIVE_PARTICIPANT)
          .where(CONVERSATION_ARCHIVE_PARTICIPANT.ARCHIVE_STATE_ID.eq(archiveId))
          .execute();
      for (var participantId : Set.copyOf(participantIds)) {
        transaction.insertInto(CONVERSATION_ARCHIVE_PARTICIPANT)
            .set(CONVERSATION_ARCHIVE_PARTICIPANT.ARCHIVE_STATE_ID, archiveId)
            .set(CONVERSATION_ARCHIVE_PARTICIPANT.ACCOUNT_ID, participantId)
            .execute();
      }
    });
    return new ConversationArchiveResult(conversationKey, archivedAt);
  }
}
