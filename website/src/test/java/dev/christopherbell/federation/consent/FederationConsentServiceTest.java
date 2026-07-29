package dev.christopherbell.federation.consent;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.christopherbell.account.AccountMapper;
import dev.christopherbell.account.AccountRepository;
import dev.christopherbell.account.model.Account;
import dev.christopherbell.account.model.AccountStatus;
import dev.christopherbell.account.model.dto.AccountDetail;
import dev.christopherbell.federation.configuration.FederationProperties;
import dev.christopherbell.federation.identity.EncryptedPrivateKey;
import dev.christopherbell.federation.identity.FederationIdentity;
import dev.christopherbell.federation.identity.FederationIdentityFactory;
import dev.christopherbell.libs.api.exception.InvalidRequestException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class FederationConsentServiceTest {
  private static final Instant NOW = Instant.parse("2026-07-28T18:00:00Z");

  @Mock private AccountRepository accounts;
  @Mock private AccountMapper accountMapper;
  @Mock private FederationIdentityFactory identityFactory;

  private FederationConsentService service;

  @BeforeEach
  void setUp() {
    byte[] secret = new byte[32];
    var properties = new FederationProperties(
        true,
        false,
        false,
        "christopherbell.dev",
        "1.0",
        Base64.getEncoder().encodeToString(secret));
    service = new FederationConsentService(
        accounts,
        accountMapper,
        properties,
        Optional.of(identityFactory),
        Clock.fixed(NOW, ZoneOffset.UTC));
  }

  @Test
  void omittedSignupConsentStaysDisabledWithoutGeneratingAnIdentity() throws Exception {
    var account = account();

    service.prepareNewAccount(account, false);

    assertFalse(account.isFederationEnabled());
    assertNull(account.getFederationIdentity());
    verify(identityFactory, never()).create(account.getId(), account.getUsername());
  }

  @Test
  void signupConsentGeneratesIdentityBeforeTheCallerSavesTheAccount() throws Exception {
    var account = account();
    var identity = identity();
    when(identityFactory.create(account.getId(), account.getUsername())).thenReturn(identity);

    service.prepareNewAccount(account, true);

    assertTrue(account.isFederationEnabled());
    assertSame(identity, account.getFederationIdentity());
    assertSame(NOW, account.getFederationEnabledOn());
    verify(accounts, never()).save(account);
  }

  @Test
  void enabledSignupFailsClosedWhenDiscoveryEnrollmentIsUnavailable() {
    var disabled = new FederationProperties(
        false, false, false, "christopherbell.dev", "1.0", null);
    service = new FederationConsentService(
        accounts,
        accountMapper,
        disabled,
        Optional.empty(),
        Clock.fixed(NOW, ZoneOffset.UTC));

    assertThrows(InvalidRequestException.class,
        () -> service.prepareNewAccount(account(), true));
    verify(accounts, never()).save(org.mockito.ArgumentMatchers.any());
  }

  @Test
  void firstEnablePersistsIdentityAndConsentInOneWrite() throws Exception {
    var account = account();
    var identity = identity();
    var detail = AccountDetail.builder().id(account.getId()).federationEnabled(true).build();
    when(accounts.findById(account.getId())).thenReturn(Optional.of(account));
    when(identityFactory.create(account.getId(), account.getUsername())).thenReturn(identity);
    when(accounts.save(account)).thenReturn(account);
    when(accountMapper.toAccount(account)).thenReturn(detail);

    AccountDetail result = service.setEnabled(account.getId(), true);

    assertSame(detail, result);
    assertTrue(account.isFederationEnabled());
    assertSame(identity, account.getFederationIdentity());
    verify(accounts).save(account);
  }

  @Test
  void disablingRetainsIdentityForStableReEnable() throws Exception {
    var identity = identity();
    var account = account();
    account.setFederationEnabled(true);
    account.setFederationIdentity(identity);
    when(accounts.findById(account.getId())).thenReturn(Optional.of(account));
    when(accounts.save(account)).thenReturn(account);

    service.setEnabled(account.getId(), false);

    assertFalse(account.isFederationEnabled());
    assertSame(identity, account.getFederationIdentity());
    verify(identityFactory, never()).create(account.getId(), account.getUsername());
  }

  @Test
  void reEnableUsesTheExistingStableIdentity() throws Exception {
    var identity = identity();
    var account = account();
    account.setFederationIdentity(identity);
    when(accounts.findById(account.getId())).thenReturn(Optional.of(account));
    when(accounts.save(account)).thenReturn(account);

    service.setEnabled(account.getId(), true);

    assertTrue(account.isFederationEnabled());
    assertSame(identity, account.getFederationIdentity());
    verify(identityFactory, never()).create(account.getId(), account.getUsername());
  }

  private static Account account() {
    return Account.builder()
        .id("account-123")
        .username("Christopher.Bell")
        .status(AccountStatus.ACTIVE)
        .build();
  }

  private static FederationIdentity identity() {
    return new FederationIdentity(
        "https://www.christopherbell.dev/ap/users/Christopher.Bell",
        "https://www.christopherbell.dev/ap/users/Christopher.Bell#main-key",
        "-----BEGIN PUBLIC KEY-----\npublic\n-----END PUBLIC KEY-----",
        new EncryptedPrivateKey(new byte[12], new byte[16]),
        1,
        NOW);
  }
}
