package dev.christopherbell.account.api;

import static dev.christopherbell.configuration.persistence.PostgresqlMigrationVerificationSupport.text;
import static dev.christopherbell.configuration.persistence.PostgresqlMigrationVerificationSupport.verifyOptionalLookup;

import dev.christopherbell.account.PostgresAccountRepository;
import dev.christopherbell.account.deletion.PostgresAccountDeletionJobRepository;
import dev.christopherbell.account.follow.PostgresAccountFollowStore;
import dev.christopherbell.account.model.AccountStatus;
import dev.christopherbell.account.trust.PostgresAccountTrustRepository;
import dev.christopherbell.account.trust.model.AccountTrustType;
import dev.christopherbell.configuration.persistence.PostgresPersistenceSupport;
import java.sql.Connection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.transaction.support.TransactionOperations;

/** Published account-module adapter operations used by cutover parity. */
@PostgresPersistenceSupport
public final class AccountMigrationVerifier {
  private AccountMigrationVerifier() {}

  public static boolean verify(
      Connection connection, String schema, String sourceKind, String queryName,
      List<Map<String, Object>> rows) {
    var jdbc = JdbcClient.create(new SingleConnectionDataSource(connection, true));
    var schemas = dev.christopherbell.configuration.persistence.PostgresqlSchemaNames
        .fromPhysicalSchema(schema);
    var accounts = new PostgresAccountRepository(
        jdbc, schemas, TransactionOperations.withoutTransaction());
    return switch (sourceKind + "/" + queryName) {
      case "account/find-by-id" -> verifyOptionalLookup(
          rows, "account_id", accounts::findById);
      case "account/find-by-email" -> verifyEmails(accounts, rows);
      case "account/find-by-username" -> verifyUsernames(accounts, rows);
      case "account/federation-actor-page" -> verifyFederationActors(accounts, rows);
      case "account_deletion_job/find-by-id" -> verifyOptionalLookup(
          rows, "account_deletion_job_id",
          new PostgresAccountDeletionJobRepository(
              org.springframework.jdbc.core.simple.JdbcClient.create(
                  new org.springframework.jdbc.datasource.SingleConnectionDataSource(
                      connection, true)),
              dev.christopherbell.configuration.persistence.PostgresqlSchemaNames
                  .fromPhysicalSchema(schema))::findById);
      case "account_follow/follow-exists" -> verifyFollow(jdbc, schemas, rows);
      case "account_trust_relationship/relationship-exists" -> verifyTrust(
          jdbc, schemas, rows);
      default -> false;
    };
  }

  private static boolean verifyEmails(
      PostgresAccountRepository repository, List<Map<String, Object>> rows) {
    return rows.stream().allMatch(row -> repository.findByEmail(text(row.get("email")))
        .map(account -> account.getId().equals(text(row.get("account_id"))))
        .orElse(false))
        && repository.findByEmail("migration-verifier-missing@example.test").isEmpty();
  }

  private static boolean verifyUsernames(
      PostgresAccountRepository repository, List<Map<String, Object>> rows) {
    return rows.stream().allMatch(row -> repository.findByUsername(text(row.get("username")))
        .map(account -> account.getId().equals(text(row.get("account_id"))))
        .orElse(false))
        && repository.findByUsername("migration-verifier-missing-username").isEmpty();
  }

  private static boolean verifyFederationActors(
      PostgresAccountRepository repository, List<Map<String, Object>> rows) {
    for (var row : rows) {
      var expected = "ACTIVE".equals(text(row.get("status")))
          && Boolean.TRUE.equals(row.get("federation_enabled"));
      var actual = repository.findByUsernameIgnoreCaseAndStatusAndFederationEnabledTrue(
          text(row.get("username")).toUpperCase(Locale.ROOT), AccountStatus.ACTIVE);
      if (actual.isPresent() != expected
          || actual.isPresent() && !actual.orElseThrow().getId().equals(text(row.get("account_id")))) {
        return false;
      }
    }
    return repository.findByUsernameIgnoreCaseAndStatusAndFederationEnabledTrue(
        "migration-verifier-missing-username", AccountStatus.ACTIVE).isEmpty();
  }

  private static boolean verifyFollow(
      JdbcClient jdbc,
      dev.christopherbell.configuration.persistence.PostgresqlSchemaNames schemas,
      List<Map<String, Object>> rows) {
    var store = new PostgresAccountFollowStore(jdbc, schemas);
    return rows.stream().allMatch(row -> store.exists(
        text(row.get("follower_account_id")), text(row.get("followed_account_id"))))
        && !store.exists("migration-verifier-missing-follower", "migration-verifier-missing-followed");
  }

  private static boolean verifyTrust(
      JdbcClient jdbc,
      dev.christopherbell.configuration.persistence.PostgresqlSchemaNames schemas,
      List<Map<String, Object>> rows) {
    var repository = new PostgresAccountTrustRepository(jdbc, schemas);
    return rows.stream().allMatch(row -> repository.existsByOwnerAccountIdAndTargetAccountIdAndType(
        text(row.get("owner_account_id")), text(row.get("target_account_id")),
        AccountTrustType.valueOf(text(row.get("trust_type")))))
        && !repository.existsByOwnerAccountIdAndTargetAccountIdAndType(
            "migration-verifier-missing-owner", "migration-verifier-missing-target",
            AccountTrustType.BLOCK);
  }
}
