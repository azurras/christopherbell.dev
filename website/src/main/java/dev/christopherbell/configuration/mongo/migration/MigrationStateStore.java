package dev.christopherbell.configuration.mongo.migration;

import dev.christopherbell.configuration.mongo.domain.DomainMongoOperationsFactory;
import dev.christopherbell.configuration.mongo.domain.KindScopedMongoOperations;
import java.time.Instant;
import java.util.Optional;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Repository;

/** Persists owner-scoped migration lifecycle transitions. */
@Repository
public class MigrationStateStore {
  private final KindScopedMongoOperations<MigrationRecord> mongo;

  public MigrationStateStore(DomainMongoOperationsFactory factory) {
    this.mongo = factory.forType(MigrationRecord.class);
  }

  MigrationStateStore(KindScopedMongoOperations<MigrationRecord> mongo) {
    this.mongo = mongo;
  }

  public Optional<MigrationRecord> find(String id) {
    return mongo.findById(id);
  }

  public void start(ApplicationMigration migration, String ownerToken, Instant startedAt) {
    mongo.insert(MigrationRecord.running(migration, ownerToken, startedAt));
  }

  public void complete(String id, String ownerToken, Instant completedAt) {
    transition(id, ownerToken, new Update()
        .set("status", MigrationStatus.APPLIED)
        .set("completedAt", completedAt)
        .unset("failureCategory"));
  }

  public void fail(
      String id, String ownerToken, Instant completedAt, String failureCategory) {
    transition(id, ownerToken, new Update()
        .set("status", MigrationStatus.FAILED)
        .set("completedAt", completedAt)
        .set("failureCategory", failureCategory));
  }

  private void transition(String id, String ownerToken, Update update) {
    var query = Query.query(Criteria.where("id").is(id)
        .and("ownerToken").is(ownerToken)
        .and("status").is(MigrationStatus.RUNNING));
    var result = mongo.updateFirst(query, update);
    if (result.getMatchedCount() != 1) {
      throw new IllegalStateException("Migration state ownership was lost for " + id + ".");
    }
  }
}
