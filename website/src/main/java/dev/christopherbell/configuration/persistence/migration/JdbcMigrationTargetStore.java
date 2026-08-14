package dev.christopherbell.configuration.persistence.migration;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import javax.sql.DataSource;

/** PostgreSQL ledger/staging store with atomic batch checkpoint and kind publication. */
public final class JdbcMigrationTargetStore implements MigrationTargetStore {
  private static final Pattern PREFIX = Pattern.compile("(?:|cbtest_[a-z0-9_]+_)");
  private final DataSource dataSource;
  private final MigrationRowPublisher publisher;
  private final MigrationRowCodec codec = new MigrationRowCodec();

  public JdbcMigrationTargetStore(DataSource dataSource, MigrationRowPublisher publisher) {
    this.dataSource = dataSource;
    this.publisher = publisher;
  }

  @Override
  public MigrationCheckpoint checkpoint(
      ValidatedMigrationContext context, PostgresqlMigrationCatalog.Kind kind) {
    return transaction(connection -> {
      ensureRun(connection, context);
      ensureKind(connection, context, kind);
      return readCheckpoint(connection, context, kind, false);
    });
  }

  @Override
  public MigrationCheckpoint commitBatch(
      ValidatedMigrationContext context,
      PostgresqlMigrationCatalog.Kind kind,
      MigrationCheckpoint expected,
      List<TransformedMigrationDocument> documents,
      String nextCursor) {
    return transaction(connection -> {
      var actual = readCheckpoint(connection, context, kind, true);
      requireExpected(actual, expected);
      if (documents.isEmpty()) {
        throw new SQLException("Empty batches cannot advance a checkpoint.");
      }
      for (var index = 0; index < documents.size(); index++) {
        stageDocument(
            connection, context, kind, documents.get(index), actual.sourceCount() + index);
      }
      var advanced = actual.advance(nextCursor, documents);
      updateCheckpoint(connection, context, kind, advanced);
      return advanced;
    });
  }

  @Override
  public MigrationCheckpoint completeStaging(
      ValidatedMigrationContext context,
      PostgresqlMigrationCatalog.Kind kind,
      MigrationCheckpoint expected) {
    return transaction(connection -> {
      var actual = readCheckpoint(connection, context, kind, true);
      requireExpected(actual, expected);
      var complete = actual.markComplete();
      updateCheckpoint(connection, context, kind, complete);
      return complete;
    });
  }

  @Override
  public MigrationReconciliation reconcile(
      ValidatedMigrationContext context, PostgresqlMigrationCatalog.Kind kind) {
    return transaction(connection -> reconcile(connection, context, kind, true));
  }

  @Override
  public void publish(
      ValidatedMigrationContext context,
      PostgresqlMigrationCatalog.Kind kind,
      MigrationReconciliation supplied) {
    transaction(connection -> {
      var checkpoint = readCheckpoint(connection, context, kind, true);
      if (published(connection, context, kind)) {
        return null;
      }
      var actual = reconcile(connection, context, kind, false);
      if (!actual.equivalent() || !actual.equals(supplied) || !checkpoint.complete()) {
        throw new MigrationReconciliationException();
      }
      deleteFrozenDelta(connection, context, kind);
      publishStagedRows(connection, context, kind);
      if (!typedTargetEquivalent(connection, context, kind, actual.sourceCount())) {
        throw new MigrationReconciliationException();
      }
      try (var statement = connection.prepareStatement(
          "update " + platform(context) + ".persistence_migration_kind "
              + "set published=true, published_count=?, published_at=transaction_timestamp(), "
              + "updated_at=transaction_timestamp() where run_id=? and source_kind=?")) {
        statement.setLong(1, actual.sourceCount());
        statement.setObject(2, runId(context));
        statement.setString(3, kind.sourceKind());
        requireOne(statement.executeUpdate());
      }
      return null;
    });
  }

