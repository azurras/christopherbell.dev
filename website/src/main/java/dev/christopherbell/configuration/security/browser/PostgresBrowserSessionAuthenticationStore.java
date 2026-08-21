package dev.christopherbell.configuration.security.browser;

import static dev.christopherbell.persistence.jooq.identity.Tables.ACCOUNT;
import static dev.christopherbell.persistence.jooq.identity.Tables.ACCOUNT_PERMISSION;
import static dev.christopherbell.persistence.jooq.identity.Tables.BROWSER_SESSION;

import dev.christopherbell.account.model.AccountPermission;
import dev.christopherbell.account.model.AccountStatus;
import dev.christopherbell.account.model.Role;
import dev.christopherbell.configuration.persistence.PostgresPersistence;
import java.util.Optional;
import java.util.Set;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;

/** PostgreSQL join for a browser session and current account security state. */
@PostgresPersistence
public class PostgresBrowserSessionAuthenticationStore
    implements BrowserSessionAuthenticationStore {
  private final DSLContext database;

  public PostgresBrowserSessionAuthenticationStore(DSLContext database) {
    this.database = database;
  }

  @Override
  public Optional<BrowserSessionAuthentication> findById(String sessionId) {
    var permissions = DSL.multiset(database.select(ACCOUNT_PERMISSION.PERMISSION)
            .from(ACCOUNT_PERMISSION)
            .where(ACCOUNT_PERMISSION.ACCOUNT_ID.eq(ACCOUNT.ACCOUNT_ID)))
        .as("permissions");
    return database.select(BROWSER_SESSION.asterisk(), ACCOUNT.asterisk(), permissions)
        .from(BROWSER_SESSION)
        .join(ACCOUNT).on(ACCOUNT.ACCOUNT_ID.eq(BROWSER_SESSION.ACCOUNT_ID))
        .where(BROWSER_SESSION.BROWSER_SESSION_ID.eq(sessionId))
        .limit(1)
        .fetchOptional(record -> {
          var session = PostgresBrowserSessionMapper.map(record.into(BROWSER_SESSION));
          Set<AccountPermission> currentPermissions = record.get(permissions).stream()
              .map(row -> AccountPermission.valueOf(row.value1()))
              .collect(java.util.stream.Collectors.toUnmodifiableSet());
          var account = new BrowserSessionAccount(
              record.get(ACCOUNT.ACCOUNT_ID),
              record.get(ACCOUNT.PASSWORD_HASH),
              Role.valueOf(record.get(ACCOUNT.ROLE)),
              currentPermissions,
              AccountStatus.valueOf(record.get(ACCOUNT.STATUS)));
          return new BrowserSessionAuthentication(session, account);
        });
  }
}
