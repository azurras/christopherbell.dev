package dev.christopherbell.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.christopherbell.account.model.Account;
import dev.christopherbell.account.model.AccountPermission;
import dev.christopherbell.account.model.AccountStatus;
import dev.christopherbell.account.model.Role;
import dev.christopherbell.libs.moderation.ModerationAuditCommand;
import dev.christopherbell.configuration.postgresql.PersistencePortContract;
import dev.christopherbell.configuration.postgresql.Task3PostgresqlTestSupport;
import java.time.Instant;
import java.util.HashSet;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.dao.OptimisticLockingFailureException;

@EnabledIfEnvironmentVariable(named = "POSTGRESQL_INTEGRATION_TESTS", matches = "enabled")
class PostgresAccountRepositoryContractTest
    implements PersistencePortContract<Account>, AccountRepositoryParityContract {
  private static Task3PostgresqlTestSupport schemas;
  private static Task3PostgresqlTestSupport.Database database;
  private static AccountRepository accounts;

  @BeforeAll
  static void migrateDatabase() throws Exception {
    schemas = Task3PostgresqlTestSupport.migrate();
    database = schemas.openDatabase();
    accounts = new PostgresAccountRepository(database.dsl());
  }

  @AfterAll
  static void cleanupDatabase() throws Exception {
    if (database != null) database.close();
    if (schemas != null) schemas.close();
  }

  @BeforeEach
  void removeContractFixtures() {
    accounts.deleteById("account-contract");
    accounts.deleteById("account-case-a");
    accounts.deleteById("account-case-b");
    accounts.deleteById("account-moderation-contract");
    accounts.deleteById("account-two-writer-contract");
  }

  @Test
  void preservesCrudRoundTrip() {
    verifyCrudRoundTrip();
  }

  @Test
  void preservesCaseInsensitiveIdentityAndPermissionQueries() {
    var saved = accounts.save(createFixture());

    assertThat(accounts.findByEmailIgnoreCase("OWNER@EXAMPLE.TEST")).contains(saved);
    assertThat(accounts.findByUsernameIgnoreCase("OWNER")).contains(saved);
    assertThat(accounts.findByUsernameIgnoreCaseAndStatusAndFederationEnabledTrue(
        "OWNER", AccountStatus.ACTIVE)).contains(saved);
    assertThat(accounts.findById(saved.getId()).orElseThrow().getPermissions())
        .containsExactly(AccountPermission.MUSIC_READ);
  }

  @Test
  void preservesCaseDistinctLegacyEmailsWithoutCollapsingAccountIdentity() {
    var first = createFixture();
    first.setId("account-case-a");
    first.setEmail("Legacy.Case@example.test");
    first.setUsername("legacy-case-a");
    var second = createFixture();
    second.setId("account-case-b");
    second.setEmail("legacy.case@example.test");
    second.setUsername("legacy-case-b");

    accounts.save(first);
    accounts.save(second);

    assertThat(accounts.findByEmail(first.getEmail()).map(Account::getId))
        .contains(first.getId());
    assertThat(accounts.findByEmail(second.getEmail()).map(Account::getId))
        .contains(second.getId());
  }

  @Test
  void pendingModerationAuditSurvivesUntilExplicitlyCompleted() throws Exception {
    var account = createFixture();
    account.setId("account-moderation-contract");
    account.setEmail("moderation-owner@example.test");
    account.setUsername("moderation-owner");
    var audit = ModerationAuditCommand.create(
        account.getId(), account.getUsername(), "ACCOUNT_STATUS_CHANGED", "ACCOUNT",
        account.getId(), "@owner", "approved reason", "%s changed account state.",
        Map.of("status", "ACTIVE"), Map.of("status", "SUSPENDED"),
        Map.of("source", "back-office", "accountId", account.getId()));
    account.setPendingModerationAudit(audit);

    assertThat(accounts.save(account).getPendingModerationAudit()).isEqualTo(audit);
    account.setPendingModerationAudit(null);
    accounts.save(account);
    assertThat(accounts.findById(account.getId()).orElseThrow().getPendingModerationAudit())
        .isNull();
  }

  @Test
  void rejectsTheSecondWriterWhenTwoReadersSaveTheSameVersion() {
    var fixture = createFixture();
    fixture.setId("account-two-writer-contract");
    fixture.setEmail("two-writer@example.test");
    fixture.setUsername("two-writer");
    accounts.save(fixture);

    var firstWriter = accounts.findById(fixture.getId()).orElseThrow();
    var secondWriter = accounts.findById(fixture.getId()).orElseThrow();
    firstWriter.setFirstName("first writer won");
    secondWriter.setFirstName("stale writer");

    accounts.save(firstWriter);

    assertThatThrownBy(() -> accounts.save(secondWriter))
        .isInstanceOf(OptimisticLockingFailureException.class);
    assertThat(accounts.findById(fixture.getId()).orElseThrow().getFirstName())
        .isEqualTo("first writer won");
  }

  @Override
  public Account createFixture() {
    return Account.builder()
        .id("account-contract")
        .createdOn(Instant.parse("2026-08-13T12:00:00Z"))
        .email("owner@example.test")
        .federationEnabled(true)
        .federationEnabledOn(Instant.parse("2026-08-13T12:05:00Z"))
        .firstName("Owner")
        .passwordHash("hash")
        .role(Role.USER)
        .permissions(new HashSet<>(java.util.Set.of(AccountPermission.MUSIC_READ)))
        .status(AccountStatus.ACTIVE)
        .username("owner")
        .build();
  }

  @Override
  public Account save(Account value) {
    return accounts.save(value);
  }

  @Override
  public java.util.Optional<Account> findById(String id) {
    return accounts.findById(id);
  }

  @Override
  public void deleteById(String id) {
    accounts.deleteById(id);
  }

  @Override
  public String identityOf(Account value) {
    return value.getId();
  }

  @Override
  public AccountRepository parityRepository() {
    return accounts;
  }
}
