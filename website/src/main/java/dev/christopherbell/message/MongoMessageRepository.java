package dev.christopherbell.message;

import dev.christopherbell.configuration.persistence.MongoPersistence;

import dev.christopherbell.configuration.mongo.domain.DomainMongoOperationsFactory;
import dev.christopherbell.configuration.mongo.domain.KindScopedRepositorySupport;
import dev.christopherbell.message.model.Message;
import java.util.ArrayList;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Repository;

@MongoPersistence
@Repository
class MongoMessageRepository extends KindScopedRepositorySupport<Message>
    implements MessageRepository {
  MongoMessageRepository(DomainMongoOperationsFactory factory) { super(factory, Message.class); }

  @Override public Message save(Message message) { return saveValue(message); }
  @Override public List<Message> saveAll(Iterable<Message> messages) {
    var saved = new ArrayList<Message>();
    messages.forEach(message -> saved.add(saveValue(message)));
    return List.copyOf(saved);
  }
  @Override
  public List<Message> findByConversationKeyOrderByCreatedOnAsc(
      String key, Pageable pageable) {
    return find(Query.query(Criteria.where("conversationKey").is(key))
        .with(Sort.by(Sort.Direction.ASC, "createdOn")), pageable);
  }
  @Override
  public List<Message> findByParticipantIdsContainingOrderByCreatedOnDesc(
      String accountId, Pageable pageable) {
    return find(Query.query(Criteria.where("participantIds").is(accountId))
        .with(Sort.by(Sort.Direction.DESC, "createdOn")), pageable);
  }
}
