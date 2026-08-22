package dev.christopherbell.message;

import dev.christopherbell.configuration.persistence.PostgresPersistence;
import dev.christopherbell.configuration.persistence.PostgresqlSchemaNames;
import dev.christopherbell.message.model.Message;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionOperations;

/** PostgreSQL implementation of the direct-message persistence port. */
@PostgresPersistence
public class PostgresMessageRepository implements MessageRepository {
  private final JdbcClient database;
  private final TransactionOperations transactions;
  private final String messageTable;
  private final String participantTable;

  public PostgresMessageRepository(
      JdbcClient database, PostgresqlSchemaNames schemas, TransactionOperations transactions) {
    this.database = database;
    this.transactions = transactions;
    messageTable = schemas.qualifiedTable("communication", "message");
    participantTable = schemas.qualifiedTable("communication", "message_participant");
  }

  @Override
  public Message save(Message message) {
    var saved = transactions.execute(ignored -> saveInTransaction(message));
    if (saved == null) throw new IllegalStateException("Message transaction returned no value");
    return saved;
  }

  private Message saveInTransaction(Message message) {
    database.sql("""
            insert into %s (
              message_id, conversation_key, sender_account_id, recipient_account_id,
              message_text, is_read, created_on)
            values (:id, :conversationKey, :senderId, :recipientId, :text, :read, :createdOn)
            on conflict (message_id) do update set
              conversation_key = excluded.conversation_key,
              sender_account_id = excluded.sender_account_id,
              recipient_account_id = excluded.recipient_account_id,
              message_text = excluded.message_text,
              is_read = excluded.is_read,
              version = %s.version + 1
            """.formatted(messageTable, messageTable))
        .param("id", message.getId()).param("conversationKey", message.getConversationKey())
        .param("senderId", message.getSenderAccountId())
        .param("recipientId", message.getRecipientAccountId())
        .param("text", message.getText()).param("read", Boolean.TRUE.equals(message.getRead()))
        .param("createdOn", message.getCreatedOn().atOffset(ZoneOffset.UTC)).update();
    database.sql("delete from %s where message_id = :id".formatted(participantTable))
        .param("id", message.getId()).update();
    if (message.getParticipantIds() != null) {
      for (var participant : message.getParticipantIds()) {
        database.sql("""
                insert into %s (message_id, account_id) values (:messageId, :accountId)
                """.formatted(participantTable))
            .param("messageId", message.getId()).param("accountId", participant).update();
      }
    }
    return loadByIds(List.of(message.getId())).getFirst();
  }

  @Override
  public Iterable<Message> saveAll(Iterable<Message> messages) {
    var result = transactions.execute(ignored -> {
      var saved = new ArrayList<Message>();
      messages.forEach(message -> saved.add(saveInTransaction(message)));
      return List.copyOf(saved);
    });
    return result == null ? List.of() : result;
  }

  @Override
  public List<Message> findByConversationKeyOrderByCreatedOnAsc(
      String conversationKey, Pageable pageable) {
    return queryMessages(
        "conversation_key = :conversationKey",
        Map.of("conversationKey", conversationKey),
        "order by created_on asc, message_id asc " + pageClause(pageable),
        pageParameters(pageable));
  }

  @Override
  public List<Message> findByParticipantIdsContainingOrderByCreatedOnDesc(
      String accountId, Pageable pageable) {
    var suffix = pageClause(pageable);
    var statement = database.sql("""
            select message_id from %s where account_id = :accountId
            order by message_id
            """.formatted(participantTable)).param("accountId", accountId);
    var participantIds = statement.query(String.class).list();
    if (participantIds.isEmpty()) return List.of();
    return queryMessages(
        "message_id in (:ids)", Map.of("ids", participantIds),
        "order by created_on desc, message_id desc " + suffix, pageParameters(pageable));
  }

  /** Loads exact message identities in the caller's stable order, including participants. */
  public List<Message> loadByIds(List<String> ids) {
    if (ids.isEmpty()) return List.of();
    var loaded = queryMessages("message_id in (:ids)", Map.of("ids", ids), "", Map.of());
    var byId = new LinkedHashMap<String, Message>();
    loaded.forEach(message -> byId.put(message.getId(), message));
    return ids.stream().map(byId::get).filter(java.util.Objects::nonNull).toList();
  }

  private List<Message> queryMessages(
      String where, Map<String, ?> parameters, String suffix, Map<String, ?> suffixParameters) {
    var query = database.sql("select * from %s where %s %s".formatted(messageTable, where, suffix));
    for (var entry : parameters.entrySet()) query.param(entry.getKey(), entry.getValue());
    for (var entry : suffixParameters.entrySet()) query.param(entry.getKey(), entry.getValue());
    return attachParticipants(query.query(PostgresMessageRepository::mapBase).list());
  }

  /** Attaches ordered participant identities to already loaded message rows. */
  public List<Message> attachParticipants(List<Message> messages) {
    if (messages.isEmpty()) return List.of();
    Map<String, LinkedHashSet<String>> participants = new LinkedHashMap<>();
    messages.forEach(message -> participants.put(message.getId(), new LinkedHashSet<>()));
    database.sql("""
            select message_id, account_id from %s where message_id in (:ids)
            order by message_id asc, account_id asc
            """.formatted(participantTable))
        .param("ids", participants.keySet())
        .query((row, ignored) -> Map.entry(row.getString(1), row.getString(2)))
        .list().forEach(value -> participants.get(value.getKey()).add(value.getValue()));
    messages.forEach(message -> message.setParticipantIds(participants.get(message.getId())));
    return List.copyOf(messages);
  }

  /** Maps one base message row; participant loading remains a separate bounded query. */
  public static Message mapBase(java.sql.ResultSet row, int rowNumber) throws SQLException {
    return Message.builder()
        .id(row.getString("message_id"))
        .conversationKey(row.getString("conversation_key"))
        .senderAccountId(row.getString("sender_account_id"))
        .recipientAccountId(row.getString("recipient_account_id"))
        .text(row.getString("message_text"))
        .read(row.getBoolean("is_read"))
        .createdOn(row.getObject("created_on", OffsetDateTime.class).toInstant())
        .build();
  }

  private static String pageClause(Pageable pageable) {
    return pageable.isPaged() ? "limit :limit offset :offset" : "";
  }

  private static Map<String, ?> pageParameters(Pageable pageable) {
    return pageable.isPaged()
        ? Map.of("limit", pageable.getPageSize(), "offset", Math.toIntExact(pageable.getOffset()))
        : Map.of();
  }
}
