package dev.christopherbell.account.api;

import static dev.christopherbell.configuration.persistence.PostgresqlMigrationVerificationSupport.database;
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

/** Published account-module adapter operations used by cutover parity. */
@PostgresPersistenceSupport
public final class AccountMigrationVerifier {
  private AccountMigrationVerifier() {}

  public static boolean verify(
      Connection connection, String schema, String sourceKind, String queryName,
      List<Map<String, Object>> rows) {
    var context = database(connection, schema);
    return switch (sourceKind + "/" + queryName) {
      case "account/find-by-id" -> verifyOptionalLookup(
          rows, "account_id", new PostgresAccountRepository(context)::findById);
      case "account/find-by-email" -> verifyEmails(context, rows);
      case "account/find-by-username" -> verifyUsernames(context, rows);
      case "account/federation-actor-page" -> verifyFederationActors(context, rows);
      case "account_deletion_job/find-by-id" -> verifyOptionalLookup(
          rows, "account_deletion_job_id",
          new PostgresAccountDeletionJobRepository(context)::findById);
      case "account_follow/follow-exists" -> verifyFollow(context, rows);
      case "account_trust_relationship/relationship-exists" -> verifyTrust(context, rows);
      default -> false;
    };
  }

  private static boolean verifyEmails(
      org.jooq.DSLContext context, List<Map<String, Object>> rows) {
    var repository = new PostgresAccountRepository(context);
    return rows.stream().allMatch(row -> repository.findByEmail(text(row.get("email")))
        .map(account -> account.getId().equals(text(row.get("account_id"))))
        .orElse(false))
        && repository.findByEmail("migration-verifier-missing@example.test").isEmpty();
  }

  private static boolean verifyUsernames(
      org.jooq.DSLContext context, List<Map<String, Object>> rows) {
    var repository = new PostgresAccountRepository(context);
    return rows.stream().allMatch(row -> repository.findByUsername(text(row.get("username")))
        .map(account -> account.getId().equals(text(row.get("account_id"))))
        .orElse(false))
        && repository.findByUsername("migration-verifier-missing-username").isEmpty();
  }

  private static boolean verifyFederationActors(
      org.jooq.DSLContext context, List<Map<String, Object>> rows) {
    var repository = new PostgresAccountRepository(context);
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
      org.jooq.DSLContext context, List<Map<String, Object>> rows) {
    var store = new PostgresAccountFollowStore(context);
    return rows.stream().allMatch(row -> store.exists(
        text(row.get("follower_account_id")), text(row.get("followed_account_id"))))
        && !store.exists("migration-verifier-missing-follower", "migration-verifier-missing-followed");
  }

  private static boolean verifyTrust(
      org.jooq.DSLContext context, List<Map<String, Object>> rows) {
    var repository = new PostgresAccountTrustRepository(context);
    return rows.stream().allMatch(row -> repository.existsByOwnerAccountIdAndTargetAccountIdAndType(
        text(row.get("owner_account_id")), text(row.get("target_account_id")),
        AccountTrustType.valueOf(text(row.get("trust_type")))))
        && !repository.existsByOwnerAccountIdAndTargetAccountIdAndType(
            "migration-verifier-missing-owner", "migration-verifier-missing-target",
            AccountTrustType.BLOCK);
  }
}
