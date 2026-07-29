package dev.christopherbell.federation.consent;

import dev.christopherbell.account.AccountMapper;
import dev.christopherbell.account.AccountRepository;
import dev.christopherbell.account.model.Account;
import dev.christopherbell.account.model.AccountStatus;
import dev.christopherbell.account.model.dto.AccountDetail;
import dev.christopherbell.account.model.dto.FederationConsentStatus;
import dev.christopherbell.federation.configuration.FederationProperties;
import dev.christopherbell.federation.identity.FederationIdentityFactory;
import dev.christopherbell.libs.api.exception.InvalidRequestException;
import dev.christopherbell.libs.api.exception.ResourceNotFoundException;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import org.springframework.stereotype.Service;

/** Owns the only boundaries that may enable or disable a local federation identity. */
@Service
public class FederationConsentService {
  private final AccountRepository accounts;
  private final AccountMapper accountMapper;
  private final FederationProperties properties;
  private final Optional<FederationIdentityFactory> identityFactory;
  private final Clock clock;

  public FederationConsentService(
      AccountRepository accounts,
      AccountMapper accountMapper,
      FederationProperties properties,
      Optional<FederationIdentityFactory> identityFactory,
      Clock clock
  ) {
    this.accounts = Objects.requireNonNull(accounts, "accounts");
    this.accountMapper = Objects.requireNonNull(accountMapper, "accountMapper");
    this.properties = Objects.requireNonNull(properties, "properties");
    this.identityFactory = Objects.requireNonNull(identityFactory, "identityFactory");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  /** Prepares a new account before its sole creation write. */
  public void prepareNewAccount(Account account, boolean requested) throws InvalidRequestException {
    Objects.requireNonNull(account, "account");
    if (!requested) {
      account.setFederationEnabled(false);
      account.setFederationEnabledOn(null);
      return;
    }
    enable(account);
  }

  /** Applies an authenticated account's explicit federation choice. */
  public AccountDetail setEnabled(String accountId, boolean enabled)
      throws InvalidRequestException, ResourceNotFoundException {
    if (accountId == null || accountId.isBlank()) {
      throw new InvalidRequestException("Account ID is required.");
    }
    Account account = accounts.findById(accountId)
        .orElseThrow(() -> new ResourceNotFoundException("Account not found."));
    if (enabled) {
      if (account.isFederationEnabled() && account.getFederationIdentity() != null) {
        return accountMapper.toAccount(account);
      }
      enable(account);
    } else {
      if (!account.isFederationEnabled()) {
        return accountMapper.toAccount(account);
      }
      account.setFederationEnabled(false);
      account.setFederationEnabledOn(null);
    }
    return accountMapper.toAccount(accounts.save(account));
  }

  public boolean enrollmentAvailable() {
    return properties.discoveryEnabled() && identityFactory.isPresent();
  }

  public FederationConsentStatus status(String accountId) throws ResourceNotFoundException {
    if (accountId == null || accountId.isBlank()) {
      throw new ResourceNotFoundException("Account not found.");
    }
    Account account = accounts.findById(accountId)
        .orElseThrow(() -> new ResourceNotFoundException("Account not found."));
    return new FederationConsentStatus(account.isFederationEnabled(), enrollmentAvailable());
  }

  private void enable(Account account) throws InvalidRequestException {
    if (account.getStatus() != AccountStatus.ACTIVE) {
      throw new InvalidRequestException("Only active accounts may enable federation.");
    }
    FederationIdentityFactory factory = identityFactory.orElseThrow(
        () -> new InvalidRequestException("Federation enrollment is unavailable."));
    if (!properties.discoveryEnabled()) {
      throw new InvalidRequestException("Federation enrollment is unavailable.");
    }
    if (account.getFederationIdentity() == null) {
      account.setFederationIdentity(factory.create(account.getId(), account.getUsername()));
    }
    account.setFederationEnabled(true);
    account.setFederationEnabledOn(Instant.now(clock));
  }
}
