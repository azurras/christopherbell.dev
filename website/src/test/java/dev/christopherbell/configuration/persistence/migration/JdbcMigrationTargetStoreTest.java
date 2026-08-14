package dev.christopherbell.configuration.persistence.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

@EnabledIfEnvironmentVariable(named = "POSTGRESQL_INTEGRATION_TESTS", matches = "enabled")
class JdbcMigrationTargetStoreTest {
  @Test
  void everyCatalogChildTableCarriesTheFrozenSourceIdentity() throws Exception {
    try (var database = PostgresqlSchemaTestSupport.migrate();
         var connection = database.connect()) {
      for (var kind : loadCatalog().kinds()) {
        var rootKey = kind.keyMapping().targetColumn();
        rootKey = rootKey.substring(rootKey.indexOf('.') + 1);
        var schema = database.prefix() + kind.targetSchema();
        for (var child : kind.targetTables().stream().skip(1).toList()) {
          var found = false;
          try (var keys = connection.getMetaData().getImportedKeys(null, schema, child)) {
            while (keys.next()) {
              found |= schema.equals(keys.getString("PKTABLE_SCHEM"))
                  && kind.targetTables().contains(keys.getString("PKTABLE_NAME"))
                  && rootKey.equals(keys.getString("PKCOLUMN_NAME"));
            }
          }
          assertThat(found).as(kind.sourceKind() + ":" + child).isTrue();
        }
      }
    }
  }

  @Test
  void statusOfAnAbsentRunDoesNotCreateLedgerState() throws Exception {
    try (var database = PostgresqlSchemaTestSupport.migrate()) {
      var target = new JdbcMigrationTargetStore(
          dataSource(database), new JdbcRelationalRowPublisher());

      assertThat(target.statuses(context(database))).isEmpty();
      assertThat(count(database, "persistence_migration_run")).isZero();
      assertThat(count(database, "persistence_migration_kind")).isZero();
    }
  }

  @Test
  void mutationBoundaryRejectsShadowBeforeOpeningAFinalizeTransaction() throws Exception {
    try (var database = PostgresqlSchemaTestSupport.migrate()) {
      var kind = applicationLeaseKind();
      var target = new JdbcMigrationTargetStore(
          dataSource(database), new JdbcRelationalRowPublisher());
      var shadow = shadowContext(database);
      var reconciliation = new MigrationReconciliation(
          true, 0, 0, MigrationCheckpoint.initial().sourceDigest(),
          MigrationCheckpoint.initial().sourceDigest(), true, true);

      assertThatThrownBy(() -> target.finalizeRun(
          shadow, List.of(kind), List.of(reconciliation)))
          .isInstanceOf(MigrationReconciliationException.class);
      assertThat(count(database, "persistence_migration_run")).isZero();
    }
  }

  @Test
  void typedPublisherSuppliesTheImplicitSameKindChildForeignKey() throws Exception {
    try (var database = PostgresqlSchemaTestSupport.migrate();
         var connection = database.connect()) {
      var kind = loadCatalog().kinds().stream()
          .filter(candidate -> candidate.sourceKind().equals("vin_decode_cache"))
          .findFirst().orElseThrow();
      var rows = List.of(
          new StagedMigrationRow(
              "JM1BN1L30K1234567", "mobility", "vin_decode_cache", 0,
              Map.of(
                  "vin", "JM1BN1L30K1234567",
                  "response_present", true,
                  "raw_decoded_values_present", true)),
          new StagedMigrationRow(
              "JM1BN1L30K1234567", "mobility", "vin_decode_raw_value", 0,
              Map.of("field_name", "Make", "field_value", "Mazda")));

      new JdbcRelationalRowPublisher().publish(connection, database.prefix(), kind, rows);

      try (var statement = connection.createStatement();
           var result = statement.executeQuery(
               "select vin, field_name, field_value from \"" + database.prefix()
                   + "mobility\".vin_decode_raw_value")) {
        assertThat(result.next()).isTrue();
        assertThat(List.of(result.getString(1), result.getString(2), result.getString(3)))
            .containsExactly("JM1BN1L30K1234567", "Make", "Mazda");
      }
    }
  }

