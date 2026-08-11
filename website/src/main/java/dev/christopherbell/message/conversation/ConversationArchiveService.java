package dev.christopherbell.message.conversation;

import java.time.Clock;
import java.util.Set;
import dev.christopherbell.configuration.mongo.domain.DomainMongoOperationsFactory;
import dev.christopherbell.configuration.mongo.domain.KindScopedMongoOperations;
import dev.christopherbell.message.model.Message;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

/** Persists per-user conversation visibility without changing messages or the other participant. */
@Service
public class ConversationArchiveService {
  private final KindScopedMongoOperations<Message> messages;
  private final KindScopedMongoOperations<ConversationArchiveState> archives;
  private final Clock clock;

  @Autowired
  public ConversationArchiveService(DomainMongoOperationsFactory factory) {
    this(factory, Clock.systemUTC());
  }

  ConversationArchiveService(DomainMongoOperationsFactory factory, Clock clock) {
    this.messages = factory.forType(Message.class);
    this.archives = factory.forType(ConversationArchiveState.class);
    this.clock = clock;
  }

  /** Upserts the caller-owned archive marker at the current instant. */
  public ConversationArchiveResult archive(
      String ownerAccountId,
      String conversationKey,
      Set<String> participantIds
  ) {
    var archivedAt = clock.instant();
    var latestQuery = new Query(Criteria.where("conversationKey").is(conversationKey))
        .with(Sort.by(Sort.Direction.DESC, "createdOn", "id"))
        .limit(1);
    var latest = messages.findOne(latestQuery).orElse(null);
    var query = new Query(new Criteria().andOperator(
        Criteria.where("ownerAccountId").is(ownerAccountId),
        Criteria.where("conversationKey").is(conversationKey)));
    var state = archives.findOne(query).orElseGet(() -> {
      var created = new ConversationArchiveState();
      created.setId(ownerAccountId + ":" + conversationKey);
      return created;
    });
    state.setOwnerAccountId(ownerAccountId);
    state.setConversationKey(conversationKey);
    state.setParticipantIds(Set.copyOf(participantIds));
    state.setArchivedThroughMessageId(latest == null ? null : latest.getId());
    state.setArchivedAt(archivedAt);
    archives.save(state);
    return new ConversationArchiveResult(conversationKey, archivedAt);
  }
}
