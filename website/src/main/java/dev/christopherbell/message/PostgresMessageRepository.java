package dev.christopherbell.message;

import static dev.christopherbell.persistence.jooq.communication.Tables.MESSAGE;
import static dev.christopherbell.persistence.jooq.communication.Tables.MESSAGE_PARTICIPANT;

import dev.christopherbell.configuration.persistence.PostgresPersistence;
import dev.christopherbell.message.model.Message;
import dev.christopherbell.persistence.jooq.communication.tables.records.MessageRecord;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.springframework.data.domain.Pageable;

/** PostgreSQL implementation of the direct-message persistence port. */
@PostgresPersistence
public final class PostgresMessageRepository implements MessageRepository {
  private final DSLContext database;

  public PostgresMessageRepository(DSLContext database) {
    this.database = database;
  }

  @Override
  public Message save(Message message) {
    return database.transactionResult(configuration -> save(DSL.using(configuration), message));
  }

  private static Message save(DSLContext transaction, Message message) {
    transaction.insertInto(MESSAGE)
        .set(MESSAGE.MESSAGE_ID, message.getId())
        .set(MESSAGE.CONVERSATION_KEY, message.getConversationKey())
        .set(MESSAGE.SENDER_ACCOUNT_ID, message.getSenderAccountId())
        .set(MESSAGE.RECIPIENT_ACCOUNT_ID, message.getRecipientAccountId())
        .set(MESSAGE.MESSAGE_TEXT, message.getText())
        .set(MESSAGE.IS_READ, Boolean.TRUE.equals(message.getRead()))
        .set(MESSAGE.CREATED_ON, message.getCreatedOn().atOffset(ZoneOffset.UTC))
        .onConflict(MESSAGE.MESSAGE_ID)
        .doUpdate()
        .set(MESSAGE.CONVERSATION_KEY, message.getConversationKey())
        .set(MESSAGE.SENDER_ACCOUNT_ID, message.getSenderAccountId())
        .set(MESSAGE.RECIPIENT_ACCOUNT_ID, message.getRecipientAccountId())
        .set(MESSAGE.MESSAGE_TEXT, message.getText())
        .set(MESSAGE.IS_READ, Boolean.TRUE.equals(message.getRead()))
        .set(MESSAGE.VERSION, MESSAGE.VERSION.plus(1L))
        .execute();
    transaction.deleteFrom(MESSAGE_PARTICIPANT)
        .where(MESSAGE_PARTICIPANT.MESSAGE_ID.eq(message.getId())).execute();
    var participants = message.getParticipantIds() == null
        ? java.util.Set.<String>of() : message.getParticipantIds();
    for (var participant : participants) {
      transaction.insertInto(MESSAGE_PARTICIPANT)
          .set(MESSAGE_PARTICIPANT.MESSAGE_ID, message.getId())
          .set(MESSAGE_PARTICIPANT.ACCOUNT_ID, participant)
          .execute();
    }
    return transaction.selectFrom(MESSAGE).where(MESSAGE.MESSAGE_ID.eq(message.getId()))
        .fetchOne(record -> map(transaction, record));
  }

  @Override
  public Iterable<Message> saveAll(Iterable<Message> messages) {
    return database.transactionResult(configuration -> {
      var transaction = DSL.using(configuration);
      var saved = new ArrayList<Message>();
      messages.forEach(message -> saved.add(save(transaction, message)));
      return List.copyOf(saved);
    });
  }

  @Override
  public List<Message> findByConversationKeyOrderByCreatedOnAsc(
      String conversationKey, Pageable pageable) {
    var query = database.selectFrom(MESSAGE)
        .where(MESSAGE.CONVERSATION_KEY.eq(conversationKey))
        .orderBy(MESSAGE.CREATED_ON.asc(), MESSAGE.MESSAGE_ID.asc());
    return pageable.isPaged()
        ? query.limit(pageable.getPageSize()).offset(Math.toIntExact(pageable.getOffset()))
            .fetch(record -> map(database, record))
        : query.fetch(record -> map(database, record));
  }

  @Override
  public List<Message> findByParticipantIdsContainingOrderByCreatedOnDesc(
      String accountId, Pageable pageable) {
    var query = database.select(MESSAGE.fields()).from(MESSAGE)
        .join(MESSAGE_PARTICIPANT).on(MESSAGE_PARTICIPANT.MESSAGE_ID.eq(MESSAGE.MESSAGE_ID))
        .where(MESSAGE_PARTICIPANT.ACCOUNT_ID.eq(accountId))
        .orderBy(MESSAGE.CREATED_ON.desc(), MESSAGE.MESSAGE_ID.desc());
    return pageable.isPaged()
        ? query.limit(pageable.getPageSize()).offset(Math.toIntExact(pageable.getOffset()))
            .fetch(record -> map(database, record.into(MESSAGE)))
        : query.fetch(record -> map(database, record.into(MESSAGE)));
  }

  public static Message map(DSLContext context, MessageRecord record) {
    var participants = new LinkedHashSet<>(context.select(MESSAGE_PARTICIPANT.ACCOUNT_ID)
        .from(MESSAGE_PARTICIPANT)
        .where(MESSAGE_PARTICIPANT.MESSAGE_ID.eq(record.getMessageId()))
        .fetch(MESSAGE_PARTICIPANT.ACCOUNT_ID));
    return Message.builder()
        .id(record.getMessageId())
        .conversationKey(record.getConversationKey())
        .participantIds(participants)
        .senderAccountId(record.getSenderAccountId())
        .recipientAccountId(record.getRecipientAccountId())
        .text(record.getMessageText())
        .read(record.getIsRead())
        .createdOn(record.getCreatedOn().toInstant())
        .build();
  }
}