  @Test
  void failedBatchAndFailedPublicationRollbackThenResumeToExactTypedRows() throws Exception {
    try (var database = PostgresqlSchemaTestSupport.migrate()) {
      var context = context(database);
      var kind = applicationLeaseKind();
      var transformer = MigrationTransformerRegistry.from(loadCatalog()).require(kind.sourceKind());
      var first = transformer.transform(source("lease-a", 1));
      var second = transformer.transform(source("lease-b", 2));
      var dataSource = dataSource(database);
      var target = new JdbcMigrationTargetStore(dataSource, new JdbcRelationalRowPublisher());
      var initial = target.checkpoint(context, kind);

      assertThatThrownBy(() -> target.commitBatch(
          context, kind, initial, List.of(first, first), "duplicate"))
          .isInstanceOf(MigrationStorageException.class)
          .hasMessage("PostgreSQL migration target operation failed.");
      assertThat(target.checkpoint(context, kind)).isEqualTo(initial);
      assertThat(count(database, "persistence_migration_source")).isZero();

      var staged = target.commitBatch(context, kind, initial, List.of(first, second), "lease-b");
      var complete = target.completeStaging(context, kind, staged);
      var reconciliation = target.reconcile(context, kind);
      assertThat(reconciliation.equivalent()).isTrue();
      assertThat(complete.sourceCount()).isEqualTo(2);

      var failingTarget = new JdbcMigrationTargetStore(dataSource, (connection, prefix, item, rows) -> {
        new JdbcRelationalRowPublisher().publish(connection, prefix, item, rows);
        throw new SQLException("injected after typed inserts");
      });
      assertThatThrownBy(() -> failingTarget.finalizeRun(
          context, List.of(kind), List.of(reconciliation)))
          .isInstanceOf(MigrationStorageException.class);
      assertThat(count(database, "application_lease")).isZero();
      assertThat(target.statuses(context).getFirst().published()).isFalse();

      target.finalizeRun(context, List.of(kind), List.of(reconciliation));
      target.finalizeRun(context, List.of(kind), List.of(reconciliation));

      assertThat(count(database, "application_lease")).isEqualTo(2);
      assertThat(target.statuses(context).getFirst().published()).isTrue();
      assertThat(target.statuses(context).getFirst().publishedCount()).isEqualTo(2);
    }
  }

  @Test
  void reconciliationRehashesDecodedRowsAndPublicationVerifiesTheTypedTarget() throws Exception {
    try (var database = PostgresqlSchemaTestSupport.migrate()) {
      var context = context(database);
      var kind = applicationLeaseKind();
      var transformed = MigrationTransformerRegistry.from(loadCatalog())
          .require(kind.sourceKind()).transform(source("lease-a", 1));
      var target = new JdbcMigrationTargetStore(
          dataSource(database), new JdbcRelationalRowPublisher());
      var staged = target.commitBatch(
          context, kind, target.checkpoint(context, kind), List.of(transformed), "lease-a");
      target.completeStaging(context, kind, staged);
      var originalRowHash = scalar(database, "select row_hash from \"" + database.prefix()
          + "platform\".persistence_migration_staged_row");
      execute(database, "update \"" + database.prefix()
          + "platform\".persistence_migration_staged_row set row_hash='"
          + "0".repeat(64) + "'");

      assertThat(target.reconcile(context, kind).equivalent()).isFalse();

      execute(database, "update \"" + database.prefix()
          + "platform\".persistence_migration_staged_row set row_hash='" + originalRowHash + "'");
      var noOpPublisher = new JdbcMigrationTargetStore(dataSource(database),
          (connection, prefix, item, rows) -> {});
      var supplied = target.reconcile(context, kind);
      assertThat(supplied.equivalent()).isTrue();
      assertThatThrownBy(() -> noOpPublisher.finalizeRun(
          context, List.of(kind), List.of(supplied)))
          .isInstanceOf(MigrationReconciliationException.class);
      assertThat(count(database, "application_lease")).isZero();
    }
  }

