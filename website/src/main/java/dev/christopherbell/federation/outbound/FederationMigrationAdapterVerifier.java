package dev.christopherbell.federation.outbound;

import static dev.christopherbell.configuration.persistence.PostgresqlMigrationVerificationSupport.instant;
import static dev.christopherbell.configuration.persistence.PostgresqlMigrationVerificationSupport.rollback;
import static dev.christopherbell.configuration.persistence.PostgresqlMigrationVerificationSupport.text;

import dev.christopherbell.configuration.persistence.PostgresPersistenceSupport;
import dev.christopherbell.federation.configuration.FederationOutboundProperties.ControlledPeer;
import java.net.URI;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Executes federation's production delivery adapter for migration parity. */
@PostgresPersistenceSupport
public final class FederationMigrationAdapterVerifier {
  private FederationMigrationAdapterVerifier() {}

  public static boolean verify(
      Connection connection, String schema, String sourceKind, String queryName,
      List<Map<String, Object>> rows) throws SQLException {
    var database = org.springframework.jdbc.core.simple.JdbcClient.create(
        new org.springframework.jdbc.datasource.SingleConnectionDataSource(connection, true));
    var schemas = dev.christopherbell.configuration.persistence.PostgresqlSchemaNames
        .fromPhysicalSchema(schema);
    var repository = new PostgresFederationDeliveryJobRepository(
        database, schemas,
        org.springframework.transaction.support.TransactionOperations.withoutTransaction());
    return switch (sourceKind + "/" + queryName) {
      case "federation_scan_state/load-cursor" -> verifyCursor(repository, rows);
      case "federation_delivery_job/claim-due" ->
          verifyClaim(connection, database, schemas, repository, rows);
      case "federation_delivery_job/enqueue-if-absent" ->
          verifyEnqueue(connection, database, schemas, repository, rows);
      default -> false;
    };
  }

  private static boolean verifyCursor(
      PostgresFederationDeliveryJobRepository repository,
      List<Map<String, Object>> rows) {
    var expected = rows.stream()
        .filter(row -> FederationScanState.OUTBOUND_CREATE.equals(text(row.get("scan_state_id"))))
        .findFirst();
    var actual = repository.loadCursor();
    if (expected.isEmpty()) {
      return actual == null;
    }
    var row = expected.orElseThrow();
    return actual != null
        && java.util.Objects.equals(actual.createdOn(), instant(row.get("created_on")))
        && java.util.Objects.equals(actual.postId(), text(row.get("post_id")));
  }

  private static boolean verifyClaim(
      Connection connection,
      org.springframework.jdbc.core.simple.JdbcClient database,
      dev.christopherbell.configuration.persistence.PostgresqlSchemaNames schemas,
      PostgresFederationDeliveryJobRepository repository,
      List<Map<String, Object>> rows) throws SQLException {
    if (rows.isEmpty()) {
      return true;
    }
    var id = text(rows.getFirst().get("delivery_job_id"));
    return rollback(connection, () -> {
      database.sql("""
              update %s set state = 'PENDING', next_attempt_on = :epoch,
                claim_owner = null, claim_until = null where delivery_job_id = :id
              """.formatted(schemas.qualifiedTable("federation", "federation_delivery_job")))
          .param("epoch", Instant.EPOCH.atOffset(java.time.ZoneOffset.UTC))
          .param("id", id).update();
      var now = Instant.now();
      var claim = repository.claimDue(
          "migration-verifier-owner", now, now.plus(Duration.ofMinutes(1)));
      return claim.isPresent() && id.equals(claim.orElseThrow().id())
          && repository.claimDue(
              "migration-verifier-second-owner", now, now.plus(Duration.ofMinutes(1))).isEmpty();
    });
  }

  private static boolean verifyEnqueue(
      Connection connection,
      org.springframework.jdbc.core.simple.JdbcClient database,
      dev.christopherbell.configuration.persistence.PostgresqlSchemaNames schemas,
      PostgresFederationDeliveryJobRepository repository,
      List<Map<String, Object>> rows) throws SQLException {
    if (rows.isEmpty()) {
      return true;
    }
    var row = rows.getFirst();
    return rollback(connection, () -> {
      var before = database.sql("select count(*) from %s".formatted(
              schemas.qualifiedTable("federation", "federation_delivery_job")))
          .query(Long.class).single();
      var peer = new ControlledPeer(
          "migration-verifier-peer", URI.create(text(row.get("peer_inbox"))));
      var now = Instant.now();
      repository.enqueueIfAbsent(
          text(row.get("post_id")), text(row.get("account_id")), peer, now);
      repository.enqueueIfAbsent(
          text(row.get("post_id")), text(row.get("account_id")), peer, now);
      var after = database.sql("select count(*) from %s".formatted(
              schemas.qualifiedTable("federation", "federation_delivery_job")))
          .query(Long.class).single();
      return after == before + 1;
    });
  }
}
