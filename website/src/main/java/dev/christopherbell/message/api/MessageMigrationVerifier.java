package dev.christopherbell.message.api;

import static dev.christopherbell.configuration.persistence.PostgresqlMigrationVerificationSupport.database;
import static dev.christopherbell.configuration.persistence.PostgresqlMigrationVerificationSupport.instant;
import static dev.christopherbell.configuration.persistence.PostgresqlMigrationVerificationSupport.rollback;
import static dev.christopherbell.configuration.persistence.PostgresqlMigrationVerificationSupport.text;

import dev.christopherbell.configuration.persistence.PostgresPersistenceSupport;
import dev.christopherbell.message.PostgresMessageRepository;
import dev.christopherbell.message.conversation.PostgresConversationArchiveService;
import dev.christopherbell.message.model.Message;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.data.domain.PageRequest;

/** Published message-module adapter operations used by cutover parity. */
@PostgresPersistenceSupport
public final class MessageMigrationVerifier {
  private MessageMigrationVerifier() {}

  public static boolean verify(
      Connection connection, String schema, String queryName,
      Map<String, List<Map<String, Object>>> tables) throws SQLException {
    var context = database(connection, schema);
    return switch (queryName) {
      case "archive-conversation" -> verifyArchive(connection, context, tables);
      case "conversation-page" -> verifyConversation(context, tables.get("message"));
      case "participant-page" -> verifyParticipant(context, tables);
      default -> false;
    };
  }

  private static boolean verifyArchive(
      Connection connection,
      org.jooq.DSLContext context,
      Map<String, List<Map<String, Object>>> tables) throws SQLException {
    var states = tables.get("conversation_archive_state");
    if (states.isEmpty()) {
      return true;
    }
    var participants = tables.get("conversation_archive_participant");
    return rollback(connection, () -> states.stream().allMatch(row -> {
      var id = text(row.get("archive_state_id"));
      var participantIds = participants.stream()
          .filter(child -> id.equals(text(child.get("archive_state_id"))))
          .map(child -> text(child.get("account_id")))
          .collect(java.util.stream.Collectors.toUnmodifiableSet());
      var archivedAt = instant(row.get("archived_at"));
      var service = new PostgresConversationArchiveService(
          context, Clock.fixed(archivedAt == null ? Instant.EPOCH : archivedAt, ZoneOffset.UTC));
      var result = service.archive(
          text(row.get("owner_account_id")), text(row.get("conversation_key")), participantIds);
      return text(row.get("conversation_key")).equals(result.conversationKey());
    }));
  }

  private static boolean verifyConversation(
      org.jooq.DSLContext context, List<Map<String, Object>> rows) {
    var repository = new PostgresMessageRepository(context);
    for (var key : values(rows, "conversation_key")) {
      var expected = rows.stream().filter(row -> key.equals(text(row.get("conversation_key"))))
          .sorted(Comparator.comparing(MessageMigrationVerifier::createdOn)
              .thenComparing(row -> text(row.get("message_id"))))
          .map(row -> text(row.get("message_id"))).toList();
      var first = repository.findByConversationKeyOrderByCreatedOnAsc(key, PageRequest.of(0, 2));
      var offset = repository.findByConversationKeyOrderByCreatedOnAsc(key, PageRequest.of(1, 1));
      if (!ids(first).equals(expected.stream().limit(2).toList())
          || !ids(offset).equals(expected.stream().skip(1).limit(1).toList())) {
        return false;
      }
    }
    return repository.findByConversationKeyOrderByCreatedOnAsc(
        "migration-verifier-missing-conversation", PageRequest.of(0, 1)).isEmpty();
  }

  private static boolean verifyParticipant(
      org.jooq.DSLContext context, Map<String, List<Map<String, Object>>> tables) {
    var messages = tables.get("message");
    var participants = tables.get("message_participant");
    var repository = new PostgresMessageRepository(context);
    for (var account : values(participants, "account_id")) {
      var idsForAccount = participants.stream()
          .filter(row -> account.equals(text(row.get("account_id"))))
          .map(row -> text(row.get("message_id"))).collect(java.util.stream.Collectors.toSet());
      var expected = messages.stream()
          .filter(row -> idsForAccount.contains(text(row.get("message_id"))))
          .sorted(Comparator.comparing(MessageMigrationVerifier::createdOn).reversed()
              .thenComparing(row -> text(row.get("message_id")), Comparator.reverseOrder()))
          .map(row -> text(row.get("message_id"))).toList();
      var first = repository.findByParticipantIdsContainingOrderByCreatedOnDesc(
          account, PageRequest.of(0, 2));
      var offset = repository.findByParticipantIdsContainingOrderByCreatedOnDesc(
          account, PageRequest.of(1, 1));
      if (!ids(first).equals(expected.stream().limit(2).toList())
          || !ids(offset).equals(expected.stream().skip(1).limit(1).toList())) {
        return false;
      }
    }
    return true;
  }

  private static List<String> values(List<Map<String, Object>> rows, String key) {
    return rows.stream().map(row -> text(row.get(key))).filter(java.util.Objects::nonNull)
        .distinct().toList();
  }

  private static List<String> ids(List<Message> messages) {
    return messages.stream().map(Message::getId).toList();
  }

  private static Instant createdOn(Map<String, Object> row) {
    return instant(row.get("created_on"));
  }
}