  @Test
  void sourceSnapshotRejectsTamperedPayloadEvenWhenItsRowHashWasRecomputed() throws Exception {
    try (var database = PostgresqlSchemaTestSupport.migrate()) {
      var context = context(database);
      var kind = applicationLeaseKind();
      var transformer = MigrationTransformerRegistry.from(loadCatalog()).require(kind.sourceKind());
      var expected = transformer.transform(source("lease-a", 1));
      var target = new JdbcMigrationTargetStore(
          dataSource(database), new JdbcRelationalRowPublisher());
      var staged = target.commitBatch(
          context, kind, target.checkpoint(context, kind), List.of(expected), "lease-a");
      target.completeStaging(context, kind, staged);

      var tamperedRow = transformer.transform(source("lease-a", 99)).rows().getFirst();
      var tamperedHash = CanonicalMigrationHasher.sha256(List.of(
          tamperedRow.targetSchema(), tamperedRow.targetTable(), tamperedRow.ordinal(),
          tamperedRow.values()));
      try (var connection = database.connect();
           var statement = connection.prepareStatement("update \"" + database.prefix()
               + "platform\".persistence_migration_staged_row set row_payload=?, row_hash=?")) {
        statement.setBytes(1, new MigrationRowCodec().encode(tamperedRow.values()));
        statement.setString(2, tamperedHash);
        assertThat(statement.executeUpdate()).isEqualTo(1);
      }

      assertThat(target.reconcile(context, kind).equivalent()).isTrue();
      assertThatThrownBy(() -> target.requireStagedDocuments(context, kind, List.of(expected)))
          .isInstanceOf(MigrationReconciliationException.class);
    }
  }

  @Test
  void distinctFrozenRunsUpsertChangedRowsAndDeleteOnlyTheFinalDelta() throws Exception {
    try (var database = PostgresqlSchemaTestSupport.migrate()) {
      var kind = applicationLeaseKind();
      var transformer = MigrationTransformerRegistry.from(loadCatalog()).require(kind.sourceKind());
      var target = new JdbcMigrationTargetStore(
          dataSource(database), new JdbcRelationalRowPublisher());
      var firstContext = context(database,
          UUID.fromString("00000000-0000-0000-0000-000000000611"));
      var first = target.commitBatch(firstContext, kind, target.checkpoint(firstContext, kind),
          List.of(transformer.transform(source("lease-a", 1)),
              transformer.transform(source("lease-b", 2))), "lease-b");
      target.completeStaging(firstContext, kind, first);
      var firstReconciliation = target.reconcile(firstContext, kind);
      target.finalizeRun(firstContext, List.of(kind), List.of(firstReconciliation));

      var secondContext = context(database,
          UUID.fromString("00000000-0000-0000-0000-000000000612"));
      var second = target.commitBatch(secondContext, kind, target.checkpoint(secondContext, kind),
          List.of(transformer.transform(source("lease-b", 20)),
              transformer.transform(source("lease-c", 3))), "lease-c");
      target.completeStaging(secondContext, kind, second);
      target.finalizeRun(
          secondContext, List.of(kind), List.of(target.reconcile(secondContext, kind)));

      assertThat(leases(database)).containsExactly(
          List.of("lease-b", 20L), List.of("lease-c", 3L));
    }
  }