  @Override
  public List<MigrationKindStatus> statuses(ValidatedMigrationContext context) {
    return readOnly(connection -> {
      var result = new ArrayList<MigrationKindStatus>();
      try (var statement = connection.prepareStatement(
          "select source_kind, checkpoint_cursor, staging_complete, source_count, source_digest, "
              + "published_count, published from " + platform(context)
              + ".persistence_migration_kind where run_id=? order by source_kind")) {
        statement.setObject(1, runId(context));
        try (var rows = statement.executeQuery()) {
          while (rows.next()) {
            result.add(new MigrationKindStatus(
                rows.getString(1), checkpoint(rows, 2), rows.getLong(6), rows.getBoolean(7)));
          }
        }
      }
      return List.copyOf(result);
    });
  }

  private void ensureRun(Connection connection, ValidatedMigrationContext context)
      throws SQLException {
    var evidence = context.request().frozenSourceEvidence();
    try (var statement = connection.prepareStatement(
        "insert into " + platform(context) + ".persistence_migration_run "
            + "(run_id, catalog_version, source_database, target_database, source_frozen, status, "
            + "release_commit, source_uri_digest, target_jdbc_url_digest, target_role, "
            + "source_snapshot_digest, backup_digest, writer_lock_digest, finalize_evidence_digest) "
            + "values (?, ?, ?, ?, ?, 'STAGING', ?, ?, ?, ?, ?, ?, ?, ?) "
            + "on conflict (run_id) do nothing")) {
      statement.setObject(1, runId(context));
      statement.setString(2, context.request().catalogDigest());
      statement.setString(3, context.sourceIdentity().database());
      statement.setString(4, context.targetIdentity().database());
      statement.setBoolean(5, context.sourceFrozen());
      statement.setString(6, evidence == null ? null : evidence.release());
      statement.setString(7, evidence == null ? null
          : CanonicalMigrationHasher.sha256(evidence.sourceUri()));
      statement.setString(8, evidence == null ? null
          : CanonicalMigrationHasher.sha256(evidence.targetJdbcUrl()));
      statement.setString(9, evidence == null ? null : evidence.targetRole());
      statement.setString(10, evidence == null ? null : evidence.sourceDigest());
      statement.setString(11, evidence == null ? null : evidence.backupDigest());
      statement.setString(12, evidence == null ? null : evidence.writerLockDigest());
      statement.setString(13, evidence == null ? null : evidence.evidenceDigest());
      statement.executeUpdate();
    }
    try (var statement = connection.prepareStatement(
        "select catalog_version, source_database, target_database, source_frozen, "
            + "release_commit, source_uri_digest, target_jdbc_url_digest, target_role, "
            + "source_snapshot_digest, backup_digest, writer_lock_digest, finalize_evidence_digest from "
            + platform(context) + ".persistence_migration_run where run_id=?")) {
      statement.setObject(1, runId(context));
      try (var rows = statement.executeQuery()) {
        if (!rows.next()
            || !context.request().catalogDigest().equals(rows.getString(1))
            || !context.sourceIdentity().database().equals(rows.getString(2))
            || !context.targetIdentity().database().equals(rows.getString(3))
            || context.sourceFrozen() != rows.getBoolean(4)
            || !java.util.Objects.equals(evidence == null ? null : evidence.release(), rows.getString(5))
            || !java.util.Objects.equals(evidence == null ? null
                : CanonicalMigrationHasher.sha256(evidence.sourceUri()), rows.getString(6))
            || !java.util.Objects.equals(evidence == null ? null
                : CanonicalMigrationHasher.sha256(evidence.targetJdbcUrl()), rows.getString(7))
            || !java.util.Objects.equals(evidence == null ? null : evidence.targetRole(), rows.getString(8))
            || !java.util.Objects.equals(evidence == null ? null : evidence.sourceDigest(), rows.getString(9))
            || !java.util.Objects.equals(evidence == null ? null : evidence.backupDigest(), rows.getString(10))
            || !java.util.Objects.equals(evidence == null ? null : evidence.writerLockDigest(), rows.getString(11))
            || !java.util.Objects.equals(evidence == null ? null : evidence.evidenceDigest(), rows.getString(12))) {
          throw new SQLException("Migration run identity does not match.");
        }
      }
    }
  }

