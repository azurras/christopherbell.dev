package dev.christopherbell.configuration.persistence.migration;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import javax.sql.DataSource;

/** PostgreSQL ledger/staging store with atomic batch checkpoint and kind publication. */
public final class JdbcMigrationTargetStore implements MigrationTargetStore {
  private static final Pattern PREFIX = Pattern.compile("(?:|cbtest_[a-z0-9_]+_)");
  private static final String FROZEN_SOURCE = "pg_temp.persistence_migration_frozen_source";
  private static final String FROZEN_ROW = "pg_temp.persistence_migration_frozen_row";
  private final DataSource dataSource;
  private final MigrationRowPublisher publisher;
  private final FinalizationSnapshotInterlock finalizationInterlock;
  private final List<String> expectedKinds;
  private final JdbcRelationalRowPublisher verifier = new JdbcRelationalRowPublisher();
  private final MigrationRowCodec codec = new MigrationRowCodec();
  private final MigrationPortQueryVerifierRegistry portQueryVerifiers;

  JdbcMigrationTargetStore(DataSource dataSource, MigrationRowPublisher publisher) {
    this(dataSource, publisher, MigrationPortQueryVerifierRegistry.standard(),
        FinalizationSnapshotInterlock.NONE, List.of());
  }

  JdbcMigrationTargetStore(
      DataSource dataSource,
      MigrationRowPublisher publisher,
      FinalizationSnapshotInterlock finalizationInterlock) {
    this(dataSource, publisher, MigrationPortQueryVerifierRegistry.standard(),
        finalizationInterlock, List.of());
  }

  public JdbcMigrationTargetStore(
      DataSource dataSource,
      MigrationRowPublisher publisher,
      PostgresqlMigrationCatalog catalog) {
    this(dataSource, publisher, MigrationPortQueryVerifierRegistry.from(catalog),
        FinalizationSnapshotInterlock.NONE, catalog.kinds().stream()
            .sorted(java.util.Comparator.comparingInt(PostgresqlMigrationCatalog.Kind::loadOrder))
            .map(PostgresqlMigrationCatalog.Kind::sourceKind).toList());
  }

  private JdbcMigrationTargetStore(
      DataSource dataSource,
      MigrationRowPublisher publisher,
      MigrationPortQueryVerifierRegistry portQueryVerifiers,
      FinalizationSnapshotInterlock finalizationInterlock,
      List<String> expectedKinds) {
    this.dataSource = dataSource;
    this.publisher = publisher;
    this.finalizationInterlock = java.util.Objects.requireNonNull(
        finalizationInterlock, "finalizationInterlock");
    this.expectedKinds = List.copyOf(expectedKinds);
    this.portQueryVerifiers = portQueryVerifiers;
  }

  @Override
  public void requireExistingRun(ValidatedMigrationContext context) {
    if (context.request().command() != PostgresqlMigrationCommand.STATUS
        && context.request().command() != PostgresqlMigrationCommand.RECONCILE) {
      throw new IllegalArgumentException(
          "Existing migration run identity is only valid for inspection commands.");
    }
    readOnly(connection -> {
      requireRunIdentity(connection, context, false);
      return null;
    });
  }

