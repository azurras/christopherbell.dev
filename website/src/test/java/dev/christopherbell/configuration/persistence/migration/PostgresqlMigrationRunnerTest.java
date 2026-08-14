package dev.christopherbell.configuration.persistence.migration;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PostgresqlMigrationRunnerTest {
  @Test
  void shadowReconcilesWithoutPublishingWhileFinalizePublishes() {
    var shadowTarget = new RecordingTarget();
    runner(shadowTarget).run(request(PostgresqlMigrationCommand.SHADOW));
    assertThat(shadowTarget.reconciliations).isOne();
    assertThat(shadowTarget.publications).isZero();

    var finalizeTarget = new RecordingTarget();
    runner(finalizeTarget).run(request(PostgresqlMigrationCommand.FINALIZE));
    assertThat(finalizeTarget.reconciliations).isOne();
    assertThat(finalizeTarget.publications).isOne();
  }

  @Test
  void reconcileRejectsAMissingKindInsteadOfReportingSuccess() {
    var target = new StatusTarget(List.of());

    assertThatThrownBy(() -> runner(target).run(request(PostgresqlMigrationCommand.RECONCILE)))
        .isInstanceOf(MigrationReconciliationException.class);
  }

  @Test
  void reconcileRejectsAnIncompleteKindInsteadOfReportingSuccess() {
    var target = new StatusTarget(List.of(new MigrationKindStatus(
        "fixture", MigrationCheckpoint.initial(), 0, false)));

    assertThatThrownBy(() -> runner(target).run(request(PostgresqlMigrationCommand.RECONCILE)))
        .isInstanceOf(MigrationReconciliationException.class);
  }

  private static PostgresqlMigrationRunner runner(MigrationTargetStore target) {
    var kind = kind();
    var catalog = new PostgresqlMigrationCatalog(1, List.of(kind));
    var preflight = new MigrationPreflight(new MigrationIdentityProbe() {
      @Override
      public MigrationDatabaseIdentity sourceIdentity(MigrationRequest request) {
        return new MigrationDatabaseIdentity("127.0.0.1", 57018, "test", null);
      }

      @Override
      public MigrationDatabaseIdentity targetIdentity(MigrationRequest request) {
        return new MigrationDatabaseIdentity(
            "127.0.0.1", 55432, "test", "christopherbell_test");
      }
    });
    var engine = new KindMigrationEngine(
        (context, item, cursor, limit) -> SourceBatch.of(List.of()),
        target,
        ignored -> new KindMigrationEngineTest.StubTransformer());
    return new PostgresqlMigrationRunner(
        preflight, catalog, engine, new MigrationReconciler(target), target);
  }

  private static PostgresqlMigrationCatalog.Kind kind() {
    return new PostgresqlMigrationCatalog.Kind(
        "configuration", "fixture", 1, 1, "string", "platform", List.of("fixture"),
        1,
        List.of(),
        new PostgresqlMigrationCatalog.KeyMapping("id", "fixture.id", "exact"),
        Map.of(
            "id",
            new PostgresqlMigrationCatalog.FieldMapping(
                List.of("fixture.id"), "string", "reject", "reject")),
        "preserve",
        "none",
        "none",
        "sha256-rfc8785-v1",
        List.of("count"),
        List.of("by-id"),
        AccountTransformer.class.getName());
  }

  private static MigrationRequest request(PostgresqlMigrationCommand command) {
    FrozenSourceEvidence evidence = null;
    if (command == PostgresqlMigrationCommand.FINALIZE) {
      var unsigned = new FrozenSourceEvidence(
          "release-6", "a".repeat(64), "test", "test", "b".repeat(64), "c".repeat(64),
          UUID.fromString("00000000-0000-0000-0000-000000000016"),
          "mongodb://127.0.0.1:57018/test", "jdbc:postgresql://127.0.0.1:55432/test",
          "christopherbell_test", "d".repeat(64), "e".repeat(64));
      evidence = new FrozenSourceEvidence(
          unsigned.release(), unsigned.catalogDigest(), unsigned.sourceDatabase(),
          unsigned.targetDatabase(), unsigned.sourceDigest(), unsigned.backupDigest(),
          unsigned.lockToken(), unsigned.sourceUri(), unsigned.targetJdbcUrl(),
          unsigned.targetRole(), unsigned.writerLockDigest(), unsigned.reconstructedDigest());
    }
    return new MigrationRequest(
        command,
        "mongodb://127.0.0.1:57018/test",
        "test",
        "jdbc:postgresql://127.0.0.1:55432/test",
        "test",
        "christopherbell_test",
        "cbtest_task6_fix1_",
        "a".repeat(64),
        "release-6",
        UUID.fromString("00000000-0000-0000-0000-000000000016"),
        evidence,
        100);
  }

  private static final class RecordingTarget implements MigrationTargetStore {
    private MigrationCheckpoint checkpoint = MigrationCheckpoint.initial();
    private int reconciliations;
    private int publications;

    @Override
    public MigrationCheckpoint checkpoint(
        ValidatedMigrationContext context, PostgresqlMigrationCatalog.Kind kind) {
      return checkpoint;
    }

    @Override
    public MigrationCheckpoint commitBatch(
        ValidatedMigrationContext context,
        PostgresqlMigrationCatalog.Kind kind,
        MigrationCheckpoint expected,
        List<TransformedMigrationDocument> documents,
        String nextCursor) {
      throw new AssertionError("empty fixture must not commit a batch");
    }

    @Override
    public MigrationCheckpoint completeStaging(
        ValidatedMigrationContext context,
        PostgresqlMigrationCatalog.Kind kind,
        MigrationCheckpoint expected) {
      checkpoint = expected.markComplete();
      return checkpoint;
    }

    @Override
    public MigrationReconciliation reconcile(
        ValidatedMigrationContext context, PostgresqlMigrationCatalog.Kind kind) {
      reconciliations++;
      return new MigrationReconciliation(
          checkpoint.complete(), 0, 0, checkpoint.sourceDigest(), checkpoint.sourceDigest(),
          true, true);
    }

    @Override
    public void publish(
        ValidatedMigrationContext context,
        PostgresqlMigrationCatalog.Kind kind,
        MigrationReconciliation reconciliation) {
      publications++;
    }

    @Override
    public List<MigrationKindStatus> statuses(ValidatedMigrationContext context) {
      return List.of(new MigrationKindStatus("fixture", checkpoint, publications, publications > 0));
    }
  }

  private static final class StatusTarget implements MigrationTargetStore {
    private final List<MigrationKindStatus> statuses;

    private StatusTarget(List<MigrationKindStatus> statuses) {
      this.statuses = statuses;
    }

    @Override
    public MigrationCheckpoint checkpoint(
        ValidatedMigrationContext context, PostgresqlMigrationCatalog.Kind kind) {
      throw new AssertionError("reconcile must not stage");
    }

    @Override
    public MigrationCheckpoint commitBatch(
        ValidatedMigrationContext context,
        PostgresqlMigrationCatalog.Kind kind,
        MigrationCheckpoint expected,
        List<TransformedMigrationDocument> documents,
        String nextCursor) {
      throw new AssertionError("reconcile must not stage");
    }

    @Override
    public MigrationCheckpoint completeStaging(
        ValidatedMigrationContext context,
        PostgresqlMigrationCatalog.Kind kind,
        MigrationCheckpoint expected) {
      throw new AssertionError("reconcile must not stage");
    }

    @Override
    public MigrationReconciliation reconcile(
        ValidatedMigrationContext context, PostgresqlMigrationCatalog.Kind kind) {
      throw new AssertionError("missing or incomplete kind must fail before reconciliation");
    }

    @Override
    public void publish(
        ValidatedMigrationContext context,
        PostgresqlMigrationCatalog.Kind kind,
        MigrationReconciliation reconciliation) {
      throw new AssertionError("reconcile must not publish");
    }

    @Override
    public List<MigrationKindStatus> statuses(ValidatedMigrationContext context) {
      return statuses;
    }
  }
}
