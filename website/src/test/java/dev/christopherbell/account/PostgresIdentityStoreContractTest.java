package dev.christopherbell.account;

import static org.assertj.core.api.Assertions.assertThat;

import dev.christopherbell.account.auth.PostgresAccountLoginStore;
import dev.christopherbell.account.deletion.AccountDeletionJob;
import dev.christopherbell.account.deletion.PostgresAccountDeletionJobRepository;
import dev.christopherbell.account.follow.PostgresAccountFollowStore;
import dev.christopherbell.account.model.Account;
import dev.christopherbell.account.model.AccountStatus;
import dev.christopherbell.account.model.Role;
import dev.christopherbell.account.trust.AccountTrustRelationship;
import dev.christopherbell.account.trust.PostgresAccountTrustRepository;
import dev.christopherbell.account.trust.model.AccountTrustType;
import dev.christopherbell.configuration.postgresql.Task3PostgresqlTestSupport;
import dev.christopherbell.configuration.security.browser.BrowserSession;
import dev.christopherbell.configuration.security.browser.PostgresBrowserSessionActivityStore;
import dev.christopherbell.configuration.security.browser.PostgresBrowserSessionAuthenticationStore;
import dev.christopherbell.configuration.security.browser.PostgresBrowserSessionRepository;
import java.time.Instant;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

@EnabledIfEnvironmentVariable(named = "POSTGRESQL_INTEGRATION_TESTS", matches = "enabled")
class PostgresIdentityStoreContractTest {
  private static Task3PostgresqlTestSupport schemas;
  private static Task3PostgresqlTestSupport.Database database;
  private static PostgresAccountRepository accounts;

  @BeforeAll
  static void migrateDatabase() throws Exception {
    schemas = Task3PostgresqlTestSupport.migrate();
    database = schemas.openDatabase();
    accounts = new PostgresAccountRepository(
        database.managedJdbc(), database.schemas(), database.transactions());
    accounts.save(account("identity-a", "a@example.test", "alpha"));
    accounts.save(account("identity-b", "b@example.test", "beta"));
  }

  @AfterAll
  static void cleanupDatabase() throws Exception {
    if (database != null) database.close();
    if (schemas != null) schemas.close();
  }

  @Test
  void followAndTrustEdgesPreserveDuplicateAndLookupSemantics() {
    var follows = new PostgresAccountFollowStore(database.jdbc(), database.schemas());
    var trust = new PostgresAccountTrustRepository(database.jdbc(), database.schemas());
    var now = Instant.parse("2026-08-13T13:00:00Z");

    assertThat(follows.follow("identity-a", "identity-b", now).created()).isTrue();
    assertThat(follows.follow("identity-a", "identity-b", now).created()).isFalse();
    assertThat(follows.exists("identity-a", "identity-b")).isTrue();
    assertThat(follows.countFollowing("identity-a")).isOne();
    assertThat(follows.countFollowers("identity-b")).isOne();

    var relationship = AccountTrustRelationship.builder()
        .id("trust-a-b")
        .ownerAccountId("identity-a")
        .targetAccountId("identity-b")
        .type(AccountTrustType.BLOCK)
        .createdOn(now)
        .build();
    assertThat(trust.save(relationship)).isEqualTo(relationship);
    assertThat(trust.findByOwnerAccountIdAndTargetAccountIdAndType(
        "identity-a", "identity-b", AccountTrustType.BLOCK)).contains(relationship);
    assertThat(follows.unfollow("identity-a", "identity-b").removed()).isTrue();
  }

  @Test
  void loginAndBrowserActivityRejectStaleObservedState() {
    var observed = accounts.findById("identity-a").orElseThrow();
    observed.setPasswordSalt("salt-a");
    observed.setPasswordHash("legacy-hash");
    accounts.save(observed);
    var logins = new PostgresAccountLoginStore(
        database.managedJdbc(), database.schemas(), database.transactions());
    var loginOn = Instant.parse("2026-08-13T13:01:00Z");

    var completed = logins.completeLogin(observed, "current-hash", loginOn).orElseThrow();
    assertThat(completed.getPasswordHash()).isEqualTo("current-hash");
    assertThat(completed.getPasswordSalt()).isNull();
    assertThat(logins.completeLogin(observed, "wrong", loginOn.plusSeconds(1))).isEmpty();

    var sessions = database.bean(PostgresBrowserSessionRepository.class);
    var activity = new PostgresBrowserSessionActivityStore(database.jdbc(), database.schemas());
    var authentication = new PostgresBrowserSessionAuthenticationStore(
        database.jdbc(), database.schemas());
    var session = BrowserSession.builder()
        .id("session-a")
        .accountId("identity-a")
        .role(Role.USER)
        .tokenHash("token-a")
        .accountSecurityFingerprint("fingerprint")
        .createdOn(loginOn)
        .lastSeenOn(loginOn)
        .idleExpiresOn(loginOn.plusSeconds(600))
        .absoluteExpiresOn(loginOn.plusSeconds(3600))
        .build();
    sessions.save(session);
    assertThat(authentication.findById("session-a")).isPresent();
    assertThat(activity.touch(
        "session-a", loginOn, loginOn.plusSeconds(10), loginOn.plusSeconds(610))).isPresent();
    assertThat(activity.touch(
        "session-a", loginOn, loginOn.plusSeconds(20), loginOn.plusSeconds(620))).isEmpty();
  }

  @Test
  void deletionJobRoundTripsLifecycleCheckpoint() {
    var jobs = new PostgresAccountDeletionJobRepository(database.jdbc(), database.schemas());
    var job = AccountDeletionJob.started("deletion-pseudonym");
    job.setCreatedOn(Instant.parse("2026-08-13T13:10:00Z"));
    job.setLastUpdatedOn(Instant.parse("2026-08-13T13:10:00Z"));
    jobs.save(job);

    assertThat(jobs.findById(job.getId())).contains(job);
  }

  private static Account account(String id, String email, String username) {
    return Account.builder()
        .id(id)
        .createdOn(Instant.parse("2026-08-13T12:00:00Z"))
        .email(email)
        .passwordHash("hash")
        .role(Role.USER)
        .status(AccountStatus.ACTIVE)
        .username(username)
        .build();
  }
}
