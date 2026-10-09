package dev.christopherbell.account.passwordreset;

import dev.christopherbell.account.AccountRepository;
import dev.christopherbell.account.auth.AccountSessionRevoker;
import dev.christopherbell.account.model.Account;
import dev.christopherbell.account.model.AccountPasswordResetConfirmRequest;
import dev.christopherbell.account.model.AccountPasswordResetRequest;
import dev.christopherbell.libs.api.exception.InvalidRequestException;
import dev.christopherbell.libs.api.exception.InvalidTokenException;
import dev.christopherbell.libs.security.EmailSanitizer;
import dev.christopherbell.libs.security.PasswordUtil;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.spec.InvalidKeySpecException;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.IncorrectResultSizeDataAccessException;
import org.springframework.stereotype.Service;

/**
 * Owns password reset token lifecycle and delegates delivery to the mail notifier.
 */
@RequiredArgsConstructor
@Slf4j
@Service
public class PasswordResetService {
  private static final Duration PASSWORD_RESET_TTL = Duration.ofHours(1);
  private static final int PASSWORD_RESET_TOKEN_BYTES = 32;

  private final AccountRepository accountRepository;
  private final PasswordResetNotificationService passwordResetNotificationService;
  private final AccountSessionRevoker sessionRevoker;
  private final Clock clock;
  /**
   * Requests a password reset without revealing whether the email exists.
   */
  public void requestPasswordReset(AccountPasswordResetRequest request, String baseUrl) {
    if (request == null || request.email() == null || request.email().isBlank()) {
      return;
    }

    final Optional<Account> account;
    try {
      var sanitizedEmail = EmailSanitizer.sanitize(request.email());
      account = accountRepository.findByEmailIgnoreCase(sanitizedEmail);
    } catch (IllegalArgumentException | IncorrectResultSizeDataAccessException failure) {
      return;
    }
    account.ifPresent(found -> sendResetLink(found, baseUrl));
  }

  private void sendResetLink(Account account, String baseUrl) {
    log.info("Password reset requested for account id: {}", account.getId());
    var token = generatePasswordResetToken();
    account.setPasswordResetTokenHash(hashPasswordResetToken(token));
    account.setPasswordResetTokenExpiresOn(clock.instant().plus(PASSWORD_RESET_TTL));
    accountRepository.save(account);
    var resetUrl = buildPasswordResetUrl(baseUrl, token);
    passwordResetNotificationService.sendPasswordReset(account, resetUrl);
  }

  /**
   * Completes a reset by validating the token and replacing the stored password hash.
   */
  public void resetPassword(AccountPasswordResetConfirmRequest request)
      throws InvalidRequestException, InvalidTokenException {
    if (request == null || request.token() == null || request.token().isBlank()) {
      throw new InvalidTokenException("Password reset token is invalid or expired.");
    }
    if (request.password() == null || request.password().isBlank()) {
      throw new InvalidRequestException("Password cannot be null or blank.");
    }

    var tokenHash = hashPasswordResetToken(request.token());
    var account = accountRepository
        .findByPasswordResetTokenHash(tokenHash)
        .orElseThrow(() -> new InvalidTokenException("Password reset token is invalid or expired."));

    if (account.getPasswordResetTokenExpiresOn() == null
        || account.getPasswordResetTokenExpiresOn().isBefore(clock.instant())) {
      clearPasswordResetToken(account);
      accountRepository.save(account);
      throw new InvalidTokenException("Password reset token is invalid or expired.");
    }

    try {
      account.setPasswordSalt(null);
      account.setPasswordHash(PasswordUtil.hashPassword(request.password()));
      clearPasswordResetToken(account);
      account.setLastUpdatedOn(clock.instant());
      accountRepository.save(account);
      sessionRevoker.revokeAll(account.getId());
      log.info("Password reset completed for account id: {}", account.getId());
    } catch (NoSuchAlgorithmException | InvalidKeySpecException hashingUnavailable) {
      throw new InvalidTokenException(
          "Error resetting password: " + hashingUnavailable.getMessage(), hashingUnavailable);
    }
  }

  private String generatePasswordResetToken() {
    var bytes = new byte[PASSWORD_RESET_TOKEN_BYTES];
    new SecureRandom().nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  private String hashPasswordResetToken(String token) {
    try {
      var digest = MessageDigest.getInstance("SHA-256");
      var hash = digest.digest(token.getBytes(StandardCharsets.UTF_8));
      return Base64.getEncoder().encodeToString(hash);
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 is not available.", impossible);
    }
  }

  private String buildPasswordResetUrl(String baseUrl, String token) {
    var safeBaseUrl = (baseUrl == null || baseUrl.isBlank()) ? "" : baseUrl.strip();
    var encodedToken = URLEncoder.encode(token, StandardCharsets.UTF_8);
    return safeBaseUrl + "/reset-password?token=" + encodedToken;
  }

  private void clearPasswordResetToken(Account account) {
    account.setPasswordResetTokenHash(null);
    account.setPasswordResetTokenExpiresOn(null);
  }
}
