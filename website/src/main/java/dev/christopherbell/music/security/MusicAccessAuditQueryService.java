package dev.christopherbell.music.security;

import java.util.List;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

/** Returns a bounded newest-first view of denied Music entry attempts for administrators. */
@Service
public final class MusicAccessAuditQueryService {
  private final MongoTemplate mongo;

  public MusicAccessAuditQueryService(MongoTemplate mongo) {
    this.mongo = mongo;
  }

  public List<MusicAccessAttempt> recent(int requestedLimit) {
    int limit = Math.max(1, Math.min(100, requestedLimit));
    Query query = new Query()
        .with(Sort.by(Sort.Direction.DESC, "lastAttemptAt"))
        .limit(limit);
    return List.copyOf(mongo.find(query, MusicAccessAttempt.class));
  }
}
