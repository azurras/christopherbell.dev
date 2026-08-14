package dev.christopherbell.music.security;

import dev.christopherbell.configuration.mongo.domain.DomainMongoOperationsFactory;
import dev.christopherbell.configuration.mongo.domain.KindScopedMongoOperations;
import dev.christopherbell.configuration.persistence.MongoPersistence;
import java.time.Instant;
import java.util.List;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

/** MongoDB transition adapter for denied Music access attempts. */
@MongoPersistence
public class MongoMusicAccessAttemptRepository implements MusicAccessAttemptRepository {
  private final KindScopedMongoOperations<MusicAccessAttempt> attempts;

  public MongoMusicAccessAttemptRepository(DomainMongoOperationsFactory factory) {
    this.attempts = factory.forType(MusicAccessAttempt.class);
  }

  @Override
  public MusicAccessAttempt record(String id, MusicAccessPrincipalType type, String principal,
      String reason, Instant occurredAt, Instant expiresAt) {
    Query identity = Query.query(Criteria.where("id").is(id));
    Update update = new Update().inc("count", 1).set("lastAttemptAt", occurredAt)
        .set("expiresAt", expiresAt);
    var existing = attempts.findAndUpdate(identity, update);
    if (existing.isPresent()) return existing.get();
    try {
      return attempts.insert(new MusicAccessAttempt(
          id, type, principal, reason, 1, occurredAt, occurredAt, expiresAt));
    } catch (DuplicateKeyException contention) {
      return attempts.findAndUpdate(identity, update).orElseThrow(() ->
          new IllegalStateException("Concurrent Music access audit insert left no record.", contention));
    }
  }

  @Override public List<MusicAccessAttempt> recent(int limit) {
    Query query = new Query().with(Sort.by(Sort.Direction.DESC, "lastAttemptAt")).limit(limit);
    return attempts.find(query, Pageable.unpaged());
  }

  @Override public int deleteExpired(Instant cutoff, int limit) {
    List<String> ids = attempts.find(Query.query(Criteria.where("expiresAt").lte(cutoff))
        .with(Sort.by("expiresAt", "id")).limit(limit), Pageable.unpaged()).stream()
        .map(MusicAccessAttempt::id).toList();
    int deleted = 0;
    for (String id : ids) {
      if (attempts.remove(Query.query(Criteria.where("id").is(id))).getDeletedCount() == 1) deleted++;
    }
    return deleted;
  }
}