  @Test
  void publicationStreamsScaleRowsInBoundedPreparedBatches() throws Exception {
    try (var database = PostgresqlSchemaTestSupport.migrate()) {
      var kind = applicationLeaseKind();
      var transformer = MigrationTransformerRegistry.from(loadCatalog()).require(kind.sourceKind());
      var documents = new java.util.ArrayList<TransformedMigrationDocument>();
      for (var index = 0; index < 201; index++) {
        documents.add(transformer.transform(source("lease-%04d".formatted(index), index + 1L)));
      }
      var delegate = new JdbcRelationalRowPublisher();
      var calls = new int[1];
      var largestBatch = new int[1];
      MigrationRowPublisher counting = (connection, prefix, item, rows) -> {
        calls[0]++;
        largestBatch[0] = Math.max(largestBatch[0], rows.size());
        delegate.publish(connection, prefix, item, rows);
      };
      var target = new JdbcMigrationTargetStore(dataSource(database), counting);
      var context = context(
          database, UUID.fromString("00000000-0000-0000-0000-000000000613"), 100);
      var staged = target.commitBatch(
          context, kind, target.checkpoint(context, kind), documents, "lease-0200");
      target.completeStaging(context, kind, staged);
      target.finalizeRun(
          context, List.of(kind), List.of(target.reconcile(context, kind)));

      assertThat(calls[0]).isEqualTo(3);
      assertThat(largestBatch[0]).isEqualTo(100);
      assertThat(count(database, "application_lease")).isEqualTo(201);
    }
  }

  @Test
  void distinctRunRemovesNestedRowsMissingFromTheFrozenDocument() throws Exception {
    try (var database = PostgresqlSchemaTestSupport.migrate()) {
      var kind = loadCatalog().kinds().stream()
          .filter(candidate -> candidate.sourceKind().equals("vin_decode_cache"))
          .findFirst().orElseThrow();
      var transformer = MigrationTransformerRegistry.from(loadCatalog()).require(kind.sourceKind());
      var target = new JdbcMigrationTargetStore(
          dataSource(database), new JdbcRelationalRowPublisher());
      var firstContext = context(database,
          UUID.fromString("00000000-0000-0000-0000-000000000614"));
      var first = target.commitBatch(firstContext, kind, target.checkpoint(firstContext, kind),
          List.of(transformer.transform(vinSource(Map.of("A", "one", "B", "two")))), "vin");
      target.completeStaging(firstContext, kind, first);
      target.finalizeRun(
          firstContext, List.of(kind), List.of(target.reconcile(firstContext, kind)));

      var secondContext = context(database,
          UUID.fromString("00000000-0000-0000-0000-000000000615"));
      var second = target.commitBatch(secondContext, kind, target.checkpoint(secondContext, kind),
          List.of(transformer.transform(vinSource(Map.of("B", "updated")))), "vin");
      target.completeStaging(secondContext, kind, second);
      target.finalizeRun(
          secondContext, List.of(kind), List.of(target.reconcile(secondContext, kind)));

      assertThat(rawVinValues(database)).containsExactly(List.of("B", "updated"));
    }
  }

