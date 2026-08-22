package dev.christopherbell.configuration.security.browser;

import dev.christopherbell.account.model.AccountPermission;
import dev.christopherbell.account.model.AccountStatus;
import dev.christopherbell.account.model.Role;
import dev.christopherbell.configuration.persistence.PostgresPersistence;
import dev.christopherbell.configuration.persistence.PostgresqlSchemaNames;
import java.util.Optional;
import java.util.Set;
import org.springframework.jdbc.core.simple.JdbcClient;

/** PostgreSQL join for a browser session and current account security state. */
@PostgresPersistence
public class PostgresBrowserSessionAuthenticationStore
    implements BrowserSessionAuthenticationStore {
  private final JdbcClient database;
  private final String browserSessionTable;
  private final String accountTable;
  private final String permissionTable;

  public PostgresBrowserSessionAuthenticationStore(
      JdbcClient database, PostgresqlSchemaNames schemas) {
    this.database = database;
    browserSessionTable = schemas.qualifiedTable("identity", "browser_session");
    accountTable = schemas.qualifiedTable("identity", "account");
    permissionTable = schemas.qualifiedTable("identity", "account_permission");
  }

  @Override
  public Optional<BrowserSessionAuthentication> findById(String sessionId) {
    return database.sql("""
            select browser_session.*,
              account.account_id as current_account_id,
              account.password_hash as current_password_hash,
              account.role as current_role,
              account.status as current_status
            from %s browser_session
            join %s account on account.account_id = browser_session.account_id
            where browser_session.browser_session_id = :sessionId
            limit 1
            """.formatted(browserSessionTable, accountTable))
        .param("sessionId", sessionId)
        .query((record, rowNumber) -> {
          var session = PostgresBrowserSessionMapper.map(record, rowNumber);
          var accountId = record.getString("current_account_id");
          Set<AccountPermission> permissions = database.sql("""
                  select permission from %s
                  where account_id = :accountId
                  """.formatted(permissionTable))
              .param("accountId", accountId)
              .query((permission, permissionRow) ->
                  AccountPermission.valueOf(permission.getString("permission")))
              .set();
          var account = new BrowserSessionAccount(
              accountId,
              record.getString("current_password_hash"),
              Role.valueOf(record.getString("current_role")),
              permissions,
              AccountStatus.valueOf(record.getString("current_status")));
          return new BrowserSessionAuthentication(session, account);
        })
        .optional();
  }
}
