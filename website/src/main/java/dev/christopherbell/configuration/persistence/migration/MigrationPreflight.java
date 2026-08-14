package dev.christopherbell.configuration.persistence.migration;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/** Fails closed on command, endpoint, authority, role, and frozen-source drift. */
public final class MigrationPreflight {
  private static final String CONNECTION_REJECTED =
      "PostgreSQL migration preflight rejected connection identity.";
  private static final String DATABASE_REJECTED =
      "PostgreSQL migration preflight rejected database identity.";
  private static final String FROZEN_REJECTED =
      "PostgreSQL migration preflight rejected frozen source evidence.";
  private static final Pattern DIGEST = Pattern.compile("[0-9a-f]{64}");
  private static final Pattern PREFIX = Pattern.compile("cbtest_[a-z0-9_]+_");
  private static final Set<String> LOOPBACK = Set.of("127.0.0.1", "localhost", "::1");
  private static final UUID NIL_UUID = new UUID(0L, 0L);

  private final MigrationIdentityProbe identityProbe;

  public MigrationPreflight(MigrationIdentityProbe identityProbe) {
    this.identityProbe = Objects.requireNonNull(identityProbe, "identityProbe");
  }

  /** Validates cheap untrusted values before making read-only identity probes. */
  public ValidatedMigrationContext validate(MigrationRequest request) {
    requireRequest(request);
    var frozen = requireFrozenEvidence(request);
    var source = identityProbe.sourceIdentity(request);
    var target = identityProbe.targetIdentity(request);
    requireObservedIdentity(request, source, target);
    return new ValidatedMigrationContext(request, source, target, frozen);
  }

  private static void requireRequest(MigrationRequest request) {
    if (request == null
        || request.command() == null
        || request.batchSize() < 1
        || request.batchSize() > 10_000
        || request.lockToken() == null
        || NIL_UUID.equals(request.lockToken())
        || !DIGEST.matcher(value(request.catalogDigest())).matches()
        || value(request.release()).isBlank()
        || request.release().length() > 128) {
      throw connectionRejected();
    }
    var source = parseEndpoint(request.sourceUri(), "mongodb://");
    var target = parseEndpoint(request.targetJdbcUrl(), "jdbc:postgresql://");
    if (!LOOPBACK.contains(source.getHost())
        || !LOOPBACK.contains(target.getHost())
        || source.getPort() < 1
        || target.getPort() < 1) {
      throw connectionRejected();
    }
    var test = "test".equals(request.sourceDatabase())
        && "test".equals(request.targetDatabase());
    var production = "christopherbell".equals(request.sourceDatabase())
        && "christopherbell".equals(request.targetDatabase());
    if (!test && !production) {
      throw connectionRejected();
    }
    if (test && (!"christopherbell_test".equals(request.expectedTargetRole())
        || !PREFIX.matcher(value(request.schemaPrefix())).matches())) {
      throw connectionRejected();
    }
    if (production && (!"christopherbell_bridge".equals(request.expectedTargetRole())
        || !value(request.schemaPrefix()).isEmpty())) {
      throw connectionRejected();
    }
    if (!databasePath(source).equals(request.sourceDatabase())
        || !databasePath(target).equals(request.targetDatabase())) {
      throw connectionRejected();
    }
  }

  private static void requireObservedIdentity(
      MigrationRequest request,
      MigrationDatabaseIdentity source,
      MigrationDatabaseIdentity target) {
    var requestedSource = parseEndpoint(request.sourceUri(), "mongodb://");
    var requestedTarget = parseEndpoint(request.targetJdbcUrl(), "jdbc:postgresql://");
    if (source == null
        || target == null
        || !LOOPBACK.contains(source.host())
        || !LOOPBACK.contains(target.host())
        || source.port() < 1
        || target.port() < 1
        || !requestedSource.getHost().equals(source.host())
        || requestedSource.getPort() != source.port()
        || !requestedTarget.getHost().equals(target.host())
        || requestedTarget.getPort() != target.port()
        || !request.sourceDatabase().equals(source.database())
        || !request.targetDatabase().equals(target.database())
        || !request.expectedTargetRole().equals(target.role())) {
      throw new MigrationPreflightException(DATABASE_REJECTED);
    }
  }

  private static boolean requireFrozenEvidence(MigrationRequest request) {
    if (request.command() != PostgresqlMigrationCommand.FINALIZE) {
      return false;
    }
    var evidence = request.frozenSourceEvidence();
    if (evidence == null
        || !request.release().equals(evidence.release())
        || !request.catalogDigest().equals(evidence.catalogDigest())
        || !request.sourceDatabase().equals(evidence.sourceDatabase())
        || !request.targetDatabase().equals(evidence.targetDatabase())
        || !request.lockToken().equals(evidence.lockToken())
        || !request.sourceUri().equals(evidence.sourceUri())
        || !request.targetJdbcUrl().equals(evidence.targetJdbcUrl())
        || !request.expectedTargetRole().equals(evidence.targetRole())
        || !DIGEST.matcher(value(evidence.sourceDigest())).matches()
        || !DIGEST.matcher(value(evidence.backupDigest())).matches()
        || !DIGEST.matcher(value(evidence.writerLockDigest())).matches()
        || !DIGEST.matcher(value(evidence.evidenceDigest())).matches()
        || !evidence.evidenceDigest().equals(evidence.reconstructedDigest())) {
      throw new MigrationPreflightException(FROZEN_REJECTED);
    }
    return true;
  }

  private static URI parseEndpoint(String value, String prefix) {
    if (value == null || !value.startsWith(prefix)) {
      throw connectionRejected();
    }
    try {
      return new URI(prefix.startsWith("jdbc:") ? value.substring("jdbc:".length()) : value);
    } catch (URISyntaxException failure) {
      throw connectionRejected();
    }
  }

  private static String databasePath(URI uri) {
    var path = uri.getPath();
    return path == null || path.length() < 2 ? "" : path.substring(1);
  }

  private static String value(String value) {
    return value == null ? "" : value;
  }

  private static MigrationPreflightException connectionRejected() {
    return new MigrationPreflightException(CONNECTION_REJECTED);
  }
}
