package dev.christopherbell.message.conversation;

import static dev.christopherbell.persistence.jooq.communication.Tables.CONVERSATION_ARCHIVE_STATE;
import static dev.christopherbell.persistence.jooq.communication.Tables.MESSAGE;
import static dev.christopherbell.persistence.jooq.communication.Tables.MESSAGE_PARTICIPANT;

import dev.christopherbell.configuration.persistence.PostgresPersistence;
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
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;

/** PostgreSQL distinct-conversation, unread-count, and stable history queries. */
@PostgresPersistence
public class PostgresConversationQueryRepository implements ConversationQueryPort {
  private static final int MAX_PAGE_SIZE = 100;
  private final DSLContext database;
  private final StableCursorCodec cursors;

  public PostgresConversationQueryRepository(DSLContext database, StableCursorCodec cursors) {
    this.database = database;
    this.cursors = cursors;
  }

  @Override
  public List<Message> latestDistinctVisible(String ownerAccountId, int requestedLimit) {
    var limit = Math.max(1, Math.min(requestedLimit, 50));
    var candidate = MESSAGE.as("candidate_message");
    var latestId = database.select(candidate.MESSAGE_ID)
        .from(candidate)
        .where(candidate.CONVERSATION_KEY.eq(MESSAGE.CONVERSATION_KEY))
        .orderBy(candidate.CREATED_ON.desc(), candidate.MESSAGE_ID.desc())
        .limit(1);
    var participates = DSL.exists(database.selectOne().from(MESSAGE_PARTICIPANT)
        .where(MESSAGE_PARTICIPANT.MESSAGE_ID.eq(MESSAGE.MESSAGE_ID)
            .and(MESSAGE_PARTICIPANT.ACCOUNT_ID.eq(ownerAccountId))));
    var archivedAtLatest = DSL.exists(database.selectOne().from(CONVERSATION_ARCHIVE_STATE)
        .where(CONVERSATION_ARCHIVE_STATE.OWNER_ACCOUNT_ID.eq(ownerAccountId)
            .and(CONVERSATION_ARCHIVE_STATE.CONVERSATION_KEY.eq(MESSAGE.CONVERSATION_KEY))
            .and(CONVERSATION_ARCHIVE_STATE.ARCHIVED_THROUGH_MESSAGE_ID.eq(MESSAGE.MESSAGE_ID))));
    var records = database.selectFrom(MESSAGE)
        .where(participates)
        .and(MESSAGE.MESSAGE_ID.eq(latestId))
        .andNot(archivedAtLatest)
        .orderBy(MESSAGE.CREATED_ON.desc(), MESSAGE.MESSAGE_ID.desc())
        .limit(limit)
        .fetch();
    return PostgresMessageRepository.mapAll(database, records);
  }

  @Override
  public Map<String, Long> unreadCounts(
      String recipientAccountId, Collection<String> senderAccountIds) {
    if (senderAccountIds.isEmpty()) return Map.of();
    var result = new LinkedHashMap<String, Long>();
    database.select(MESSAGE.SENDER_ACCOUNT_ID, DSL.count())
        .from(MESSAGE)
        .where(MESSAGE.RECIPIENT_ACCOUNT_ID.eq(recipientAccountId)
            .and(MESSAGE.SENDER_ACCOUNT_ID.in(senderAccountIds))
            .and(MESSAGE.IS_READ.isFalse()))
        .groupBy(MESSAGE.SENDER_ACCOUNT_ID)
        .fetch()
        .forEach(row -> result.put(row.value1(), row.value2().longValue()));
    return Map.copyOf(result);
  }

  @Override
  public ConversationMessageSlice page(
      String conversationKey, Optional<StableCursor> cursor, int requestedSize) {
    var size = Math.max(1, Math.min(requestedSize, MAX_PAGE_SIZE));
    Condition condition = MESSAGE.CONVERSATION_KEY.eq(conversationKey);
    if (cursor.isPresent()) {
      var boundary = cursor.orElseThrow();
      var timestamp = boundary.timestamp().atOffset(ZoneOffset.UTC);
      condition = condition.and(MESSAGE.CREATED_ON.lt(timestamp)
          .or(MESSAGE.CREATED_ON.eq(timestamp).and(MESSAGE.MESSAGE_ID.lt(boundary.id()))));
    }
    var records = database.selectFrom(MESSAGE)
        .where(condition)
        .orderBy(MESSAGE.CREATED_ON.desc(), MESSAGE.MESSAGE_ID.desc())
        .limit(size + 1)
        .fetch();
    var loaded = PostgresMessageRepository.mapAll(database, records);
    var hasNext = loaded.size() > size;
    var items = loaded.stream().limit(size).toList();
    String nextCursor = null;
    if (hasNext && !items.isEmpty()) {
      var boundary = items.getLast();
      nextCursor = cursors.encode(new StableCursor(boundary.getCreatedOn(), boundary.getId()));
    }
    return new ConversationMessageSlice(items, nextCursor);
  }
}
