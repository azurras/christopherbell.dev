package dev.christopherbell.account.api;

import static dev.christopherbell.configuration.persistence.PostgresqlMigrationVerificationSupport.database;
import static dev.christopherbell.configuration.persistence.PostgresqlMigrationVerificationSupport.text;
import static dev.christopherbell.configuration.persistence.PostgresqlMigrationVerificationSupport.verifyOptionalLookup;

import dev.christopherbell.account.PostgresAccountRepository;
import dev.christopherbell.account.deletion.PostgresAccountDeletionJobRepository;
import dev.christopherbell.account.follow.PostgresAccountFollowStore;
import dev.christopherbell.account.trust.PostgresAccountTrustRepository;
import dev.christopherbell.account.trust.model.AccountTrustType;
import dev.christopherbell.configuration.persistence.PostgresPersistenceSupport;
import java.sql.Connection;
import java.util.List;
import java.util.Map;

/** Published account-module adapter operations used by cutover parity. */
@PostgresPersistenceSupport
public final class AccountMigrationVerifier {
  private AccountMigrationVerifier() {}

  public static boolean verify(
      Connection connection, String schema, String sourceKind,
      List<Map<String, Object>> rows) {
    var context = database(connection, schema);
    return switch (sourceKind) {
      case "account" -> verifyOptionalLookup(
          rows, "account_id", new PostgresAccountRepository(context)::findById);
      case "account_deletion_job" -> verifyOptionalLookup(
          rows, "account_deletion_job_id",
          new PostgresAccountDeletionJobRepository(context)::findById);
      case "account_follow" -> verifyFollow(context, rows);
      case "account_trust_relationship" -> verifyTrust(context, rows);
      default -> false;
    };
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
