package dev.christopherbell.message.conversation;

import java.time.Clock;
import java.time.Instant;
import java.util.Set;
import dev.christopherbell.configuration.persistence.MongoPersistence;
import dev.christopherbell.configuration.mongo.domain.DomainMongoOperationsFactory;
import dev.christopherbell.configuration.mongo.domain.KindScopedMongoOperations;
import dev.christopherbell.message.model.Message;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

/** Persists per-user conversation visibility without changing messages or the other participant. */
@MongoPersistence
public class ConversationArchiveService implements ConversationArchivePort {
  private final KindScopedMongoOperations<Message> messages;
  private final KindScopedMongoOperations<ConversationArchiveState> archives;
  private final Clock clock;

  @Autowired
  public ConversationArchiveService(DomainMongoOperationsFactory factory, Clock clock) {
    this.messages = factory.forType(Message.class);
    this.archives = factory.forType(ConversationArchiveState.class);
    this.clock = clock;
  }

  /**
   * Upserts the owner's archive marker at the current instant, recording the conversation's latest
   * message so later messages make the conversation visible again.
   */
  @Override
  public ConversationArchiveResult archive(
      String ownerAccountId,
      String conversationKey,
      Set<String> participantIds
  ) {
    Instant archivedAt = clock.instant();
    Query latestMessageQuery = new Query(Criteria.where("conversationKey").is(conversationKey))
        .with(Sort.by(Sort.Direction.DESC, "createdOn", "id"))
        .limit(1);
    Message latestMessage = messages.findOne(latestMessageQuery).orElse(null);
    archives.upsertById(
        ownerAccountId + ":" + conversationKey,
        new Update()
            .set("ownerAccountId", ownerAccountId)
            .set("conversationKey", conversationKey)
            .set("participantIds", Set.copyOf(participantIds))
            .set("archivedThroughMessageId", latestMessage == null ? null : latestMessage.getId())
            .set("archivedAt", archivedAt));
    return new ConversationArchiveResult(conversationKey, archivedAt);
  }
}
