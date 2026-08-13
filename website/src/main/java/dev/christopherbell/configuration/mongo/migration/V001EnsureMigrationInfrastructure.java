package dev.christopherbell.configuration.mongo.migration;

import dev.christopherbell.configuration.persistence.MongoPersistence;

import dev.christopherbell.libs.mongo.lease.MongoLeaseService;
import org.springframework.data.domain.Sort.Direction;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.stereotype.Component;

/** Creates the named indexes used by the migration runner and shared lease service. */
@MongoPersistence
@Component
public final class V001EnsureMigrationInfrastructure implements ApplicationMigration {
  private static final String CHECKSUM =
      "aec77e3e8cf68bf8d67f239ee0e842fbdad26ea9766ab04cbc3d74dd9ad93876";

  @Override
  public String id() {
    return "001-ensure-migration-infrastructure";
  }

  @Override
  public String checksum() {
    return CHECKSUM;
  }

  @Override
  public String description() {
    return "Ensure migration-status and lease-expiry indexes";
  }

  @Override
  public void apply(MongoTemplate mongo) {
    mongo.indexOps("application_migrations").createIndex(new Index()
        .on("status", Direction.ASC)
        .on("completedAt", Direction.DESC)
        .named("migration_status_completed"));
    mongo.indexOps("application_leases").createIndex(new Index()
        .on("expiresAt", Direction.ASC)
        .named("lease_expiry"));
  }
}
