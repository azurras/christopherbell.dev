package dev.christopherbell.message.conversation;

import java.time.Clock;
import java.util.Set;
import dev.christopherbell.message.model.Message;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

/** Persists per-user conversation visibility without changing messages or the other participant. */
@Service
public class ConversationArchiveService {
  private final MongoTemplate mongo;
  private final Clock clock;

  @Autowired
  public ConversationArchiveService(MongoTemplate mongo) {
    this(mongo, Clock.systemUTC());
  }

  ConversationArchiveService(MongoTemplate mongo, Clock clock) {
    this.mongo = mongo;
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
        .with(Sort.by(Sort.Direction.DESC, "createdOn", "_id"))
        .limit(1);
    var latest = mongo.findOne(latestQuery, Message.class);
    var query = new Query(new Criteria().andOperator(
        Criteria.where("ownerAccountId").is(ownerAccountId),
        Criteria.where("conversationKey").is(conversationKey)));
    var update = new Update()
        .set("ownerAccountId", ownerAccountId)
        .set("conversationKey", conversationKey)
        .set("participantIds", Set.copyOf(participantIds))
        .set("archivedThroughMessageId", latest == null ? null : latest.getId())
        .set("archivedAt", archivedAt);
    mongo.upsert(query, update, ConversationArchiveState.class);
    return new ConversationArchiveResult(conversationKey, archivedAt);
  }
}
