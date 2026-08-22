package dev.christopherbell.account.auth;

import dev.christopherbell.account.AccountRepository;
import dev.christopherbell.account.PostgresAccountRepository;
import dev.christopherbell.account.model.Account;
import dev.christopherbell.configuration.persistence.PostgresPersistence;
import dev.christopherbell.configuration.persistence.PostgresqlSchemaNames;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionOperations;

/** PostgreSQL conditional login update that rejects stale observed credentials. */
@PostgresPersistence
public class PostgresAccountLoginStore implements AccountLoginStore {
  private final JdbcClient database;
  private final AccountRepository accounts;
  private final String table;

  public PostgresAccountLoginStore(
      JdbcClient database, PostgresqlSchemaNames schemas, TransactionOperations transactions) {
    this.database = database;
    accounts = new PostgresAccountRepository(database, schemas, transactions);
    table = schemas.qualifiedTable("identity", "account");
  }

  @Override
  public Optional<Account> completeLogin(
      Account observed, String passwordHash, Instant loginOn) {
    var updated = database.sql("""
            update %s set
              last_login_on = :loginOn,
              password_hash = :passwordHash,
              password_salt = null,
              version = version + 1
            where account_id = :accountId
              and status = 'ACTIVE'
              and password_hash is not distinct from :observedHash
              and password_salt is not distinct from :observedSalt
            """.formatted(table))
        .param("loginOn", loginOn.atOffset(ZoneOffset.UTC))
        .param("passwordHash", passwordHash)
        .param("accountId", observed.getId())
        .param("observedHash", observed.getPasswordHash())
        .param("observedSalt", observed.getPasswordSalt(), java.sql.Types.VARCHAR)
        .update();
    return updated == 1 ? accounts.findById(observed.getId()) : Optional.empty();
  }
}
