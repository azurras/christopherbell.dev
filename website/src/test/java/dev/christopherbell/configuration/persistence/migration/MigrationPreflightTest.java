package dev.christopherbell.configuration.persistence.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class MigrationPreflightTest {
  private static final String CATALOG_DIGEST = "a".repeat(64);
  private static final String SOURCE_DIGEST = "b".repeat(64);
  private static final String BACKUP_DIGEST = "c".repeat(64);
  private static final UUID LOCK_TOKEN = UUID.fromString("00000000-0000-0000-0000-000000000006");

  @Test
  void validatesExactLoopbackTestIdentitiesAndBridgeRole() {
    var request = testRequest(PostgresqlMigrationCommand.SHADOW, null);
    var probes = new RecordingIdentityProbe(
        new MigrationDatabaseIdentity("127.0.0.1", 57018, "test", null),
        new MigrationDatabaseIdentity("127.0.0.1", 55432, "test", "christopherbell_test"));

    var context = new MigrationPreflight(probes).validate(request);

    assertThat(context.request()).isEqualTo(request);
    assertThat(context.sourceFrozen()).isFalse();
    assertThat(probes.sourceProbes).isOne();
    assertThat(probes.targetProbes).isOne();
  }

  @Test
  void finalizeRequiresFrozenEvidenceBoundToEveryAuthorityIdentity() {
    var absent = testRequest(PostgresqlMigrationCommand.FINALIZE, null);
    var mismatched = testRequest(
        PostgresqlMigrationCommand.FINALIZE,
        evidence("other-release"));
    var probe = validProbe();

    assertThatThrownBy(() -> new MigrationPreflight(probe).validate(absent))
        .isInstanceOf(MigrationPreflightException.class)
        .hasMessage("PostgreSQL migration preflight rejected frozen source evidence.");
    assertThatThrownBy(() -> new MigrationPreflight(probe).validate(mismatched))
        .isInstanceOf(MigrationPreflightException.class)
        .hasMessage("PostgreSQL migration preflight rejected frozen source evidence.");
    assertThat(probe.sourceProbes).isZero();
    assertThat(probe.targetProbes).isZero();

    var evidence = evidence("release-6");
    assertThat(new MigrationPreflight(validProbe()).validate(
        testRequest(PostgresqlMigrationCommand.FINALIZE, evidence)).sourceFrozen()).isTrue();
  }

  @Test
  void rejectsRemoteEndpointsProductionNamesAndOrdinaryWebsiteRoleWithoutLeakingValues() {
    var remote = new MigrationRequest(
        PostgresqlMigrationCommand.SHADOW,
        "mongodb://db.example.invalid:27017/test?password=source-secret",
        "test",
        "jdbc:postgresql://db.example.invalid:5432/test?password=target-secret",
        "test",
        "christopherbell_app",
        "cbtest_task6_",
        CATALOG_DIGEST,
        "release-6",
        1,
        LOCK_TOKEN,
        null,
        100);
    var probe = validProbe();

    assertThatThrownBy(() -> new MigrationPreflight(probe).validate(remote))
        .isInstanceOf(MigrationPreflightException.class)
        .hasMessage("PostgreSQL migration preflight rejected connection identity.")
        .hasMessageNotContaining("example.invalid")
        .hasMessageNotContaining("source-secret")
        .hasMessageNotContaining("target-secret")
        .hasMessageNotContaining("christopherbell_app");
    assertThat(probe.sourceProbes).isZero();
    assertThat(probe.targetProbes).isZero();
  }

  @Test
  void rejectsObservedIdentityDriftWithAValueFreeFailure() {
    var probe = new RecordingIdentityProbe(
        new MigrationDatabaseIdentity("127.0.0.1", 57018, "wrong", null),
        new MigrationDatabaseIdentity("127.0.0.1", 55432, "test", "christopherbell_test"));

    assertThatThrownBy(() -> new MigrationPreflight(probe).validate(
        testRequest(PostgresqlMigrationCommand.SHADOW, null)))
        .isInstanceOf(MigrationPreflightException.class)
        .hasMessage("PostgreSQL migration preflight rejected database identity.")
        .hasMessageNotContaining("wrong")
        .hasMessageNotContaining("test")
        .hasMessageNotContaining("christopherbell_test");
  }

  @Test
  void rejectsObservedPortDriftEvenWhenDatabaseAndRoleMatch() {
    var probe = new RecordingIdentityProbe(
        new MigrationDatabaseIdentity("127.0.0.1", 27017, "test", null),
        new MigrationDatabaseIdentity("127.0.0.1", 5432, "test", "christopherbell_test"));

    assertThatThrownBy(() -> new MigrationPreflight(probe).validate(
        testRequest(PostgresqlMigrationCommand.SHADOW, null)))
        .isInstanceOf(MigrationPreflightException.class)
        .hasMessage("PostgreSQL migration preflight rejected database identity.");
  }

  private static MigrationRequest testRequest(
      PostgresqlMigrationCommand command, FrozenSourceEvidence evidence) {
    return new MigrationRequest(
        command,
        "mongodb://127.0.0.1:57018/test",
        "test",
        "jdbc:postgresql://127.0.0.1:55432/test",
        "test",
        "christopherbell_test",
        "cbtest_task6_",
        CATALOG_DIGEST,
        "release-6",
        1,
        LOCK_TOKEN,
        evidence,
        100);
  }

  private static FrozenSourceEvidence evidence(String release) {
    var unsigned = new FrozenSourceEvidence(
        release, CATALOG_DIGEST, "test", "test", SOURCE_DIGEST, BACKUP_DIGEST, LOCK_TOKEN,
        "mongodb://127.0.0.1:57018/test", "jdbc:postgresql://127.0.0.1:55432/test",
        "christopherbell_test", "C:\\protected\\writer.lock", "d".repeat(64),
        "e".repeat(64));
    return new FrozenSourceEvidence(
        unsigned.release(), unsigned.catalogDigest(), unsigned.sourceDatabase(),
        unsigned.targetDatabase(), unsigned.sourceDigest(), unsigned.backupDigest(),
        unsigned.lockToken(), unsigned.sourceUri(), unsigned.targetJdbcUrl(), unsigned.targetRole(),
        unsigned.writerLockPath(), unsigned.writerLockDigest(), unsigned.reconstructedDigest());
  }

  private static RecordingIdentityProbe validProbe() {
    return new RecordingIdentityProbe(
        new MigrationDatabaseIdentity("127.0.0.1", 57018, "test", null),
        new MigrationDatabaseIdentity("127.0.0.1", 55432, "test", "christopherbell_test"));
  }

  private static final class RecordingIdentityProbe implements MigrationIdentityProbe {
    private final MigrationDatabaseIdentity source;
    private final MigrationDatabaseIdentity target;
    private int sourceProbes;
    private int targetProbes;

    private RecordingIdentityProbe(
        MigrationDatabaseIdentity source, MigrationDatabaseIdentity target) {
      this.source = source;
      this.target = target;
    }

    @Override
    public MigrationDatabaseIdentity sourceIdentity(MigrationRequest request) {
      sourceProbes++;
      return source;
    }

    @Override
    public MigrationDatabaseIdentity targetIdentity(MigrationRequest request) {
      targetProbes++;
      return target;
    }
  }
}
