package dev.christopherbell.message.conversation;

import dev.christopherbell.configuration.persistence.PostgresPersistence;
import dev.christopherbell.configuration.persistence.PostgresqlSchemaNames;
import dev.christopherbell.libs.pagination.StableCursor;
import dev.christopherbell.libs.pagination.StableCursorCodec;
import dev.christopherbell.message.PostgresMessageRepository;
import dev.christopherbell.message.model.Message;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionOperations;

/** PostgreSQL distinct-conversation, unread-count, and stable history queries. */
@PostgresPersistence
public class PostgresConversationQueryRepository implements ConversationQueryPort {
  private static final int MAX_PAGE_SIZE = 100;
  private final JdbcClient database;
  private final StableCursorCodec cursors;
  private final PostgresMessageRepository messages;
  private final String messageTable;
  private final String participantTable;
  private final String archiveStateTable;

  public PostgresConversationQueryRepository(
      JdbcClient database, PostgresqlSchemaNames schemas,
      TransactionOperations transactions, StableCursorCodec cursors) {
    this.database = database;
    this.cursors = cursors;
    messages = new PostgresMessageRepository(database, schemas, transactions);
    messageTable = schemas.qualifiedTable("communication", "message");
    participantTable = schemas.qualifiedTable("communication", "message_participant");
    archiveStateTable = schemas.qualifiedTable("communication", "conversation_archive_state");
  }

  @Override
  public List<Message> latestDistinctVisible(String ownerAccountId, int requestedLimit) {
    var limit = Math.max(1, Math.min(requestedLimit, 50));
    var loaded = database.sql("""
            select message.* from %1$s message
            where exists (
              select 1 from %2$s participant
              where participant.message_id = message.message_id
                and participant.account_id = :ownerId)
              and message.message_id = (
                select candidate.message_id from %1$s candidate
                where candidate.conversation_key = message.conversation_key
                order by candidate.created_on desc, candidate.message_id desc limit 1)
              and not exists (
                select 1 from %3$s archive
                where archive.owner_account_id = :ownerId
                  and archive.conversation_key = message.conversation_key
                  and archive.archived_through_message_id = message.message_id)
            order by message.created_on desc, message.message_id desc limit :limit
            """.formatted(messageTable, participantTable, archiveStateTable))
        .param("ownerId", ownerAccountId).param("limit", limit)
        .query(PostgresMessageRepository::mapBase).list();
    return messages.attachParticipants(loaded);
  }

  @Override
  public Map<String, Long> unreadCounts(
      String recipientAccountId, Collection<String> senderAccountIds) {
    if (senderAccountIds.isEmpty()) return Map.of();
    var result = new LinkedHashMap<String, Long>();
    database.sql("""
            select sender_account_id, count(*) as unread_count from %s
            where recipient_account_id = :recipientId
              and sender_account_id in (:senderIds) and not is_read
            group by sender_account_id order by sender_account_id
            """.formatted(messageTable))
        .param("recipientId", recipientAccountId).param("senderIds", senderAccountIds)
        .query((row, ignored) -> Map.entry(
            row.getString("sender_account_id"), row.getLong("unread_count")))
        .list().forEach(value -> result.put(value.getKey(), value.getValue()));
    return Map.copyOf(result);
  }

  @Override
  public ConversationMessageSlice page(
      String conversationKey, Optional<StableCursor> cursor, int requestedSize) {
    var size = Math.max(1, Math.min(requestedSize, MAX_PAGE_SIZE));
    var boundary = cursor.orElse(null);
    var cursorClause = boundary == null ? "" : """
        and (created_on < :cursorTime
          or (created_on = :cursorTime and message_id < :cursorId))
        """;
    var statement = database.sql("""
            select * from %s where conversation_key = :conversationKey %s
            order by created_on desc, message_id desc limit :limit
            """.formatted(messageTable, cursorClause))
        .param("conversationKey", conversationKey).param("limit", size + 1);
    if (boundary != null) {
      statement.param("cursorTime", boundary.timestamp().atOffset(ZoneOffset.UTC))
          .param("cursorId", boundary.id());
    }
    var loaded = messages.attachParticipants(
        statement.query(PostgresMessageRepository::mapBase).list());
    var hasNext = loaded.size() > size;
    var items = loaded.stream().limit(size).toList();
    String nextCursor = null;
    if (hasNext && !items.isEmpty()) {
      var last = items.getLast();
      nextCursor = cursors.encode(new StableCursor(last.getCreatedOn(), last.getId()));
    }
    return new ConversationMessageSlice(items, nextCursor);
  }
}
