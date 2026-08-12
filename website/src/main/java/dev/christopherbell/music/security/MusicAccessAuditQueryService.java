package dev.christopherbell.music.security;

import dev.christopherbell.configuration.mongo.domain.DomainMongoOperationsFactory;
import dev.christopherbell.configuration.mongo.domain.KindScopedMongoOperations;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

/** Returns a bounded newest-first view of denied Music entry attempts for administrators. */
@Service
public final class MusicAccessAuditQueryService {
  private final KindScopedMongoOperations<MusicAccessAttempt> attempts;

  public MusicAccessAuditQueryService(DomainMongoOperationsFactory factory) {
    this.attempts = factory.forType(MusicAccessAttempt.class);
  }

  public List<MusicAccessAttempt> recent(int requestedLimit) {
    int limit = Math.max(1, Math.min(100, requestedLimit));
    Query query = new Query()
        .with(Sort.by(Sort.Direction.DESC, "lastAttemptAt"))
        .limit(limit);
    return attempts.find(query, Pageable.unpaged());
  }
}