  @Override
  public void prepareExistingRunVerification(
      ValidatedMigrationContext context,
      List<PostgresqlMigrationCatalog.Kind> kinds) {
    if (context.request().command() != PostgresqlMigrationCommand.RECONCILE
        || context.sourceFrozen() || context.request().frozenSourceEvidence() != null) {
      throw new MigrationReconciliationException();
    }
    requireCompleteKinds(kinds);
    transaction(connection -> {
      lockDomainMutation(connection);
      requireRunIdentity(connection, context, false);
      for (var kind : kinds) {
        invalidateKindVerification(connection, context, kind);
      }
      markRunUnpublished(connection, context, "RECONCILING");
      return null;
    });
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
  public void requireStagedDocuments(
      ValidatedMigrationContext context,
      PostgresqlMigrationCatalog.Kind kind,
      List<TransformedMigrationDocument> documents) {
    readOnly(connection -> {
      requireStagedDocuments(connection, context, kind, documents);
      return null;
    });
  }

  @Override
  public MigrationReconciliation reconcile(
      ValidatedMigrationContext context, PostgresqlMigrationCatalog.Kind kind) {
    return transaction(connection -> reconcile(connection, context, kind, true));
  }

  @Override
  public void verifyExistingRun(
      ValidatedMigrationContext context,
      List<PostgresqlMigrationCatalog.Kind> kinds,
      List<MigrationReconciliation> reconciliations) {
    if (context.request().command() != PostgresqlMigrationCommand.RECONCILE
        || context.sourceFrozen() || context.request().frozenSourceEvidence() != null) {
      throw new MigrationReconciliationException();
    }
    requireCompleteCatalog(kinds, reconciliations);
    var verified = transaction(connection -> {
      lockDomainMutation(connection);
      lockStagingMutation(connection, context);
      requireEquivalentStaging(connection, context, kinds, reconciliations);
      lockVerificationTables(connection, context, kinds);
      var sourceFrozen = readRunSourceFrozen(connection, context);
      var publicationCommit = readPublicationCommit(connection, context);
      if (publicationCommit.isPresent() && !sourceFrozen) {
        throw new MigrationReconciliationException();
      }
      var allValid = true;
      for (var index = 0; index < kinds.size(); index++) {
        var kind = kinds.get(index);
        var actual = reconciliations.get(index);
        var typed = typedTargetEquivalent(
            connection, context, kind, actual.sourceCount(), sourceFrozen);
        var relationships = relationshipsEquivalent(connection, context, kind);
        var queries = portQueryEquivalent(connection, context, kind, !sourceFrozen);
        persistKindVerification(connection, context, kind, typed, relationships, queries);
        if (typed && relationships && queries) {
          persistSourceVerification(
              connection, context, kind, actual.sourceCount(), "VERIFIED");
        } else {
          markKindVerificationFailed(connection, context, kind);
          allValid = false;
        }
      }
      if (!allValid) {
        markRunUnpublished(connection, context, "FAILED");
      } else if (publicationCommit.isPresent()) {
        for (var index = 0; index < kinds.size(); index++) {
          markSourcesPublished(
              connection, context, kinds.get(index), reconciliations.get(index).sourceCount());
          markPublished(
              connection, context, kinds.get(index), reconciliations.get(index).sourceCount());
        }
        markRunPublished(connection, context, publicationCommit.orElseThrow());
      } else {
        markRunUnpublished(connection, context, "READY");
      }
      return allValid;
    });
    if (!verified) {
      throw new MigrationReconciliationException();
    }
  }

  @Override
  public void rehearseShadow(
      ValidatedMigrationContext context,
      List<PostgresqlMigrationCatalog.Kind> kinds,
      List<MigrationReconciliation> supplied) {
    if (context.request().command() != PostgresqlMigrationCommand.SHADOW
        || context.sourceFrozen() || context.request().frozenSourceEvidence() != null) {
      throw new MigrationReconciliationException();
    }
    requireCompleteCatalog(kinds, supplied);
    transaction(connection -> {
      lockDomainMutation(connection);
      lockStagingMutation(connection, context);
      requireEquivalentStaging(connection, context, kinds, supplied);
      for (var kind : kinds) {
        publishStagedRows(connection, context, kind);
      }
      verifyTypedDomain(connection, context, kinds, supplied, false, "VERIFIED");
      markRunUnpublished(connection, context, "READY");
      return null;
    });
  }

  @Override
  public void finalizeRun(
      ValidatedMigrationContext context,
      List<PostgresqlMigrationCatalog.Kind> kinds,
      List<MigrationReconciliation> supplied,
      LockedFinalizationCheck finalizationCheck) {
    java.util.Objects.requireNonNull(finalizationCheck, "finalizationCheck");
    requireCompleteCatalog(kinds, supplied);
    requireFinalizeRequest(context);
    transaction(connection -> {
      lockDomainMutation(connection);
      requireEquivalentStaging(connection, context, kinds, supplied);
      lockStagingMutation(connection, context);
      createFrozenSnapshot(connection);
      var frozen = new FrozenSnapshotWriter(connection);
      requireFinalizeAuthority(context, finalizationCheck.revalidate(frozen::accept));
      requireFrozenSnapshotEqualsStaging(connection, context);
      finalizationInterlock.beforePublication(connection, context);
      requireFrozenSnapshotEqualsStaging(connection, context);
      for (var index = kinds.size() - 1; index >= 0; index--) {
        deleteFrozenDelta(connection, context, kinds.get(index), FROZEN_SOURCE);
      }
      for (var kind : kinds) {
        publishFrozenRows(connection, context, kind);
      }
      verifyTypedDomain(connection, context, kinds, supplied, true, "PUBLISHED");
      var publicationCommittedAt = recordPublicationCommit(connection, context);
      for (var index = 0; index < kinds.size(); index++) {
        markPublished(connection, context, kinds.get(index), supplied.get(index).sourceCount());
      }
      markRunPublished(connection, context, publicationCommittedAt);
      return null;
    });
  }

  private static void requireFinalizeRequest(ValidatedMigrationContext context) {
    if (context.request().command() != PostgresqlMigrationCommand.FINALIZE
        || !context.sourceFrozen()
        || context.request().frozenSourceEvidence() == null) {
      throw new MigrationReconciliationException();
    }
  }

  private void requireCompleteCatalog(
      List<PostgresqlMigrationCatalog.Kind> kinds, List<MigrationReconciliation> supplied) {
    requireCompleteKinds(kinds);
    if (kinds.size() != supplied.size()) {
      throw new MigrationReconciliationException();
    }
  }

  private void requireCompleteKinds(List<PostgresqlMigrationCatalog.Kind> kinds) {
    if (kinds.isEmpty()) {
      throw new MigrationReconciliationException();
    }
    if (!expectedKinds.isEmpty()
        && !kinds.stream().map(PostgresqlMigrationCatalog.Kind::sourceKind).toList()
            .equals(expectedKinds)) {
      throw new MigrationReconciliationException();
    }
  }

  private static void lockDomainMutation(Connection connection) throws SQLException {
    try (var statement = connection.prepareStatement(
        "select pg_advisory_xact_lock(hashtextextended(?, 0))")) {
      statement.setString(1, "christopherbell-postgresql-migration-domain-mutation");
      statement.executeQuery();
    }
  }

  private static void lockStagingMutation(
      Connection connection, ValidatedMigrationContext context) throws SQLException {
    try (var statement = connection.createStatement()) {
      statement.execute("lock table " + platform(context)
          + ".persistence_migration_source in share mode");
      statement.execute("lock table " + platform(context)
          + ".persistence_migration_staged_row in share mode");
    }
  }

  private static void lockVerificationTables(
      Connection connection,
      ValidatedMigrationContext context,
      List<PostgresqlMigrationCatalog.Kind> kinds) throws SQLException {
    var tables = kinds.stream().flatMap(kind -> kind.targetTables().stream()
            .map(table -> quoted(prefix(context) + kind.targetSchema()) + "." + quoted(table)))
        .distinct().sorted().toList();
    for (var table : tables) {
      try (var statement = connection.createStatement()) {
        statement.execute("lock table " + table + " in share mode");
      }
    }
  }

  private static boolean readRunSourceFrozen(
      Connection connection, ValidatedMigrationContext context) throws SQLException {
    try (var statement = connection.prepareStatement(
        "select source_frozen from " + platform(context)
            + ".persistence_migration_run where run_id=?")) {
      statement.setObject(1, runId(context));
      try (var rows = statement.executeQuery()) {
        if (!rows.next()) {
          throw new MigrationReconciliationException();
        }
        return rows.getBoolean(1);
      }
    }
  }

  private static Optional<OffsetDateTime> readPublicationCommit(
      Connection connection, ValidatedMigrationContext context) throws SQLException {
    try (var statement = connection.prepareStatement(
        "select committed_at from " + platform(context)
            + ".persistence_migration_publication_commit where run_id=?")) {
      statement.setObject(1, runId(context));
      try (var rows = statement.executeQuery()) {
        if (!rows.next()) {
          return Optional.empty();
        }
        return Optional.of(rows.getObject(1, OffsetDateTime.class));
      }
    }
  }

  private static OffsetDateTime recordPublicationCommit(
      Connection connection, ValidatedMigrationContext context) throws SQLException {
    try (var statement = connection.prepareStatement(
        "insert into " + platform(context)
            + ".persistence_migration_publication_commit (run_id) values (?) "
            + "on conflict (run_id) do nothing")) {
      statement.setObject(1, runId(context));
      statement.executeUpdate();
    }
    return readPublicationCommit(connection, context)
        .orElseThrow(MigrationReconciliationException::new);
  }

  private static void createFrozenSnapshot(Connection connection) throws SQLException {
    try (var statement = connection.createStatement()) {
      statement.execute("create temporary table persistence_migration_frozen_source ("
          + "source_kind varchar(96) not null, source_id varchar(512) not null, "
          + "transformer_version integer not null, source_hash char(64) not null, "
          + "staged_sequence bigint not null, primary key (source_kind, source_id)) "
          + "on commit drop");
      statement.execute("create temporary table persistence_migration_frozen_row ("
          + "source_kind varchar(96) not null, source_id varchar(512) not null, "
          + "row_ordinal integer not null, target_ordinal integer not null, "
          + "target_schema varchar(96) not null, target_table varchar(96) not null, "
          + "source_hash char(64) not null, row_hash char(64) not null, row_payload bytea not null, "
          + "primary key (source_kind, source_id, row_ordinal)) on commit drop");
    }
  }

  private void requireFrozenSnapshotEqualsStaging(
      Connection connection, ValidatedMigrationContext context) throws SQLException {
    var sourceColumns = "source_kind, source_id, transformer_version, source_hash, staged_sequence";
    var sourceDifference = "select " + sourceColumns + " from " + platform(context)
        + ".persistence_migration_source where run_id=? except select " + sourceColumns
        + " from " + FROZEN_SOURCE + " union all (select " + sourceColumns + " from "
        + FROZEN_SOURCE + " except select " + sourceColumns + " from " + platform(context)
        + ".persistence_migration_source where run_id=?)";
    var rowColumns = "source_kind, source_id, row_ordinal, target_ordinal, target_schema, "
        + "target_table, source_hash, row_hash, row_payload";
    var rowDifference = "select " + rowColumns + " from " + platform(context)
        + ".persistence_migration_staged_row where run_id=? except select " + rowColumns
        + " from " + FROZEN_ROW + " union all (select " + rowColumns + " from "
        + FROZEN_ROW + " except select " + rowColumns + " from " + platform(context)
        + ".persistence_migration_staged_row where run_id=?)";
    if (hasRows(connection, sourceDifference, runId(context))
        || hasRows(connection, rowDifference, runId(context))) {
      throw new MigrationReconciliationException();
    }
  }

  private static boolean hasRows(Connection connection, String sql, UUID runId)
      throws SQLException {
    try (var statement = connection.prepareStatement(sql)) {
      statement.setObject(1, runId);
      statement.setObject(2, runId);
      try (var rows = statement.executeQuery()) {
        return rows.next();
      }
    }
  }

  private void requireEquivalentStaging(
      Connection connection,
      ValidatedMigrationContext context,
      List<PostgresqlMigrationCatalog.Kind> kinds,
      List<MigrationReconciliation> supplied) throws SQLException {
    for (var index = 0; index < kinds.size(); index++) {
      var kind = kinds.get(index);
      var checkpoint = readCheckpoint(connection, context, kind, true);
      var actual = reconcile(connection, context, kind, false);
      if (!actual.equivalent() || !actual.equals(supplied.get(index))
          || !checkpoint.complete()) {
        throw new MigrationReconciliationException();
      }
    }
  }

  private void verifyTypedDomain(
      Connection connection,
      ValidatedMigrationContext context,
      List<PostgresqlMigrationCatalog.Kind> kinds,
      List<MigrationReconciliation> supplied,
      boolean exactCounts,
      String sourceStatus) throws SQLException {
    for (var index = 0; index < kinds.size(); index++) {
      var kind = kinds.get(index);
      var actual = supplied.get(index);
      var typed = typedTargetEquivalent(
          connection, context, kind, actual.sourceCount(), exactCounts);
      var relationships = relationshipsEquivalent(connection, context, kind);
      var queries = portQueryEquivalent(connection, context, kind, !exactCounts);
      if (!typed || !relationships || !queries) {
        throw new MigrationReconciliationException();
      }
      persistKindVerification(connection, context, kind, typed, relationships, queries);
      persistSourceVerification(
          connection, context, kind, actual.sourceCount(), sourceStatus);
    }
  }

  private void requireFinalizeAuthority(
      ValidatedMigrationContext context, FrozenSourceEvidence evidence) {
    var request = context.request();
    if (request.command() != PostgresqlMigrationCommand.FINALIZE
        || !context.sourceFrozen()
        || evidence == null
        || !request.lockToken().equals(evidence.lockToken())
        || !request.release().equals(evidence.release())
        || !request.catalogDigest().equals(evidence.catalogDigest())
        || !request.sourceDatabase().equals(evidence.sourceDatabase())
        || !request.targetDatabase().equals(evidence.targetDatabase())
        || !request.sourceUri().equals(evidence.sourceUri())
        || !request.targetJdbcUrl().equals(evidence.targetJdbcUrl())
        || !request.expectedTargetRole().equals(evidence.targetRole())
        || !evidence.equals(request.frozenSourceEvidence())
        || !evidence.evidenceDigest().equals(evidence.reconstructedDigest())) {
      throw new MigrationReconciliationException();
    }
    if (!expectedKinds.isEmpty()) {
      try {
        FinalizeEvidenceLoader.requireWriterLock(evidence);
      } catch (IllegalArgumentException failure) {
        throw new MigrationReconciliationException();
      }
    }
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
            + "bridge_release, source_snapshot_digest, backup_digest, writer_lock_digest, "
            + "finalize_evidence_digest, finalize_reauthorization_required) "
            + "values (?, ?, ?, ?, ?, 'STAGING', ?, ?, ?, ?, ?, ?, ?, ?, ?, false) "
            + "on conflict (run_id) do nothing")) {
      statement.setObject(1, runId(context));
      statement.setString(2, context.request().catalogDigest());
      statement.setString(3, context.sourceIdentity().database());
      statement.setString(4, context.targetIdentity().database());
      statement.setBoolean(5, context.sourceFrozen());
      statement.setString(6, context.request().release());
      statement.setString(7, CanonicalMigrationHasher.sha256(context.request().sourceUri()));
      statement.setString(8, CanonicalMigrationHasher.sha256(context.request().targetJdbcUrl()));
      statement.setString(9, context.request().expectedTargetRole());
      statement.setInt(10, context.request().bridgeRelease());
      statement.setString(11, evidence == null ? null : evidence.sourceDigest());
      statement.setString(12, evidence == null ? null : evidence.backupDigest());
      statement.setString(13, evidence == null ? null : evidence.writerLockDigest());
      statement.setString(14, evidence == null ? null : evidence.evidenceDigest());
      statement.executeUpdate();
    }
    requireRunIdentity(connection, context, true);
  }

  private void requireRunIdentity(
      Connection connection, ValidatedMigrationContext context, boolean requireAuthorityIdentity)
      throws SQLException {
    var evidence = context.request().frozenSourceEvidence();
    try (var statement = connection.prepareStatement(
        "select catalog_version, source_database, target_database, source_frozen, "
            + "release_commit, source_uri_digest, target_jdbc_url_digest, target_role, "
            + "bridge_release, source_snapshot_digest, backup_digest, writer_lock_digest, "
            + "finalize_evidence_digest, finalize_reauthorization_required from "
            + platform(context) + ".persistence_migration_run where run_id=?")) {
      statement.setObject(1, runId(context));
      try (var rows = statement.executeQuery()) {
        if (!rows.next()
            || !context.request().catalogDigest().equals(rows.getString(1))
            || !context.sourceIdentity().database().equals(rows.getString(2))
            || !context.targetIdentity().database().equals(rows.getString(3))
            || !context.request().release().equals(rows.getString(5))
            || !CanonicalMigrationHasher.sha256(context.request().sourceUri())
                .equals(rows.getString(6))
            || !CanonicalMigrationHasher.sha256(context.request().targetJdbcUrl())
                .equals(rows.getString(7))
            || !context.request().expectedTargetRole().equals(rows.getString(8))
            || context.request().bridgeRelease() != rows.getInt(9)
            || rows.wasNull()
            || requireAuthorityIdentity && (
                context.sourceFrozen() != rows.getBoolean(4)
                || !java.util.Objects.equals(
                    evidence == null ? null : evidence.sourceDigest(), rows.getString(10))
                || !java.util.Objects.equals(
                    evidence == null ? null : evidence.backupDigest(), rows.getString(11))
                || !java.util.Objects.equals(
                    evidence == null ? null : evidence.writerLockDigest(), rows.getString(12))
                || !java.util.Objects.equals(
                    evidence == null ? null : evidence.evidenceDigest(), rows.getString(13))
                || rows.getBoolean(14))) {
          throw new MigrationReconciliationException();
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
      var rowHash = MigrationCanonicalizationRegistry.targetRowHash(kind, row);
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

  private void requireStagedDocuments(
      Connection connection,
      ValidatedMigrationContext context,
      PostgresqlMigrationCatalog.Kind kind,
      List<TransformedMigrationDocument> documents) throws SQLException {
    if (documents.isEmpty() || documents.size() > context.request().batchSize()) {
      throw new MigrationReconciliationException();
    }
    var expected = new LinkedHashMap<String, List<String>>();
    var sourceHashes = new LinkedHashMap<String, String>();
    for (var document : documents) {
      if (!kind.sourceKind().equals(document.sourceKind())
          || sourceHashes.put(document.sourceId(), document.sourceHash()) != null) {
        throw new MigrationReconciliationException();
      }
      var rows = new ArrayList<String>(document.rows().size());
      for (var rowIndex = 0; rowIndex < document.rows().size(); rowIndex++) {
        var row = document.rows().get(rowIndex);
        rows.add(CanonicalMigrationHasher.sha256(List.of(
            rowIndex, document.sourceHash(),
            MigrationCanonicalizationRegistry.targetRowHash(kind, row))));
      }
      expected.put(document.sourceId(), List.copyOf(rows));
    }
    var sourceIds = connection.createArrayOf("text", expected.keySet().toArray());
    try (var sourceStatement = connection.prepareStatement(
             "select source_id, source_hash from " + platform(context)
                 + ".persistence_migration_source where run_id=? and source_kind=? "
                 + "and source_id=any(?) order by source_id")) {
      sourceStatement.setObject(1, runId(context));
      sourceStatement.setString(2, kind.sourceKind());
      sourceStatement.setArray(3, sourceIds);
      var found = new LinkedHashMap<String, String>();
      try (var rows = sourceStatement.executeQuery()) {
        while (rows.next()) {
          found.put(rows.getString(1), rows.getString(2));
        }
      }
      if (!found.equals(sourceHashes)) {
        throw new MigrationReconciliationException();
      }
    } finally {
      sourceIds.free();
    }
    var rowIds = connection.createArrayOf("text", expected.keySet().toArray());
    try (var rowStatement = connection.prepareStatement(
             "select source_id, row_ordinal, target_schema, target_table, target_ordinal, "
                 + "source_hash, row_hash, row_payload from " + platform(context)
                 + ".persistence_migration_staged_row where run_id=? and source_kind=? "
                 + "and source_id=any(?) order by source_id, row_ordinal")) {
      rowStatement.setObject(1, runId(context));
      rowStatement.setString(2, kind.sourceKind());
      rowStatement.setArray(3, rowIds);
      var actual = new LinkedHashMap<String, List<String>>();
      try (var rows = rowStatement.executeQuery()) {
        while (rows.next()) {
          var sourceId = rows.getString(1);
          var rowOrdinal = rows.getInt(2);
          var schema = rows.getString(3);
          var table = rows.getString(4);
          var targetOrdinal = rows.getInt(5);
          var sourceHash = rows.getString(6);
          var storedRowHash = rows.getString(7);
          var values = codec.decode(rows.getBytes(8));
          var reconstructedRowHash = MigrationCanonicalizationRegistry.targetRowHash(
              kind, new MigrationRelationalRow(
                  schema, table, sourceId, targetOrdinal, values));
          if (!storedRowHash.equals(reconstructedRowHash)) {
            throw new MigrationReconciliationException();
          }
          actual.computeIfAbsent(sourceId, ignored -> new ArrayList<>()).add(
              CanonicalMigrationHasher.sha256(List.of(
                  rowOrdinal, sourceHash, reconstructedRowHash)));
        }
      }
      if (!actual.equals(expected)) {
        throw new MigrationReconciliationException();
      }
    } finally {
      rowIds.free();
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
          var reconstructedRowHash = MigrationCanonicalizationRegistry.targetRowHash(
              kind, new MigrationRelationalRow(
                  schema, table, sourceId, targetOrdinal, values));
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
            checkpoint.sourceDigest(), digest, rowsValid);
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
            + "set staged_count=?, reconstructed_source_digest=?, staged_rows_valid=?, "
            + "updated_at=transaction_timestamp() "
            + "where run_id=? and source_kind=?")) {
      statement.setLong(1, result.stagedCount());
      statement.setString(2, result.reconstructedSourceDigest());
      statement.setBoolean(3, result.stagedRowsValid());
      statement.setObject(4, runId(context));
      statement.setString(5, kind.sourceKind());
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

  private void publishFrozenRows(
      Connection connection,
      ValidatedMigrationContext context,
      PostgresqlMigrationCatalog.Kind kind) throws SQLException {
    try (var statement = connection.prepareStatement(
        "select source_id, target_schema, target_table, target_ordinal, row_payload from "
            + FROZEN_ROW + " where source_kind=? order by source_id, row_ordinal")) {
      statement.setFetchSize(Math.min(context.request().batchSize(), 500));
      statement.setString(1, kind.sourceKind());
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
      PostgresqlMigrationCatalog.Kind kind,
      String frozenSource) throws SQLException {
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
              + frozenSource + " source where source.source_kind=? "
              + "and source.source_id=child." + quoted(sourceKey)
              + "::text)")) {
        statement.setString(1, kind.sourceKind());
        statement.executeUpdate();
      }
    }
    var qualifiedRoot = quoted(prefix(context) + kind.targetSchema()) + "." + quoted(rootTable);
    try (var statement = connection.prepareStatement(
        "delete from " + qualifiedRoot + " target where not exists (select 1 from "
            + frozenSource + " source where source.source_kind=? "
            + "and source.source_id=target." + quoted(rootKey) + ")")) {
      statement.setString(1, kind.sourceKind());
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
      long expectedCount,
      boolean exactCounts) throws SQLException {
    var rootTable = kind.targetTables().getFirst();
    var keyMapping = kind.keyMapping().targetColumn();
    var rootKey = keyMapping.substring(keyMapping.indexOf('.') + 1);
    var qualifiedRoot = quoted(prefix(context) + kind.targetSchema()) + "." + quoted(rootTable);
    boolean rootsEquivalent;
    try (var statement = connection.prepareStatement(
        "select count(*), count(*) filter (where exists (select 1 from " + platform(context)
            + ".persistence_migration_source source where source.run_id=? and source.source_kind=? "
            + "and source.source_id=target." + quoted(rootKey) + ")) from "
            + qualifiedRoot + " target")) {
      statement.setObject(1, runId(context));
      statement.setString(2, kind.sourceKind());
      try (var rows = statement.executeQuery()) {
        rootsEquivalent = rows.next()
            && (!exactCounts || rows.getLong(1) == expectedCount)
            && rows.getLong(1) >= expectedCount && rows.getLong(2) == expectedCount;
      }
    }
    if (!rootsEquivalent) {
      return false;
    }
    var expectedTableCounts = new LinkedHashMap<String, Long>();
    try (var statement = connection.prepareStatement(
        "select source_id, target_schema, target_table, target_ordinal, row_payload from "
            + platform(context) + ".persistence_migration_staged_row "
            + "where run_id=? and source_kind=? order by source_id, row_ordinal")) {
      statement.setFetchSize(Math.min(context.request().batchSize(), 500));
      statement.setObject(1, runId(context));
      statement.setString(2, kind.sourceKind());
      try (var rows = statement.executeQuery()) {
        while (rows.next()) {
          var staged = new StagedMigrationRow(
              rows.getString(1), rows.getString(2), rows.getString(3), rows.getInt(4),
              codec.decode(rows.getBytes(5)));
          if (!verifier.rowEquivalent(connection, prefix(context), kind, staged)) {
            return false;
          }
          expectedTableCounts.merge(staged.targetTable(), 1L, Long::sum);
        }
      }
    }
    for (var table : kind.targetTables()) {
      try (var statement = connection.createStatement();
           var rows = statement.executeQuery("select count(*) from "
               + quoted(prefix(context) + kind.targetSchema()) + "." + quoted(table))) {
        var expectedTableCount = expectedTableCounts.getOrDefault(table, 0L);
        if (!rows.next() || exactCounts && rows.getLong(1) != expectedTableCount
            || !exactCounts && rows.getLong(1) < expectedTableCount) {
          return false;
        }
      }
    }
    return true;
  }

  private boolean relationshipsEquivalent(
      Connection connection,
      ValidatedMigrationContext context,
      PostgresqlMigrationCatalog.Kind kind) throws SQLException {
    var schema = prefix(context) + kind.targetSchema();
    for (var table : kind.targetTables()) {
      var foreignKeys = new LinkedHashMap<String, List<String[]>>();
      try (var keys = connection.getMetaData().getImportedKeys(null, schema, table)) {
        while (keys.next()) {
          foreignKeys.computeIfAbsent(keys.getString("FK_NAME"), ignored -> new ArrayList<>())
              .add(new String[] {
                  keys.getString("FKCOLUMN_NAME"), keys.getString("PKTABLE_SCHEM"),
                  keys.getString("PKTABLE_NAME"), keys.getString("PKCOLUMN_NAME")});
        }
      }
      for (var columns : foreignKeys.values()) {
        columns.sort(java.util.Comparator.comparing(column -> column[0]));
        var parentSchema = columns.getFirst()[1];
        var parentTable = columns.getFirst()[2];
        var join = columns.stream().map(column ->
            "child." + quoted(column[0]) + "=parent." + quoted(column[3]))
            .collect(java.util.stream.Collectors.joining(" and "));
        var nonNull = columns.stream().map(column ->
            "child." + quoted(column[0]) + " is not null")
            .collect(java.util.stream.Collectors.joining(" or "));
        var sql = "select count(*) from " + quoted(schema) + "." + quoted(table)
            + " child left join " + quoted(parentSchema) + "." + quoted(parentTable)
            + " parent on " + join + " where (" + nonNull + ") and parent."
            + quoted(columns.getFirst()[3]) + " is null";
        try (var statement = connection.createStatement();
             var rows = statement.executeQuery(sql)) {
          if (!rows.next() || rows.getLong(1) != 0) {
            return false;
          }
        }
      }
    }
    return true;
  }

  private boolean portQueryEquivalent(
      Connection connection,
      ValidatedMigrationContext context,
      PostgresqlMigrationCatalog.Kind kind,
      boolean includePriorShadowRows) throws SQLException {
    return portQueryVerifiers.verify(
        connection, prefix(context), prefix(context) + "platform", runId(context), kind, codec,
        includePriorShadowRows);
  }

  private void persistKindVerification(
      Connection connection,
      ValidatedMigrationContext context,
      PostgresqlMigrationCatalog.Kind kind,
      boolean typedRowsValid,
      boolean relationshipsValid,
      boolean portQueriesValid) throws SQLException {
    try (var statement = connection.prepareStatement(
        "update " + platform(context) + ".persistence_migration_kind set "
            + "typed_rows_valid=?, relationships_valid=?, port_queries_valid=?, "
            + "updated_at=transaction_timestamp() where run_id=? and source_kind=?")) {
      statement.setBoolean(1, typedRowsValid);
      statement.setBoolean(2, relationshipsValid);
      statement.setBoolean(3, portQueriesValid);
      statement.setObject(4, runId(context));
      statement.setString(5, kind.sourceKind());
      requireOne(statement.executeUpdate());
    }
  }

  private void persistSourceVerification(
      Connection connection,
      ValidatedMigrationContext context,
      PostgresqlMigrationCatalog.Kind kind,
      long expectedSourceCount,
      String status) throws SQLException {
    var rowsBySource = new LinkedHashMap<String, List<MigrationRelationalRow>>();
    try (var statement = connection.prepareStatement(
        "select source_id, target_schema, target_table, target_ordinal, row_payload from "
            + platform(context) + ".persistence_migration_staged_row "
            + "where run_id=? and source_kind=? order by source_id, row_ordinal")) {
      statement.setObject(1, runId(context));
      statement.setString(2, kind.sourceKind());
      try (var rows = statement.executeQuery()) {
        while (rows.next()) {
          var sourceId = rows.getString(1);
          rowsBySource.computeIfAbsent(sourceId, ignored -> new ArrayList<>()).add(
              new MigrationRelationalRow(
                  rows.getString(2), rows.getString(3), sourceId, rows.getInt(4),
                  codec.decode(rows.getBytes(5))));
        }
      }
    }
    if (rowsBySource.size() != expectedSourceCount) {
      throw new MigrationReconciliationException();
    }
    try (var statement = connection.prepareStatement(
        "update " + platform(context) + ".persistence_migration_source "
            + "set target_hash=?, status=? where run_id=? and source_kind=? and source_id=?")) {
      for (var entry : rowsBySource.entrySet()) {
        statement.setString(
            1, MigrationCanonicalizationRegistry.targetDocumentHash(kind, entry.getValue()));
        statement.setString(2, status);
        statement.setObject(3, runId(context));
        statement.setString(4, kind.sourceKind());
        statement.setString(5, entry.getKey());
        requireOne(statement.executeUpdate());
      }
    }
  }

  private void markKindVerificationFailed(
      Connection connection,
      ValidatedMigrationContext context,
      PostgresqlMigrationCatalog.Kind kind) throws SQLException {
    try (var statement = connection.prepareStatement(
        "update " + platform(context) + ".persistence_migration_source "
            + "set target_hash=null, status='FAILED' where run_id=? and source_kind=?")) {
      statement.setObject(1, runId(context));
      statement.setString(2, kind.sourceKind());
      statement.executeUpdate();
    }
    try (var statement = connection.prepareStatement(
        "update " + platform(context) + ".persistence_migration_kind "
            + "set published=false, published_count=0, published_at=null, "
            + "updated_at=transaction_timestamp() where run_id=? and source_kind=?")) {
      statement.setObject(1, runId(context));
      statement.setString(2, kind.sourceKind());
      requireOne(statement.executeUpdate());
    }
  }

  private void markSourcesPublished(
      Connection connection,
      ValidatedMigrationContext context,
      PostgresqlMigrationCatalog.Kind kind,
      long expectedSourceCount) throws SQLException {
    try (var statement = connection.prepareStatement(
        "update " + platform(context) + ".persistence_migration_source "
            + "set status='PUBLISHED' where run_id=? and source_kind=? "
            + "and status='VERIFIED' and target_hash is not null")) {
      statement.setObject(1, runId(context));
      statement.setString(2, kind.sourceKind());
      if (statement.executeUpdate() != expectedSourceCount) {
        throw new MigrationReconciliationException();
      }
    }
  }

  private void invalidateKindVerification(
      Connection connection,
      ValidatedMigrationContext context,
      PostgresqlMigrationCatalog.Kind kind) throws SQLException {
    try (var statement = connection.prepareStatement(
        "update " + platform(context) + ".persistence_migration_source "
            + "set target_hash=null, status='STAGED' where run_id=? and source_kind=?")) {
      statement.setObject(1, runId(context));
      statement.setString(2, kind.sourceKind());
      statement.executeUpdate();
    }
    try (var statement = connection.prepareStatement(
        "update " + platform(context) + ".persistence_migration_kind set "
            + "staged_count=null, reconstructed_source_digest=null, staged_rows_valid=null, "
            + "typed_rows_valid=null, relationships_valid=null, port_queries_valid=null, "
            + "published=false, published_count=0, published_at=null, "
            + "updated_at=transaction_timestamp() where run_id=? and source_kind=?")) {
      statement.setObject(1, runId(context));
      statement.setString(2, kind.sourceKind());
      statement.executeUpdate();
    }
  }

  private void markRunUnpublished(
      Connection connection,
      ValidatedMigrationContext context,
      String status) throws SQLException {
    try (var statement = connection.prepareStatement(
        "update " + platform(context) + ".persistence_migration_run "
            + "set status=?, completed_at=null where run_id=?")) {
      statement.setString(1, status);
      statement.setObject(2, runId(context));
      requireOne(statement.executeUpdate());
    }
  }

  private void markRunPublished(
      Connection connection,
      ValidatedMigrationContext context,
      OffsetDateTime completedAt) throws SQLException {
    try (var statement = connection.prepareStatement(
        "update " + platform(context) + ".persistence_migration_run "
            + "set status='PUBLISHED', completed_at=? where run_id=?")) {
      statement.setObject(1, completedAt);
      statement.setObject(2, runId(context));
      requireOne(statement.executeUpdate());
    }
  }

  private void markPublished(
      Connection connection,
      ValidatedMigrationContext context,
      PostgresqlMigrationCatalog.Kind kind,
      long sourceCount) throws SQLException {
    try (var statement = connection.prepareStatement(
        "update " + platform(context) + ".persistence_migration_kind "
            + "set published=true, published_count=?, published_at=transaction_timestamp(), "
            + "updated_at=transaction_timestamp() where run_id=? and source_kind=?")) {
      statement.setLong(1, sourceCount);
      statement.setObject(2, runId(context));
      statement.setString(3, kind.sourceKind());
      requireOne(statement.executeUpdate());
    }
  }

  private final class FrozenSnapshotWriter {
    private final Connection connection;
    private final HashMap<String, Long> nextSequence = new HashMap<>();

    private FrozenSnapshotWriter(Connection connection) {
      this.connection = connection;
    }

    private void accept(
        PostgresqlMigrationCatalog.Kind kind,
        List<TransformedMigrationDocument> documents) {
      try {
        for (var document : documents) {
          insert(kind, document);
        }
      } catch (SQLException failure) {
        throw new MigrationStorageException(failure);
      }
    }

    private void insert(
        PostgresqlMigrationCatalog.Kind kind,
        TransformedMigrationDocument document) throws SQLException {
      if (!kind.sourceKind().equals(document.sourceKind()) || document.rows().isEmpty()) {
        throw new SQLException("Frozen transformed document does not match its kind.");
      }
      var sequence = nextSequence.getOrDefault(kind.sourceKind(), 0L);
      try (var statement = connection.prepareStatement(
          "insert into " + FROZEN_SOURCE + " (source_kind, source_id, transformer_version, "
              + "source_hash, staged_sequence) values (?, ?, ?, ?, ?)")) {
        statement.setString(1, kind.sourceKind());
        statement.setString(2, document.sourceId());
        statement.setInt(3, kind.transformerVersion());
        statement.setString(4, document.sourceHash());
        statement.setLong(5, sequence);
        requireOne(statement.executeUpdate());
      }
      nextSequence.put(kind.sourceKind(), sequence + 1);
      for (var rowIndex = 0; rowIndex < document.rows().size(); rowIndex++) {
        var row = document.rows().get(rowIndex);
        if (!document.sourceId().equals(row.sourceId())) {
          throw new SQLException("Frozen relational row does not match its source document.");
        }
        try (var statement = connection.prepareStatement(
            "insert into " + FROZEN_ROW + " (source_kind, source_id, row_ordinal, "
                + "target_ordinal, target_schema, target_table, source_hash, row_hash, "
                + "row_payload) values (?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
          statement.setString(1, kind.sourceKind());
          statement.setString(2, document.sourceId());
          statement.setInt(3, rowIndex);
          statement.setInt(4, row.ordinal());
          statement.setString(5, row.targetSchema());
          statement.setString(6, row.targetTable());
          statement.setString(7, document.sourceHash());
          statement.setString(8, MigrationCanonicalizationRegistry.targetRowHash(kind, row));
          statement.setBytes(9, codec.encode(row.values()));
          requireOne(statement.executeUpdate());
        }
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
        if (failure instanceof MigrationReconciliationException reconciliation) {
          throw reconciliation;
        }
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
