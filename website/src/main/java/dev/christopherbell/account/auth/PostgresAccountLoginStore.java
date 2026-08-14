package dev.christopherbell.account.auth;

import static dev.christopherbell.persistence.jooq.identity.Tables.ACCOUNT;

import dev.christopherbell.account.AccountRepository;
import dev.christopherbell.account.PostgresAccountRepository;
import dev.christopherbell.account.model.Account;
import dev.christopherbell.account.model.AccountStatus;
import dev.christopherbell.configuration.persistence.PostgresPersistence;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.jooq.DSLContext;

/** PostgreSQL conditional login update that rejects stale observed credentials. */
@PostgresPersistence
public class PostgresAccountLoginStore implements AccountLoginStore {
  private final DSLContext database;
  private final AccountRepository accounts;

  public PostgresAccountLoginStore(DSLContext database) {
    this.database = database;
    this.accounts = new PostgresAccountRepository(database);
  }

  @Override
  public Optional<Account> completeLogin(
      Account observed, String passwordHash, Instant loginOn) {
    var updated = database.update(ACCOUNT)
        .set(ACCOUNT.LAST_LOGIN_ON, loginOn.atOffset(ZoneOffset.UTC))
        .set(ACCOUNT.PASSWORD_HASH, passwordHash)
        .setNull(ACCOUNT.PASSWORD_SALT)
        .set(ACCOUNT.VERSION, ACCOUNT.VERSION.plus(1L))
        .where(ACCOUNT.ACCOUNT_ID.eq(observed.getId()))
        .and(ACCOUNT.STATUS.eq(AccountStatus.ACTIVE.name()))
        .and(ACCOUNT.PASSWORD_HASH.isNotDistinctFrom(observed.getPasswordHash()))
        .and(ACCOUNT.PASSWORD_SALT.isNotDistinctFrom(observed.getPasswordSalt()))
        .execute();
    return updated == 1 ? accounts.findById(observed.getId()) : Optional.empty();
  }
}
