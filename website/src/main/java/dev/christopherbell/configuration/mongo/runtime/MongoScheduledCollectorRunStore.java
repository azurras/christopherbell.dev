package dev.christopherbell.configuration.mongo.runtime;

import dev.christopherbell.configuration.mongo.domain.DomainMongoOperationsFactory;
import dev.christopherbell.configuration.mongo.domain.KindScopedMongoOperations;
import dev.christopherbell.libs.mongo.lease.ScheduledCollectorRun;
import dev.christopherbell.libs.mongo.lease.ScheduledCollectorRunStore;
import org.springframework.stereotype.Repository;

/** Kind-scoped durable collector history adapter. */
@Repository
public class MongoScheduledCollectorRunStore implements ScheduledCollectorRunStore {
  private final KindScopedMongoOperations<ScheduledCollectorRun> mongo;

  public MongoScheduledCollectorRunStore(DomainMongoOperationsFactory factory) {
    this.mongo = factory.forType(ScheduledCollectorRun.class);
  }

  @Override
  public ScheduledCollectorRun save(ScheduledCollectorRun run) {
    return mongo.save(run);
  }
}
