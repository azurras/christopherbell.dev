package dev.christopherbell.account.auth;

import dev.christopherbell.account.AccountRepository;
import dev.christopherbell.account.model.AccountStatus;
import dev.christopherbell.account.model.AccountLoginRequest;
import dev.christopherbell.libs.api.exception.InvalidTokenException;
import dev.christopherbell.libs.security.EmailSanitizer;
import dev.christopherbell.libs.security.PasswordUtil;
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
  private static final String PUBLIC_REJECTION = "Login failed.";
  private static final String DUMMY_CURRENT_HASH = createDummyCurrentHash();
  private final AccountRepository accountRepository;
  private final AccountLoginStore accountLoginStore;
  private final AccountSessionRevoker sessionRevoker;

  /**
   * Validates login information and returns a signed JWT for active accounts.
   */
  public String loginAccount(AccountLoginRequest accountLoginRequest)
      throws InvalidTokenException {
    try {
      var sanitizedEmail = EmailSanitizer.sanitize(accountLoginRequest.email());
      var account = accountRepository
          .findByEmailIgnoreCase(sanitizedEmail)
          .orElse(null);
      var password = accountLoginRequest.password();
      var verified = account == null
          ? PasswordUtil.verifyPassword(password, null, DUMMY_CURRENT_HASH)
          : PasswordUtil.verifyPassword(
              password, account.getPasswordSalt(), account.getPasswordHash());
      if (account == null || !verified || account.getStatus() != AccountStatus.ACTIVE) {
        log.info("Rejected account login category={}", rejectionCategory(account, verified));
        throw rejectedLogin();
      }

      boolean rehashRequired = PasswordUtil.needsRehash(
          account.getPasswordSalt(), account.getPasswordHash());
      var currentHash = rehashRequired
          ? PasswordUtil.upgradePassword(
              password, account.getPasswordSalt(), account.getPasswordHash())
          : account.getPasswordHash();
      var current = accountLoginStore.completeLogin(account, currentHash, Instant.now())
          .filter(updated -> updated.getStatus() == AccountStatus.ACTIVE)
          .orElseThrow(this::rejectedLogin);
      if (rehashRequired) {
        sessionRevoker.revokeAll(current.getId());
      }
      log.info("Successful login for account with id: {}", current.getId());
      return PermissionService.generateToken(current);
    } catch (NoSuchAlgorithmException | InvalidKeySpecException | IllegalArgumentException failure) {
      log.warn("Rejected account login because credential verification failed safely.");
      throw rejectedLogin(failure);
    }
  }

  private String rejectionCategory(dev.christopherbell.account.model.Account account, boolean verified) {
    if (account == null) return "unknown-account";
    if (!verified) return "invalid-password";
    return "inactive-account";
  }

  private InvalidTokenException rejectedLogin() {
    return new InvalidTokenException(PUBLIC_REJECTION);
  }

  private InvalidTokenException rejectedLogin(Exception cause) {
    return new InvalidTokenException(PUBLIC_REJECTION, cause);
  }

  private static String createDummyCurrentHash() {
    try {
      return PasswordUtil.hashPassword("not-a-real-account-password");
    } catch (NoSuchAlgorithmException | InvalidKeySpecException impossible) {
      throw new IllegalStateException("Password hashing is unavailable.", impossible);
    }
  }
}
