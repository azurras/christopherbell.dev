package dev.christopherbell.account.auth;

import dev.christopherbell.account.AccountNotActiveException;
import dev.christopherbell.account.AccountRepository;
import dev.christopherbell.account.model.AccountLoginRequest;
import dev.christopherbell.libs.api.exception.InvalidTokenException;
import dev.christopherbell.libs.api.exception.ResourceNotFoundException;
import dev.christopherbell.libs.security.EmailSanitizer;
import dev.christopherbell.permission.PermissionService;
import java.security.NoSuchAlgorithmException;
import java.security.spec.InvalidKeySpecException;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Handles account authentication so login rules can evolve without expanding account CRUD.
 */
@RequiredArgsConstructor
@Slf4j
@Service
public class AccountAuthenticationService {
  private final AccountRepository accountRepository;

  /**
   * Validates login information and returns a signed JWT for active accounts.
   */
  public String loginAccount(AccountLoginRequest accountLoginRequest)
      throws InvalidTokenException, ResourceNotFoundException {
    try {
      var sanitizedEmail = EmailSanitizer.sanitize(accountLoginRequest.email());
      var account = accountRepository
          .findByEmailIgnoreCase(sanitizedEmail)
          .orElseThrow(() -> new ResourceNotFoundException(
              String.format("Account with email %s not found.", sanitizedEmail)));

      if (!PermissionService.isAuthenticated(accountLoginRequest, account)) {
        throw new InvalidTokenException("Given Login information was not correct.");
      }
      if (!PermissionService.isAccountActive(account.getStatus())) {
        throw new AccountNotActiveException("Account is not active.");
      }

      account.setLastLoginOn(Instant.now());
      accountRepository.save(account);
      log.info("Successful login for account with id: {}", account.getId());
      return PermissionService.generateToken(account);
    } catch (NoSuchAlgorithmException | InvalidKeySpecException e) {
      throw new InvalidTokenException("Error validating password: " + e.getMessage(), e);
    }
  }
}
