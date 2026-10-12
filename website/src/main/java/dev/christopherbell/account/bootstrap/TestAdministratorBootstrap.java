package dev.christopherbell.account.bootstrap;

import dev.christopherbell.account.AccountRepository;
import dev.christopherbell.account.AccountService;
import dev.christopherbell.account.model.Role;
import dev.christopherbell.account.model.dto.AccountCreateRequest;
import dev.christopherbell.configuration.persistence.IsolatedTestDatabase;
import dev.christopherbell.libs.api.exception.InvalidRequestException;
import dev.christopherbell.libs.api.exception.ResourceExistsException;
import java.util.List;
import java.util.Objects;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Creates one ADMIN account for local candidate verification on the isolated test database.
 *
 * <p>Account creation always assigns {@link Role#USER} and no route promotes an account, so
 * runtime checks of admin routes and shared-folder grants need this fixture. It exists only
 * under profile {@code test}, does nothing unless all three {@code app.test-administrator}
 * settings are present, and writes only after {@link IsolatedTestDatabase} proves the profile
 * is exactly {@code test} on the loopback {@code test} database. Any failure stops startup.
 */
@Slf4j
@Component
@Profile("test")
public class TestAdministratorBootstrap {
  static final String USERNAME_PROPERTY = "app.test-administrator.username";
  static final String EMAIL_PROPERTY = "app.test-administrator.email";
  static final String PASSWORD_PROPERTY = "app.test-administrator.password";
  private static final List<String> SETTINGS =
      List.of(USERNAME_PROPERTY, EMAIL_PROPERTY, PASSWORD_PROPERTY);

  private final Environment environment;
  private final IsolatedTestDatabase isolatedTestDatabase;
  private final AccountService accountService;
  private final AccountRepository accountRepository;

  public TestAdministratorBootstrap(
      Environment environment,
      IsolatedTestDatabase isolatedTestDatabase,
      AccountService accountService,
      AccountRepository accountRepository) {
    this.environment = Objects.requireNonNull(environment, "environment");
    this.isolatedTestDatabase = Objects.requireNonNull(isolatedTestDatabase, "isolatedTestDatabase");
    this.accountService = Objects.requireNonNull(accountService, "accountService");
    this.accountRepository = Objects.requireNonNull(accountRepository, "accountRepository");
  }

  /**
   * Creates the configured administrator once the application is ready.
   *
   * @throws IllegalStateException when only some settings are present, the database is not the
   *     isolated test database, or the account cannot be created
   */
  @EventListener(ApplicationReadyEvent.class)
  public void createConfiguredAdministrator() {
    var presentSettings = SETTINGS.stream()
        .filter(setting -> hasText(environment.getProperty(setting)))
        .toList();
    if (presentSettings.isEmpty()) {
      return;
    }
    if (presentSettings.size() != SETTINGS.size()) {
      var missingSettings = SETTINGS.stream()
          .filter(setting -> !presentSettings.contains(setting))
          .toList();
      throw new IllegalStateException("Test administrator settings are incomplete; missing " + missingSettings);
    }
    isolatedTestDatabase.requireIsolatedTestConnection();

    var username = environment.getProperty(USERNAME_PROPERTY);
    var request = AccountCreateRequest.builder()
        .firstName("Test")
        .lastName("Administrator")
        .email(environment.getProperty(EMAIL_PROPERTY))
        .password(environment.getProperty(PASSWORD_PROPERTY))
        .username(username)
        .build();
    try {
      var createdAccountId = accountService.createAccount(request).getId();
      var storedAccount = accountRepository.findById(createdAccountId)
          .orElseThrow(() -> new IllegalStateException("The created test administrator was not stored."));
      storedAccount.setRole(Role.ADMIN);
      accountRepository.save(storedAccount);
    } catch (ResourceExistsException | InvalidRequestException creationRefused) {
      throw new IllegalStateException("The test administrator could not be created.", creationRefused);
    }
    log.warn("Created test administrator {} on the isolated test database.", username);
  }

  private static boolean hasText(String value) {
    return value != null && !value.isBlank();
  }
}