  private void ensureKind(
      Connection connection,
      ValidatedMigrationContext context,
      PostgresqlMigrationCatalog.Kind kind) throws SQLException {
    try (var statement = connection.prepareStatement(
        "insert into " + platform(context) + ".persistence_migration_kind "
            + "(run_id, source_kind, transformer_version, source_digest) values (?, ?, ?, ?) "
            + "on conflict (run_id, source_kind) do nothing")) {
      statement.setObject(1, runId(context));
      statement.setString(2, kind.sourceKind());
      statement.setInt(3, kind.transformerVersion());
      statement.setString(4, MigrationCheckpoint.initial().sourceDigest());
      statement.executeUpdate();
    }
  }

  private MigrationCheckpoint readCheckpoint(
      Connection connection,
      ValidatedMigrationContext context,
      PostgresqlMigrationCatalog.Kind kind,
      boolean lock) throws SQLException {
    try (var statement = connection.prepareStatement(
        "select checkpoint_cursor, staging_complete, source_count, source_digest, "
            + "transformer_version from " + platform(context)
            + ".persistence_migration_kind where run_id=? and source_kind=?"
            + (lock ? " for update" : ""))) {
      statement.setObject(1, runId(context));
      statement.setString(2, kind.sourceKind());
      try (var rows = statement.executeQuery()) {
        if (!rows.next() || rows.getInt(5) != kind.transformerVersion()) {
          throw new SQLException("Migration kind is absent or incompatible.");
        }
        return checkpoint(rows, 1);
      }
    }
  }

