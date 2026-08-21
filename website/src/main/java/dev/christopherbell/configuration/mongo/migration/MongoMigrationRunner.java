package dev.christopherbell.configuration.mongo.migration;

import dev.christopherbell.configuration.persistence.MongoBackendComponent;
import dev.christopherbell.libs.mongo.lease.MongoLeaseService;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.data.mongodb.core.MongoTemplate;

/** Applies pending migrations in stable order while holding the global migration lease. */
@MongoBackendComponent
@EnableConfigurationProperties(MigrationProperties.class)
public class MongoMigrationRunner implements InitializingBean {
  private final List<ApplicationMigration> migrations;
  private final MongoTemplate mongo;
  private final MigrationStateStore state;
  private final MongoLeaseService leases;
  private final MigrationProperties properties;
  private final Clock clock;
  private final DomainCollectionStartupPreflight preflight;

  public MongoMigrationRunner(
      List<ApplicationMigration> migrations,
      MongoTemplate mongo,
      MigrationStateStore state,
      MongoLeaseService leases,
      MigrationProperties properties,
      Clock clock,
      DomainCollectionStartupPreflight preflight) {
    this.migrations = orderedAndUnique(migrations);
    this.mongo = mongo;
    this.state = state;
    this.leases = leases;
    this.properties = properties;
    this.clock = clock;
    this.preflight = preflight;
  }

  @Override
  public void afterPropertiesSet() {
    preflight.requireReady();
    var ownerToken = UUID.randomUUID().toString();
    var now = Instant.now(clock);
    var expiresAt = now.plus(properties.leaseDuration());
    if (!leases.tryAcquire(MigrationProperties.LEASE_NAME, ownerToken, now, expiresAt)) {
      throw new IllegalStateException("Application migrations are already running.");
    }

    try {
      for (var migration : migrations) {
        applyIfPending(migration, ownerToken, now);
      }
    } finally {
      leases.release(MigrationProperties.LEASE_NAME, ownerToken);
    }
  }

  private void applyIfPending(
      ApplicationMigration migration, String ownerToken, Instant timestamp) {
    var existing = state.find(migration.id());
    if (existing.isPresent()) {
      var record = existing.orElseThrow();
      if (!migration.checksum().equals(record.getChecksum())) {
        throw new IllegalStateException(
            "Migration " + migration.id() + " checksum does not match its durable record.");
      }
      if (record.getStatus() == MigrationStatus.APPLIED) {
        return;
      }
      throw new IllegalStateException(
          "Migration " + migration.id() + " has an incomplete durable record.");
    }

    state.start(migration, ownerToken, timestamp);
    try {
      migration.apply(mongo);
      state.complete(migration.id(), ownerToken, Instant.now(clock));
    } catch (RuntimeException failure) {
      try {
        state.fail(migration.id(), ownerToken, Instant.now(clock), "MIGRATION_FAILED");
      } catch (RuntimeException ignored) {
        // The startup failure below remains intentionally redacted.
      }
      throw new IllegalStateException("Migration " + migration.id() + " failed.");
    }
  }

  private static List<ApplicationMigration> orderedAndUnique(
      List<ApplicationMigration> migrations) {
    var ordered = new ArrayList<>(migrations);
    ordered.sort(Comparator.comparing(ApplicationMigration::id));
    var ids = new HashSet<String>();
    for (var migration : ordered) {
      if (!ids.add(migration.id())) {
        throw new IllegalArgumentException("Duplicate migration id: " + migration.id());
      }
    }
    return List.copyOf(ordered);
  }
}