  @Test
  void distinctRunPresentEmptyVinResponseClearsEveryStaleOptionalRootValue() throws Exception {
    try (var database = PostgresqlSchemaTestSupport.migrate()) {
      var kind = loadCatalog().kinds().stream()
          .filter(candidate -> candidate.sourceKind().equals("vin_decode_cache"))
          .findFirst().orElseThrow();
      var transformer = MigrationTransformerRegistry.from(loadCatalog()).require(kind.sourceKind());
      var target = new JdbcMigrationTargetStore(
          dataSource(database), new JdbcRelationalRowPublisher());
      var firstContext = context(database,
          UUID.fromString("00000000-0000-0000-0000-000000000618"));
      var first = target.commitBatch(firstContext, kind, target.checkpoint(firstContext, kind),
          List.of(transformer.transform(vinSource(Map.of("Make", "Mazda")))), "vin");
      target.completeStaging(firstContext, kind, first);
      target.finalizeRun(
          firstContext, List.of(kind), List.of(target.reconcile(firstContext, kind)));

      var secondContext = context(database,
          UUID.fromString("00000000-0000-0000-0000-000000000619"));
      var presentEmpty = new MigrationSourceDocument(
          "vin_decode_cache", 1, "JM1BN1L30K1234567", Map.of("response", Map.of()));
      var second = target.commitBatch(
          secondContext, kind, target.checkpoint(secondContext, kind),
          List.of(transformer.transform(presentEmpty)), "vin");
      target.completeStaging(secondContext, kind, second);
      target.finalizeRun(
          secondContext, List.of(kind), List.of(target.reconcile(secondContext, kind)));

      try (var connection = database.connect();
           var statement = connection.createStatement();
           var rows = statement.executeQuery("select response_present, "
               + "raw_decoded_values_present, make, model, model_year, response_vin from \""
               + database.prefix() + "mobility\".vin_decode_cache where vin="
               + "'JM1BN1L30K1234567'")) {
        assertThat(rows.next()).isTrue();
        assertThat(rows.getBoolean(1)).isTrue();
        assertThat(rows.getBoolean(2)).isFalse();
        assertThat(rows.getObject(3)).isNull();
        assertThat(rows.getObject(4)).isNull();
        assertThat(rows.getObject(5)).isNull();
        assertThat(rows.getObject(6)).isNull();
        assertThat(rows.next()).isFalse();
      }
    }
  }

  @Test
  void authenticatedReplayRepairsTypedTargetDriftBeforeKeepingPublishedState() throws Exception {
    try (var database = PostgresqlSchemaTestSupport.migrate()) {
      var context = context(database,
          UUID.fromString("00000000-0000-0000-0000-000000000620"));
      var kind = applicationLeaseKind();
      var transformed = MigrationTransformerRegistry.from(loadCatalog())
          .require(kind.sourceKind()).transform(source("lease-replay", 17));
      var target = new JdbcMigrationTargetStore(
          dataSource(database), new JdbcRelationalRowPublisher());
      var staged = target.commitBatch(
          context, kind, target.checkpoint(context, kind), List.of(transformed), "lease-replay");
      target.completeStaging(context, kind, staged);
      var reconciliation = target.reconcile(context, kind);
      target.finalizeRun(context, List.of(kind), List.of(reconciliation));
      execute(database, "update \"" + database.prefix()
          + "platform\".application_lease set fence_token=999 where lease_name='lease-replay'");

      target.finalizeRun(context, List.of(kind), List.of(reconciliation));

      assertThat(leases(database)).containsExactly(List.of("lease-replay", 17L));
    }
  }

  @Test
  void allKindFinalizeDeletesReferencingPostBeforeItsRemovedAccount() throws Exception {
    try (var database = PostgresqlSchemaTestSupport.migrate()) {
      var catalog = loadCatalog();
      var account = catalog.kinds().stream()
          .filter(kind -> kind.sourceKind().equals("account")).findFirst().orElseThrow();
      var post = catalog.kinds().stream()
          .filter(kind -> kind.sourceKind().equals("post")).findFirst().orElseThrow();
      var transformers = MigrationTransformerRegistry.from(catalog);
      var target = new JdbcMigrationTargetStore(
          dataSource(database), new JdbcRelationalRowPublisher());
      var first = context(database, UUID.fromString("00000000-0000-0000-0000-000000000616"));
      var accountDocument = transformers.require("account").transform(new MigrationSourceDocument(
          "account", 1, "account-a", Map.of(
              "email", "account-a@example.test", "role", "USER", "status", "ACTIVE",
              "username", "account-a")));
      var postDocument = transformers.require("post").transform(new MigrationSourceDocument(
          "post", 1, "post-a", Map.of(
              "accountId", "account-a", "text", "post", "rootId", "post-a",
              "createdOn", Instant.parse("2026-08-14T00:00:00Z"))));
      var accountStage = target.commitBatch(
          first, account, target.checkpoint(first, account), List.of(accountDocument), "account-a");
      target.completeStaging(first, account, accountStage);
      var postStage = target.commitBatch(
          first, post, target.checkpoint(first, post), List.of(postDocument), "post-a");
      target.completeStaging(first, post, postStage);
      target.finalizeRun(
          first, List.of(account, post), List.of(
              target.reconcile(first, account), target.reconcile(first, post)));
      assertThat(domainCount(database, "identity", "account")).isOne();
      assertThat(domainCount(database, "social", "post")).isOne();

      var second = context(database, UUID.fromString("00000000-0000-0000-0000-000000000617"));
      target.completeStaging(second, account, target.checkpoint(second, account));
      target.completeStaging(second, post, target.checkpoint(second, post));
      target.finalizeRun(
          second, List.of(account, post), List.of(
              target.reconcile(second, account), target.reconcile(second, post)));

      assertThat(domainCount(database, "social", "post")).isZero();
      assertThat(domainCount(database, "identity", "account")).isZero();
    }
  }

