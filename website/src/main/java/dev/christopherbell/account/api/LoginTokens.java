package dev.christopherbell.account.api;

import dev.christopherbell.account.auth.AccountSecurityFingerprint;
import dev.christopherbell.account.model.Account;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import javax.crypto.SecretKey;

/**
 * Issues and verifies the signed login JWTs that carry an account's id, role and security
 * fingerprint.
 *
 * <p>One instance owns the HMAC signing key for the application's lifetime. Build it with
 * {@link #fromConfiguration} so the secret precedence and production rules stay in one place.</p>
 */
public final class LoginTokens {

  /** How long a login token stays valid after it is issued. */
  public static final Duration TOKEN_LIFETIME = Duration.ofDays(7);
  static final String LOCAL_DEVELOPMENT_SECRET =
      "local-development-jwt-secret-change-me-at-least-32-bytes";
  private static final int MINIMUM_SECRET_BYTES = 32;
  private static final String BEARER_PREFIX = "Bearer ";

  private final SecretKey signingKey;
  private final Clock clock;

  private LoginTokens(SecretKey signingKey, Clock clock) {
    this.signingKey = signingKey;
    this.clock = clock;
  }

  /**
   * Builds login tokens from the configured secret.
   *
   * <p>The secret is, in order: {@code configuredSecret}, then the {@code APP_JWT_SECRET} and
   * {@code JWT_SECRET} environment variables, then a fixed local development secret that
   * production refuses. A secret that decodes from Base64 to at least 32 bytes is used decoded;
   * otherwise its UTF-8 bytes are used and must number at least 32.</p>
   *
   * @param configuredSecret the {@code app.jwt.secret} value, or blank
   * @param isProductionProfile whether the {@code prod} profile is active
   * @param environmentVariables the process environment
   * @param clock the time source for issuing and expiry checks
   * @return login tokens signed with the resolved secret
   * @throws IllegalStateException if production has no configured secret or the secret is too
   *     short
   */
  public static LoginTokens fromConfiguration(
      String configuredSecret,
      boolean isProductionProfile,
      Map<String, String> environmentVariables,
      Clock clock) {
    String resolvedSecret =
        resolveSecret(configuredSecret, isProductionProfile, environmentVariables);
    return new LoginTokens(signingKeyFor(resolvedSecret), Objects.requireNonNull(clock, "clock"));
  }

  /**
   * Issues a login token for an account.
   *
   * @param account the account being signed in; its id becomes the token subject
   * @return the compact signed JWT
   */
  public String issueFor(Account account) {
    Instant issuedAt = clock.instant();
    Map<String, Object> accountClaims = new HashMap<>();
    accountClaims.put(Account.PROPERTY_ROLE, account.getRole());
    accountClaims.put(AccountSecurityFingerprint.CLAIM, AccountSecurityFingerprint.from(account));
    return Jwts.builder()
        .claims(accountClaims)
        .id(UUID.randomUUID().toString())
        .subject(account.getId())
        .issuedAt(Date.from(issuedAt))
        .expiration(Date.from(issuedAt.plus(TOKEN_LIFETIME)))
        .signWith(signingKey)
        .compact();
  }

  /**
   * Verifies a presented login token and returns its claims.
   *
   * @param presentedToken a compact JWT, optionally prefixed with {@code "Bearer "}
   * @return the verified claims
   * @throws io.jsonwebtoken.JwtException if the token is malformed, unsigned, wrongly signed or
   *     expired
   * @throws IllegalArgumentException if the token is null or blank
   */
  public Claims verifiedClaimsOf(String presentedToken) {
    String compactToken = withoutBearerPrefix(presentedToken);
    return Jwts.parser()
        .verifyWith(signingKey)
        .clock(() -> Date.from(clock.instant()))
        .build()
        .parseSignedClaims(compactToken)
        .getPayload();
  }

  static String resolveSecret(
      String configuredSecret,
      boolean isProductionProfile,
      Map<String, String> environmentVariables) {
    return firstNonBlank(
        configuredSecret,
        environmentVariables.get("APP_JWT_SECRET"),
        environmentVariables.get("JWT_SECRET"))
        .orElseGet(() -> developmentSecret(isProductionProfile));
  }

  private static String developmentSecret(boolean isProductionProfile) {
    if (isProductionProfile) {
      throw new IllegalStateException(
          "Production JWT secret must be configured with app.jwt.secret or APP_JWT_SECRET.");
    }
    return LOCAL_DEVELOPMENT_SECRET;
  }

  private static Optional<String> firstNonBlank(String... candidateSecrets) {
    for (String candidateSecret : candidateSecrets) {
      if (candidateSecret != null && !candidateSecret.isBlank()) {
        return Optional.of(candidateSecret.trim());
      }
    }
    return Optional.empty();
  }

  private static SecretKey signingKeyFor(String secret) {
    byte[] secretBytes = decodedBase64Secret(secret)
        .orElseGet(() -> secret.getBytes(StandardCharsets.UTF_8));
    if (secretBytes.length < MINIMUM_SECRET_BYTES) {
      throw new IllegalStateException("JWT secret must be at least 32 bytes for HS256 signing.");
    }
    return Keys.hmacShaKeyFor(secretBytes);
  }

  private static Optional<byte[]> decodedBase64Secret(String secret) {
    try {
      byte[] decodedBytes = Base64.getDecoder().decode(secret);
      return decodedBytes.length >= MINIMUM_SECRET_BYTES
          ? Optional.of(decodedBytes)
          : Optional.empty();
    } catch (IllegalArgumentException notBase64) {
      return Optional.empty();
    }
  }

  private static String withoutBearerPrefix(String presentedToken) {
    return presentedToken != null && presentedToken.startsWith(BEARER_PREFIX)
        ? presentedToken.substring(BEARER_PREFIX.length())
        : presentedToken;
  }
}
