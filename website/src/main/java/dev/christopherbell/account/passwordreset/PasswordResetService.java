package dev.christopherbell.account.passwordreset;

import dev.christopherbell.account.AccountRepository;
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
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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

  /**
   * Requests a password reset without revealing whether the email exists.
   */
  public void requestPasswordReset(AccountPasswordResetRequest request, String baseUrl) {
    if (request == null || request.email() == null || request.email().isBlank()) {
      return;
    }

    Account account;
    try {
      var sanitizedEmail = EmailSanitizer.sanitize(request.email());
      account = accountRepository.findByEmailIgnoreCase(sanitizedEmail).orElse(null);
    } catch (IllegalArgumentException e) {
      return;
    }
    if (account == null) {
      return;
    }

    log.info("Password reset requested for account id: {}", account.getId());
    var token = generatePasswordResetToken();
    account.setPasswordResetTokenHash(hashPasswordResetToken(token));
    account.setPasswordResetTokenExpiresOn(Instant.now().plus(PASSWORD_RESET_TTL));
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
        || account.getPasswordResetTokenExpiresOn().isBefore(Instant.now())) {
      clearPasswordResetToken(account);
      accountRepository.save(account);
      throw new InvalidTokenException("Password reset token is invalid or expired.");
    }

    try {
      var salt = PasswordUtil.generateSalt();
      var hash = PasswordUtil.hashPassword(request.password(), salt);
      account.setPasswordSalt(salt);
      account.setPasswordHash(hash);
      clearPasswordResetToken(account);
      account.setLastUpdatedOn(Instant.now());
      accountRepository.save(account);
      log.info("Password reset completed for account id: {}", account.getId());
    } catch (NoSuchAlgorithmException | InvalidKeySpecException e) {
      throw new InvalidTokenException("Error resetting password: " + e.getMessage(), e);
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
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is not available.", e);
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