  private static MigrationSourceDocument source(String id, long fence) {
    return new MigrationSourceDocument(
        "application_lease",
        1,
        id,
        Map.of(
            "ownerToken", "owner-" + id,
            "fenceToken", fence,
            "acquiredAt", Instant.parse("2026-08-14T00:00:00.123456Z"),
            "expiresAt", Instant.parse("2026-08-15T00:00:00.123456Z")));
  }

  private static MigrationSourceDocument vinSource(Map<String, String> rawValues) {
    return new MigrationSourceDocument(
        "vin_decode_cache",
        1,
        "JM1BN1L30K1234567",
        Map.of("response", Map.of(
            "vin", "JM1BN1L30K1234567",
            "make", "Mazda",
            "model", "3",
            "year", 2019,
            "rawDecodedValues", rawValues)));
  }

  private static ValidatedMigrationContext context(
      PostgresqlSchemaTestSupport.MigratedDatabase database) {
    return context(database, UUID.randomUUID());
  }

  private static ValidatedMigrationContext context(
      PostgresqlSchemaTestSupport.MigratedDatabase database, UUID lockToken) {
    return context(database, lockToken, 2);
  }

  private static ValidatedMigrationContext context(
      PostgresqlSchemaTestSupport.MigratedDatabase database, UUID lockToken, int batchSize) {
    var unsigned = new FrozenSourceEvidence(
        "release-6", "a".repeat(64), "test", "test", "b".repeat(64), "c".repeat(64),
        lockToken, "mongodb://127.0.0.1:57018/test", database.jdbcConfiguration().url(),
        "christopherbell_test", "C:\\protected\\writer.lock", "d".repeat(64),
        "e".repeat(64));
    var evidence = new FrozenSourceEvidence(
        unsigned.release(), unsigned.catalogDigest(), unsigned.sourceDatabase(),
        unsigned.targetDatabase(), unsigned.sourceDigest(), unsigned.backupDigest(),
        unsigned.lockToken(), unsigned.sourceUri(), unsigned.targetJdbcUrl(), unsigned.targetRole(),
        unsigned.writerLockPath(), unsigned.writerLockDigest(), unsigned.reconstructedDigest());
    var request = new MigrationRequest(
        PostgresqlMigrationCommand.FINALIZE,
        "mongodb://127.0.0.1:57018/test",
        "test",
        database.jdbcConfiguration().url(),
        "test",
        "christopherbell_test",
        database.prefix(),
        "a".repeat(64),
        "release-6",
        lockToken,
        evidence,
        batchSize);
    return new ValidatedMigrationContext(
        request,
        new MigrationDatabaseIdentity("127.0.0.1", 57018, "test", null),
        new MigrationDatabaseIdentity("127.0.0.1", 55432, "test", "christopherbell_test"),
        true);
  }