  private void stageDocument(
      Connection connection,
      ValidatedMigrationContext context,
      PostgresqlMigrationCatalog.Kind kind,
      TransformedMigrationDocument document,
      long stagedSequence) throws SQLException {
    if (!kind.sourceKind().equals(document.sourceKind()) || document.rows().isEmpty()) {
      throw new SQLException("Transformed document does not match its kind.");
    }
    try (var statement = connection.prepareStatement(
        "insert into " + platform(context) + ".persistence_migration_source "
            + "(run_id, source_kind, source_id, transformer_version, source_hash, status, "
            + "staged_sequence) values (?, ?, ?, ?, ?, 'STAGED', ?)")) {
      statement.setObject(1, runId(context));
      statement.setString(2, kind.sourceKind());
      statement.setString(3, document.sourceId());
      statement.setInt(4, kind.transformerVersion());
      statement.setString(5, document.sourceHash());
      statement.setLong(6, stagedSequence);
      requireOne(statement.executeUpdate());
    }
    for (var rowIndex = 0; rowIndex < document.rows().size(); rowIndex++) {
      var row = document.rows().get(rowIndex);
      var rowHash = CanonicalMigrationHasher.sha256(List.of(
          row.targetSchema(), row.targetTable(), row.ordinal(), row.values()));
      try (var statement = connection.prepareStatement(
          "insert into " + platform(context) + ".persistence_migration_staged_row "
              + "(run_id, source_kind, source_id, row_ordinal, target_ordinal, target_schema, "
              + "target_table, source_hash, row_hash, row_payload) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
        statement.setObject(1, runId(context));
        statement.setString(2, kind.sourceKind());
        statement.setString(3, document.sourceId());
        statement.setInt(4, rowIndex);
        statement.setInt(5, row.ordinal());
        statement.setString(6, row.targetSchema());
        statement.setString(7, row.targetTable());
        statement.setString(8, document.sourceHash());
        statement.setString(9, rowHash);
        statement.setBytes(10, codec.encode(row.values()));
        requireOne(statement.executeUpdate());
      }
    }
  }

  private void updateCheckpoint(
      Connection connection,
      ValidatedMigrationContext context,
      PostgresqlMigrationCatalog.Kind kind,
      MigrationCheckpoint checkpoint) throws SQLException {
    try (var statement = connection.prepareStatement(
        "update " + platform(context) + ".persistence_migration_kind "
            + "set checkpoint_cursor=?, staging_complete=?, source_count=?, source_digest=?, "
            + "updated_at=transaction_timestamp() where run_id=? and source_kind=?")) {
      statement.setString(1, checkpoint.cursor());
      statement.setBoolean(2, checkpoint.complete());
      statement.setLong(3, checkpoint.sourceCount());
      statement.setString(4, checkpoint.sourceDigest());
      statement.setObject(5, runId(context));
      statement.setString(6, kind.sourceKind());
      requireOne(statement.executeUpdate());
    }
  }

  private MigrationReconciliation reconcile(
      Connection connection,
      ValidatedMigrationContext context,
      PostgresqlMigrationCatalog.Kind kind,
      boolean persist) throws SQLException {
    var checkpoint = readCheckpoint(connection, context, kind, false);
    long sourceCount;
    long stagedCount;
    var digest = MigrationCheckpoint.initial().sourceDigest();
    var sourceHashes = new HashMap<String, String>();
    try (var statement = connection.prepareStatement(
        "select source_id, source_hash from " + platform(context)
            + ".persistence_migration_source where run_id=? and source_kind=? "
            + "order by staged_sequence")) {
      statement.setObject(1, runId(context));
      statement.setString(2, kind.sourceKind());
      sourceCount = 0;
      try (var rows = statement.executeQuery()) {
        while (rows.next()) {
          var sourceId = rows.getString(1);
          var sourceHash = rows.getString(2);
          digest = CanonicalMigrationHasher.sha256(List.of(digest, sourceHash));
          sourceHashes.put(sourceId, sourceHash);
          sourceCount++;
        }
      }
    }
    var seenRoot = new HashSet<String>();
    var rowsValid = true;
    var rootKey = kind.keyMapping().targetColumn();
    rootKey = rootKey.substring(rootKey.indexOf('.') + 1);
    try (var statement = connection.prepareStatement(
        "select source_id, target_schema, target_table, target_ordinal, source_hash, row_hash, "
            + "row_payload from " + platform(context)
            + ".persistence_migration_staged_row where run_id=? and source_kind=? "
            + "order by source_id, row_ordinal")) {
      statement.setObject(1, runId(context));
      statement.setString(2, kind.sourceKind());
      try (var rows = statement.executeQuery()) {
        var stagedSources = new HashSet<String>();
        while (rows.next()) {
          var sourceId = rows.getString(1);
          var schema = rows.getString(2);
          var table = rows.getString(3);
          var targetOrdinal = rows.getInt(4);
          var sourceHash = rows.getString(5);
          var storedRowHash = rows.getString(6);
          var values = codec.decode(rows.getBytes(7));
          var reconstructedRowHash = CanonicalMigrationHasher.sha256(
              List.of(schema, table, targetOrdinal, values));
          rowsValid &= kind.targetSchema().equals(schema)
              && kind.targetTables().contains(table)
              && sourceHash.equals(sourceHashes.get(sourceId))
              && storedRowHash.equals(reconstructedRowHash);
          if (kind.targetTables().getFirst().equals(table)) {
            seenRoot.add(sourceId);
            rowsValid &= sourceId.equals(java.util.Objects.toString(values.get(rootKey), null));
          }
          stagedSources.add(sourceId);
        }
        stagedCount = stagedSources.size();
        rowsValid &= seenRoot.equals(sourceHashes.keySet());
        var result = new MigrationReconciliation(
            checkpoint.complete(), sourceCount, stagedCount,
            checkpoint.sourceDigest(), digest, rowsValid,
            rowsValid && !kind.portQueries().isEmpty());
        if (persist) {
          persistReconciliation(connection, context, kind, result);
        }
        return result;
      }
    }
  }

  private void persistReconciliation(
      Connection connection,
      ValidatedMigrationContext context,
      PostgresqlMigrationCatalog.Kind kind,
      MigrationReconciliation result) throws SQLException {
    try (var statement = connection.prepareStatement(
        "update " + platform(context) + ".persistence_migration_kind "
            + "set staged_count=?, reconstructed_source_digest=?, relationships_valid=?, "
            + "port_queries_valid=?, updated_at=transaction_timestamp() "
            + "where run_id=? and source_kind=?")) {
      statement.setLong(1, result.stagedCount());
      statement.setString(2, result.reconstructedSourceDigest());
      statement.setBoolean(3, result.relationshipsValid());
      statement.setBoolean(4, result.portQueriesValid());
      statement.setObject(5, runId(context));
      statement.setString(6, kind.sourceKind());
      requireOne(statement.executeUpdate());
    }
  }

  private void publishStagedRows(
      Connection connection,
      ValidatedMigrationContext context,
      PostgresqlMigrationCatalog.Kind kind) throws SQLException {
    try (var statement = connection.prepareStatement(
        "select source_id, target_schema, target_table, target_ordinal, row_payload from "
            + platform(context) + ".persistence_migration_staged_row "
            + "where run_id=? and source_kind=? order by source_id, row_ordinal")) {
      statement.setFetchSize(Math.min(context.request().batchSize(), 500));
      statement.setObject(1, runId(context));
      statement.setString(2, kind.sourceKind());
      try (var rows = statement.executeQuery()) {
        var batch = new ArrayList<StagedMigrationRow>(statement.getFetchSize());
        while (rows.next()) {
          batch.add(new StagedMigrationRow(
              rows.getString(1), rows.getString(2), rows.getString(3), rows.getInt(4),
              codec.decode(rows.getBytes(5))));
          if (batch.size() == statement.getFetchSize()) {
            publisher.publish(connection, prefix(context), kind, List.copyOf(batch));
            batch.clear();
          }
        }
        if (!batch.isEmpty()) {
          publisher.publish(connection, prefix(context), kind, List.copyOf(batch));
        }
      }
    }
  }

  private void deleteFrozenDelta(
      Connection connection,
      ValidatedMigrationContext context,
      PostgresqlMigrationCatalog.Kind kind) throws SQLException {
    var rootTable = kind.targetTables().getFirst();
    var keyMapping = kind.keyMapping().targetColumn();
    var separator = keyMapping.indexOf('.');
    if (!rootTable.equals(keyMapping.substring(0, separator))) {
      throw new SQLException("Migration root key does not match the first catalog table.");
    }
    var rootKey = keyMapping.substring(separator + 1);
    for (var tableIndex = kind.targetTables().size() - 1; tableIndex > 0; tableIndex--) {
      var childTable = kind.targetTables().get(tableIndex);
      var sourceKey = childSourceKey(connection, context, kind, childTable, rootKey);
      if (sourceKey == null) {
        throw new SQLException("Migration child table has no catalog-owned source identity.");
      }
      var qualifiedChild = quoted(prefix(context) + kind.targetSchema()) + "."
          + quoted(childTable);
      try (var statement = connection.prepareStatement(
          "delete from " + qualifiedChild + " child where exists (select 1 from "
              + platform(context) + ".persistence_migration_source source where source.run_id=? "
              + "and source.source_kind=? and source.source_id=child." + quoted(sourceKey)
              + "::text)")) {
        statement.setObject(1, runId(context));
        statement.setString(2, kind.sourceKind());
        statement.executeUpdate();
      }
    }
    var qualifiedRoot = quoted(prefix(context) + kind.targetSchema()) + "." + quoted(rootTable);
    try (var statement = connection.prepareStatement(
        "delete from " + qualifiedRoot + " target where not exists (select 1 from "
            + platform(context) + ".persistence_migration_source source where source.run_id=? "
            + "and source.source_kind=? and source.source_id=target." + quoted(rootKey) + ")")) {
      statement.setObject(1, runId(context));
      statement.setString(2, kind.sourceKind());
      statement.executeUpdate();
    }
  }

  private String childSourceKey(
      Connection connection,
      ValidatedMigrationContext context,
      PostgresqlMigrationCatalog.Kind kind,
      String childTable,
      String rootKey) throws SQLException {
    var schema = prefix(context) + kind.targetSchema();
    try (var keys = connection.getMetaData().getImportedKeys(null, schema, childTable)) {
      while (keys.next()) {
        if (schema.equals(keys.getString("PKTABLE_SCHEM"))
            && kind.targetTables().contains(keys.getString("PKTABLE_NAME"))
            && rootKey.equals(keys.getString("PKCOLUMN_NAME"))) {
          return keys.getString("FKCOLUMN_NAME");
        }
      }
    }
    return null;
  }

  private boolean typedTargetEquivalent(
      Connection connection,
      ValidatedMigrationContext context,
      PostgresqlMigrationCatalog.Kind kind,
      long expectedCount) throws SQLException {
    var rootTable = kind.targetTables().getFirst();
    var keyMapping = kind.keyMapping().targetColumn();
    var rootKey = keyMapping.substring(keyMapping.indexOf('.') + 1);
    var qualifiedRoot = quoted(prefix(context) + kind.targetSchema()) + "." + quoted(rootTable);
    try (var statement = connection.prepareStatement(
        "select count(*), count(*) filter (where exists (select 1 from " + platform(context)
            + ".persistence_migration_source source where source.run_id=? and source.source_kind=? "
            + "and source.source_id=target." + quoted(rootKey) + ")) from "
            + qualifiedRoot + " target")) {
      statement.setObject(1, runId(context));
      statement.setString(2, kind.sourceKind());
      try (var rows = statement.executeQuery()) {
        return rows.next() && rows.getLong(1) == expectedCount && rows.getLong(2) == expectedCount;
      }
    }
  }

  private boolean published(
      Connection connection,
      ValidatedMigrationContext context,
      PostgresqlMigrationCatalog.Kind kind) throws SQLException {
    try (var statement = connection.prepareStatement(
        "select published from " + platform(context)
            + ".persistence_migration_kind where run_id=? and source_kind=?")) {
      statement.setObject(1, runId(context));
      statement.setString(2, kind.sourceKind());
      try (var rows = statement.executeQuery()) {
        return rows.next() && rows.getBoolean(1);
      }
    }
  }

  private static MigrationCheckpoint checkpoint(ResultSet rows, int offset) throws SQLException {
    return new MigrationCheckpoint(
        rows.getString(offset), rows.getBoolean(offset + 1), rows.getLong(offset + 2),
        rows.getString(offset + 3));
  }

  private static void requireExpected(
      MigrationCheckpoint actual, MigrationCheckpoint expected) throws SQLException {
    if (!actual.equals(expected) || actual.complete()) {
      throw new SQLException("Migration checkpoint changed concurrently.");
    }
  }

  private static void requireOne(int count) throws SQLException {
    if (count != 1) {
      throw new SQLException("Migration target row count was unexpected.");
    }
  }

  private static UUID runId(ValidatedMigrationContext context) {
    return context.request().lockToken();
  }

  private static String prefix(ValidatedMigrationContext context) {
    var prefix = context.request().schemaPrefix();
    if (!PREFIX.matcher(prefix).matches()) {
      throw new IllegalArgumentException("PostgreSQL migration schema prefix is invalid.");
    }
    return prefix;
  }

  private static String platform(ValidatedMigrationContext context) {
    return '"' + prefix(context) + "platform\"";
  }

  private static String quoted(String identifier) {
    if (!identifier.matches("[a-z][a-z0-9_]*")) {
      throw new IllegalArgumentException("PostgreSQL migration target identifier is invalid.");
    }
    return '"' + identifier + '"';
  }

  private <T> T transaction(SqlWork<T> work) {
    try (var connection = dataSource.getConnection()) {
      connection.setAutoCommit(false);
      try {
        var result = work.run(connection);
        connection.commit();
        return result;
      } catch (RuntimeException | SQLException failure) {
        connection.rollback();
        if (failure instanceof MigrationReconciliationException reconciliation) {
          throw reconciliation;
        }
        throw new MigrationStorageException(failure);
      }
    } catch (SQLException failure) {
      throw new MigrationStorageException(failure);
    }
  }

  private <T> T readOnly(SqlWork<T> work) {
    try (var connection = dataSource.getConnection()) {
      connection.setReadOnly(true);
      connection.setAutoCommit(false);
      try {
        var result = work.run(connection);
        connection.rollback();
        return result;
      } catch (RuntimeException | SQLException failure) {
        connection.rollback();
        throw new MigrationStorageException(failure);
      }
    } catch (SQLException failure) {
      throw new MigrationStorageException(failure);
    }
  }

  @FunctionalInterface
  private interface SqlWork<T> {
    T run(Connection connection) throws SQLException;
  }
}
