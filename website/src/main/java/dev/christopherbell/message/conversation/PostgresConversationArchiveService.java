package dev.christopherbell.message.conversation;

import dev.christopherbell.configuration.persistence.PostgresPersistence;
import dev.christopherbell.configuration.persistence.PostgresqlSchemaNames;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.Set;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionOperations;

/** PostgreSQL owner-scoped conversation archive transition. */
@PostgresPersistence
public class PostgresConversationArchiveService implements ConversationArchivePort {
  private final JdbcClient database;
  private final TransactionOperations transactions;
  private final Clock clock;
  private final String messageTable;
  private final String archiveTable;
  private final String participantTable;

  public PostgresConversationArchiveService(
      JdbcClient database, PostgresqlSchemaNames schemas,
      TransactionOperations transactions, Clock clock) {
    this.database = database;
    this.transactions = transactions;
    this.clock = clock;
    messageTable = schemas.qualifiedTable("communication", "message");
    archiveTable = schemas.qualifiedTable("communication", "conversation_archive_state");
    participantTable = schemas.qualifiedTable("communication", "conversation_archive_participant");
  }

  @Override
  public ConversationArchiveResult archive(
      String ownerAccountId, String conversationKey, Set<String> participantIds) {
    var archivedAt = clock.instant();
    transactions.executeWithoutResult(status -> {
      var latestId = database.sql("""
              select message_id from %s where conversation_key = :conversationKey
              order by created_on desc, message_id desc limit 1
              """.formatted(messageTable))
          .param("conversationKey", conversationKey).query(String.class).optional().orElse(null);
      var archiveId = ownerAccountId + ":" + conversationKey;
      database.sql("""
              insert into %s
                (archive_state_id, owner_account_id, conversation_key,
                 archived_through_message_id, archived_at)
              values (:id, :owner, :conversationKey, :latestId, :archivedAt)
              on conflict (owner_account_id, conversation_key) do update set
                archived_through_message_id = excluded.archived_through_message_id,
                archived_at = excluded.archived_at,
                version = %s.version + 1
              """.formatted(archiveTable, archiveTable))
          .param("id", archiveId).param("owner", ownerAccountId)
          .param("conversationKey", conversationKey)
          .param("latestId", latestId, java.sql.Types.VARCHAR)
          .param("archivedAt", archivedAt.atOffset(ZoneOffset.UTC)).update();
      database.sql("delete from %s where archive_state_id = :id".formatted(participantTable))
          .param("id", archiveId).update();
      for (var participantId : Set.copyOf(participantIds)) {
        database.sql("""
                insert into %s (archive_state_id, account_id) values (:id, :accountId)
                """.formatted(participantTable))
            .param("id", archiveId).param("accountId", participantId).update();
      }
    });
    return new ConversationArchiveResult(conversationKey, archivedAt);
  }
}
