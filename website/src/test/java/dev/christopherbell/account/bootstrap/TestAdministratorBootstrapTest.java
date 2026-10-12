package dev.christopherbell.account.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import dev.christopherbell.account.AccountRepository;
import dev.christopherbell.account.AccountService;
import dev.christopherbell.account.model.Account;
import dev.christopherbell.account.model.Role;
import dev.christopherbell.account.model.dto.AccountCreateRequest;
import dev.christopherbell.account.model.dto.AccountDetail;
import dev.christopherbell.configuration.persistence.IsolatedTestDatabase;
import dev.christopherbell.libs.api.exception.ResourceExistsException;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.env.MockEnvironment;

class TestAdministratorBootstrapTest {
  private static final String ACCOUNT_ID = "4f1c2a8e-0000-4000-8000-000000000001";

  private final MockEnvironment environment = new MockEnvironment();
  private final IsolatedTestDatabase isolatedTestDatabase = mock(IsolatedTestDatabase.class);
  private final AccountService accountService = mock(AccountService.class);
  private final AccountRepository accountRepository = mock(AccountRepository.class);
  private final TestAdministratorBootstrap bootstrap = new TestAdministratorBootstrap(
      environment, isolatedTestDatabase, accountService, accountRepository);

  private void configureAllSettings() {
    environment.setProperty(TestAdministratorBootstrap.USERNAME_PROPERTY, "candidate-admin");
    environment.setProperty(TestAdministratorBootstrap.EMAIL_PROPERTY, "candidate-admin@example.test");
    environment.setProperty(TestAdministratorBootstrap.PASSWORD_PROPERTY, "correct-horse-battery-staple");
  }

  @Test
  void doesNothingWithoutSettings() {
    bootstrap.createConfiguredAdministrator();

    verifyNoInteractions(isolatedTestDatabase, accountService, accountRepository);
  }

  @Test
  void refusesIncompleteSettingsBeforeTouchingTheDatabase() {
    environment.setProperty(TestAdministratorBootstrap.USERNAME_PROPERTY, "candidate-admin");
    environment.setProperty(TestAdministratorBootstrap.PASSWORD_PROPERTY, " ");

    assertThatThrownBy(bootstrap::createConfiguredAdministrator)
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("Test administrator settings are incomplete; missing "
            + "[app.test-administrator.email, app.test-administrator.password]");
    verifyNoInteractions(isolatedTestDatabase, accountService, accountRepository);
  }

  @Test
  void refusesAnyDatabaseThatIsNotTheIsolatedTestDatabaseBeforeWriting() {
    configureAllSettings();
    doThrow(new IllegalStateException("Test-only behavior requires exactly the test profile."))
        .when(isolatedTestDatabase).requireIsolatedTestConnection();

    assertThatThrownBy(bootstrap::createConfiguredAdministrator)
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("Test-only behavior requires exactly the test profile.");
    verifyNoInteractions(accountService, accountRepository);
  }

  @Test
  void createsTheAccountThroughTheServiceThenStoresItAsAdministrator() throws Exception {
    configureAllSettings();
    var storedAccount = Account.builder().id(ACCOUNT_ID).username("candidate-admin").role(Role.USER).build();
    when(accountService.createAccount(any())).thenReturn(AccountDetail.builder().id(ACCOUNT_ID).build());
    when(accountRepository.findById(ACCOUNT_ID)).thenReturn(Optional.of(storedAccount));

    bootstrap.createConfiguredAdministrator();

    var request = ArgumentCaptor.forClass(AccountCreateRequest.class);
    verify(accountService).createAccount(request.capture());
    assertThat(request.getValue().username()).isEqualTo("candidate-admin");
    assertThat(request.getValue().email()).isEqualTo("candidate-admin@example.test");
    assertThat(request.getValue().password()).isEqualTo("correct-horse-battery-staple");
    var saved = ArgumentCaptor.forClass(Account.class);
    verify(accountRepository).save(saved.capture());
    assertThat(saved.getValue().getId()).isEqualTo(ACCOUNT_ID);
    assertThat(saved.getValue().getRole()).isEqualTo(Role.ADMIN);
  }

  @Test
  void anExistingAccountStopsStartupWithoutPromotingIt() throws Exception {
    configureAllSettings();
    var duplicate = new ResourceExistsException("Account with given email or username already exists.");
    when(accountService.createAccount(any())).thenThrow(duplicate);

    assertThatThrownBy(bootstrap::createConfiguredAdministrator)
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("The test administrator could not be created.")
        .hasCause(duplicate);
    verify(accountRepository, never()).save(any());
  }
}
