package dev.christopherbell.configuration.mongo.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.christopherbell.libs.mongo.lease.MongoLeaseService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.IndexOperations;

@ExtendWith(MockitoExtension.class)
class V001EnsureMigrationInfrastructureTest {
  private static final String EXPECTED_CHECKSUM =
      "aec77e3e8cf68bf8d67f239ee0e842fbdad26ea9766ab04cbc3d74dd9ad93876";

  @Mock private MongoTemplate mongo;
  @Mock private IndexOperations migrationIndexes;
  @Mock private IndexOperations leaseIndexes;

  private V001EnsureMigrationInfrastructure migration;

  @BeforeEach
  void setUp() {
    migration = new V001EnsureMigrationInfrastructure();
  }

  @Test
  void identityAndChecksumAreImmutableReviewedValues() {
    assertThat(migration.id()).isEqualTo("001-ensure-migration-infrastructure");
    assertThat(migration.checksum()).isEqualTo(EXPECTED_CHECKSUM);
    assertThat(migration.description()).isNotBlank();
  }

  @Test
  void applyEnsuresNamedInfrastructureIndexesIdempotently() {
    when(mongo.indexOps("application_migrations")).thenReturn(migrationIndexes);
    when(mongo.indexOps("application_leases")).thenReturn(leaseIndexes);

    migration.apply(mongo);
    migration.apply(mongo);

    verify(migrationIndexes, times(2)).createIndex(argThat(index ->
        "migration_status_completed".equals(index.getIndexOptions().get("name"))
            && index.getIndexKeys().containsKey("status")
            && index.getIndexKeys().containsKey("completedAt")));
    verify(leaseIndexes, times(2)).createIndex(argThat(index ->
        "lease_expiry".equals(index.getIndexOptions().get("name"))
            && index.getIndexKeys().containsKey("expiresAt")));
  }
}