  private static ValidatedMigrationContext shadowContext(
      PostgresqlSchemaTestSupport.MigratedDatabase database) {
    var request = new MigrationRequest(
        PostgresqlMigrationCommand.SHADOW,
        "mongodb://127.0.0.1:57018/test", "test", database.jdbcConfiguration().url(), "test",
        "christopherbell_test", database.prefix(), "a".repeat(64), "release-6",
        UUID.randomUUID(), null, 2);
    return new ValidatedMigrationContext(
        request, new MigrationDatabaseIdentity("127.0.0.1", 57018, "test", null),
        new MigrationDatabaseIdentity("127.0.0.1", 55432, "test", "christopherbell_test"),
        false);
  }

  private static void execute(
      PostgresqlSchemaTestSupport.MigratedDatabase database, String sql) throws SQLException {
    try (var connection = database.connect(); var statement = connection.createStatement()) {
      statement.executeUpdate(sql);
    }
  }

  private static String scalar(
      PostgresqlSchemaTestSupport.MigratedDatabase database, String sql) throws SQLException {
    try (var connection = database.connect();
         var statement = connection.createStatement();
         var rows = statement.executeQuery(sql)) {
      rows.next();
      return rows.getString(1);
    }
  }

  private static List<List<Object>> leases(
      PostgresqlSchemaTestSupport.MigratedDatabase database) throws SQLException {
    try (var connection = database.connect();
         var statement = connection.createStatement();
         var rows = statement.executeQuery("select lease_name, fence_token from \""
             + database.prefix() + "platform\".application_lease order by lease_name")) {
      var result = new java.util.ArrayList<List<Object>>();
      while (rows.next()) {
        result.add(List.of(rows.getString(1), rows.getLong(2)));
      }
      return result;
    }
  }

  private static List<List<String>> rawVinValues(
      PostgresqlSchemaTestSupport.MigratedDatabase database) throws SQLException {
    try (var connection = database.connect();
         var statement = connection.createStatement();
         var rows = statement.executeQuery("select field_name, field_value from \""
             + database.prefix() + "mobility\".vin_decode_raw_value order by field_name")) {
      var result = new java.util.ArrayList<List<String>>();
      while (rows.next()) {
        result.add(List.of(rows.getString(1), rows.getString(2)));
      }
      return result;
    }
  }

  private static DriverManagerDataSource dataSource(
      PostgresqlSchemaTestSupport.MigratedDatabase database) {
    var jdbc = database.jdbcConfiguration();
    return new DriverManagerDataSource(jdbc.url(), jdbc.username(), jdbc.password());
  }

  private static long count(
      PostgresqlSchemaTestSupport.MigratedDatabase database, String table) throws SQLException {
    var schema = table.equals("application_lease") ? "platform" : "platform";
    try (var connection = database.connect();
         var statement = connection.createStatement();
         var rows = statement.executeQuery(
             "select count(*) from \"" + database.prefix() + schema + "\".\"" + table + "\"")) {
      rows.next();
      return rows.getLong(1);
    }
  }

  private static long domainCount(
      PostgresqlSchemaTestSupport.MigratedDatabase database, String schema, String table)
      throws SQLException {
    try (var connection = database.connect();
         var statement = connection.createStatement();
         var rows = statement.executeQuery("select count(*) from \"" + database.prefix() + schema
             + "\".\"" + table + "\"")) {
      rows.next();
      return rows.getLong(1);
    }
  }

  private static PostgresqlMigrationCatalog.Kind applicationLeaseKind() throws IOException {
    return loadCatalog().kinds().stream()
        .filter(kind -> kind.sourceKind().equals("application_lease"))
        .findFirst()
        .orElseThrow();
  }

  private static PostgresqlMigrationCatalog loadCatalog() throws IOException {
    try (var input = JdbcMigrationTargetStoreTest.class.getClassLoader()
        .getResourceAsStream("db/migration/postgresql-migration-catalog.yml")) {
      assertThat(input).isNotNull();
      return new PostgresqlMigrationCatalogLoader().load(input);
    }
  }
}
