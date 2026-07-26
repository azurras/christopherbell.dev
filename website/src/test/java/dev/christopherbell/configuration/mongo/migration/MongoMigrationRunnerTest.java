package dev.christopherbell.configuration.mongo.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.christopherbell.configuration.mongo.lease.MongoLeaseService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.mongodb.core.MongoTemplate;

@ExtendWith(MockitoExtension.class)
class MongoMigrationRunnerTest {
  private static final Instant NOW = Instant.parse("2026-07-25T22:30:00Z");
  private static final String CHECKSUM_001 = "checksum-001";

  @Mock private MongoTemplate mongo;
  @Mock private MigrationStateStore state;
  @Mock private MongoLeaseService leases;

  private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
  private final MigrationProperties properties = new MigrationProperties(Duration.ofMinutes(2));

  @BeforeEach
  void setUp() {
    lenient().when(leases.tryAcquire(
        eq(MigrationProperties.LEASE_NAME), anyString(), eq(NOW), eq(NOW.plusSeconds(120))))
        .thenReturn(true);
  }

  @Test
  void appliesMissingMigrationsInStableIdOrderAndRecordsCompletion() throws Exception {
    var executedIds = new ArrayList<String>();
    var migration002 = migration("002", "checksum-002", ignored -> executedIds.add("002"));
    var migration001 = migration("001", CHECKSUM_001, ignored -> executedIds.add("001"));
    when(state.find(anyString())).thenReturn(Optional.empty());

    runner(List.of(migration002, migration001)).afterPropertiesSet();

    assertThat(executedIds).containsExactly("001", "002");
    verify(state).start(eq(migration001), anyString(), eq(NOW));
    verify(state).complete(eq("001"), anyString(), eq(NOW));
    verify(leases).release(eq(MigrationProperties.LEASE_NAME), anyString());
  }

  @Test
  void appliedMigrationWithMatchingChecksumIsSkipped() throws Exception {
    var migration = migration("001", CHECKSUM_001, ignored -> {
      throw new AssertionError("applied migration must be skipped");
    });
    when(state.find("001")).thenReturn(Optional.of(record("001", CHECKSUM_001,
        MigrationStatus.APPLIED)));

    runner(List.of(migration)).afterPropertiesSet();

    verify(state, never()).start(any(), anyString(), any());
  }

  @Test
  void checksumDriftFailsStartupWithoutExecutingMigration() {
    var migration = migration("001", CHECKSUM_001, ignored -> {
      throw new AssertionError("drifted migration must not execute");
    });
    when(state.find("001")).thenReturn(Optional.of(record(
        "001", "different-checksum", MigrationStatus.APPLIED)));

    assertThatThrownBy(() -> runner(List.of(migration)).afterPropertiesSet())
        .hasMessageContaining("001", "checksum")
        .hasMessageNotContaining("different-checksum")
        .hasMessageNotContaining(CHECKSUM_001);
  }

  @Test
  void incompleteRecordFailsStartupWithoutExecutingMigration() {
    var migration = migration("001", CHECKSUM_001, ignored -> {
      throw new AssertionError("incomplete migration must not execute");
    });
    when(state.find("001"))
        .thenReturn(Optional.of(record("001", CHECKSUM_001, MigrationStatus.RUNNING)));

    assertThatThrownBy(() -> runner(List.of(migration)).afterPropertiesSet())
        .hasMessageContaining("001", "incomplete");
  }

  @Test
  void migrationFailureRecordsSafeCategoryAndReleasesLease() {
    var migration = migration("001", CHECKSUM_001, ignored -> {
      throw new IllegalStateException("database-secret");
    });
    when(state.find("001")).thenReturn(Optional.empty());

    assertThatThrownBy(() -> runner(List.of(migration)).afterPropertiesSet())
        .hasMessageContaining("001")
        .hasMessageNotContaining("database-secret");
    verify(state).fail(eq("001"), anyString(), eq(NOW), eq("MIGRATION_FAILED"));
    verify(leases).release(eq(MigrationProperties.LEASE_NAME), anyString());
  }

  @Test
  void leaseContentionFailsWithoutReadingOrExecutingMigrationState() {
    when(leases.tryAcquire(
        eq(MigrationProperties.LEASE_NAME), anyString(), eq(NOW), eq(NOW.plusSeconds(120))))
        .thenReturn(false);

    assertThatThrownBy(() -> runner(List.of(migration("001", CHECKSUM_001, ignored -> {})))
        .afterPropertiesSet())
        .hasMessageContaining("already running");
    verify(state, never()).find(anyString());
  }

  @Test
  void duplicateMigrationIdsFailBeforeLeaseAcquisition() {
    var first = migration("001", CHECKSUM_001, ignored -> {});
    var second = migration("001", "checksum-other", ignored -> {});

    assertThatThrownBy(() -> runner(List.of(first, second)).afterPropertiesSet())
        .hasMessageContaining("Duplicate migration id", "001");
  }

  private MongoMigrationRunner runner(List<ApplicationMigration> migrations) {
    return new MongoMigrationRunner(migrations, mongo, state, leases, properties, clock);
  }

  private ApplicationMigration migration(
      String id, String checksum, Consumer<MongoTemplate> action) {
    return new ApplicationMigration() {
      @Override public String id() { return id; }
      @Override public String checksum() { return checksum; }
      @Override public String description() { return "Migration " + id; }
      @Override public void apply(MongoTemplate mongoTemplate) { action.accept(mongoTemplate); }
    };
  }

  private MigrationRecord record(String id, String checksum, MigrationStatus status) {
    var record = new MigrationRecord();
    record.setId(id);
    record.setChecksum(checksum);
    record.setStatus(status);
    return record;
  }
}
