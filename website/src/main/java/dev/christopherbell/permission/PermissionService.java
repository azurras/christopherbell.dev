package dev.christopherbell.permission;

import dev.christopherbell.account.model.Account;
import dev.christopherbell.account.model.AccountLoginRequest;
import dev.christopherbell.account.model.AccountStatus;
import dev.christopherbell.libs.api.exception.InvalidTokenException;
import dev.christopherbell.account.model.Role;
import dev.christopherbell.libs.security.PasswordUtil;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.security.Key;
import java.security.NoSuchAlgorithmException;
import java.security.spec.InvalidKeySpecException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Date;
import java.util.HashMap;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class PermissionService {

  private static final String LOCAL_DEV_SECRET =
      "local-development-jwt-secret-change-me-at-least-32-bytes";
  private static final long EXPIRATION_TIME = 3600_000; // 1 hour in milliseconds
  private static volatile Key key = buildKey(resolveSecret(null));

  /**
   * Applies the configured JWT secret after Spring property binding.
   *
   * @param jwtSecret configured app.jwt.secret value
   */
  @Value("${app.jwt.secret:}")
  void setJwtSecret(String jwtSecret) {
    configureSigningKey(jwtSecret);
  }

  public static String getSelf() {
    var authentication = SecurityContextHolder.getContext().getAuthentication();
    String token = (String) authentication.getCredentials();
    return  Jwts.parser()
        .setSigningKey(key)
        .build()
        .parseClaimsJws(token)
        .getBody()
        .getSubject();
  }

  /** Instance wrapper for resolving the current user id (for testability). */
  public String getSelfId() {
    return getSelf();
  }

  public static boolean isAuthenticated(
      AccountLoginRequest accountLoginRequest,
      Account account
  ) throws NoSuchAlgorithmException, InvalidKeySpecException {
    var password = accountLoginRequest.password();
    var salt = account.getPasswordSalt();
    var hash = account.getPasswordHash();
    return PasswordUtil.verifyPassword(password, salt, hash);
  }

  /**
   * Generates a JWT token with key that was created on application startup.
   *
   * @param account - the account that will be getting the new token.
   * @return a JWT token in String format.
   */
  public static String generateToken(Account account) {
    var claims = new HashMap<String, Object>();
    claims.put(Account.PROPERTY_ROLE, account.getRole());

    return Jwts.builder()
        .claims(claims)
        .id(UUID.randomUUID().toString())
        .subject(account.getId())
        .issuedAt(new Date())
        .expiration(new Date(System.currentTimeMillis() + EXPIRATION_TIME))
        .signWith(key)
        .compact();
  }

  /**
   * Validates a given JWT token with the key that was generated on application start up.
   *
   * @param token - the given JWT.
   * @return the claims for that JWT.
   */
  public static Claims validateToken(String token) {
    var jwt = stripBearer(token);

    return Jwts.parser()
        .setSigningKey(key)
        .build()
        .parseClaimsJws(jwt)
        .getBody();
  }

  /**
   * Configures the signing key from a stable secret.
   *
   * @param secret configured secret, or blank to resolve the environment/default fallback
   */
  static void configureSigningKey(String secret) {
    key = buildKey(resolveSecret(secret));
  }

  private static String resolveSecret(String configuredSecret) {
    if (hasText(configuredSecret)) {
      return configuredSecret.trim();
    }
    var appJwtSecret = System.getenv("APP_JWT_SECRET");
    if (hasText(appJwtSecret)) {
      return appJwtSecret.trim();
    }
    var jwtSecret = System.getenv("JWT_SECRET");
    if (hasText(jwtSecret)) {
      return jwtSecret.trim();
    }
    return LOCAL_DEV_SECRET;
  }

  private static Key buildKey(String secret) {
    byte[] bytes = decodeBase64Secret(secret);
    if (bytes == null) {
      bytes = secret.getBytes(StandardCharsets.UTF_8);
    }
    if (bytes.length < 32) {
      throw new IllegalStateException("JWT secret must be at least 32 bytes for HS256 signing.");
    }
    return Keys.hmacShaKeyFor(bytes);
  }

  private static byte[] decodeBase64Secret(String secret) {
    try {
      var decoded = Base64.getDecoder().decode(secret);
      return decoded.length >= 32 ? decoded : null;
    } catch (IllegalArgumentException e) {
      return null;
    }
  }

  private static boolean hasText(String value) {
    return value != null && !value.trim().isEmpty();
  }

  /**
   * Checks to see if a user has some required role in order to continue with their request.
   *
   * @param requiredRole - The role required for the request.
   * @return boolean on if the requester has the required role or not.
   */
  public boolean hasAuthority(String requiredRole) {
    try {
      var authentication = SecurityContextHolder.getContext().getAuthentication();

      if (authentication == null || !authentication.isAuthenticated()) {
        return false;
      }

      String token = (String) authentication.getCredentials();
      Claims claims = validateToken(token);
      String roleValue  = claims.get(Account.PROPERTY_ROLE, String.class);
      if (roleValue == null || requiredRole == null) {
        return false;
      }

      Role actual;
      Role required;
      try {
        actual = Role.valueOf(roleValue);
        required = Role.valueOf(requiredRole);
      } catch (IllegalArgumentException e) {
        // Unknown role value; deny access
        return false;
      }

      return level(actual) >= level(required);
    } catch (Exception e) {

      log.error("Error validating token or extracting claims: {}", e.getMessage(), e);
      return false; // Deny access on any error
    }
  }

  private static int level(Role role) {
    return switch (role) {
      case USER -> 1;
      case MOD -> 2;
      case ADMIN -> 3;
    };
  }

  public static boolean isAccountApproved(Account account) throws InvalidTokenException {
    if (account.getIsApproved()) {
      return true;
    } else {
      throw new InvalidTokenException("Account is not approved.");
    }
  }

  /**
   * Checks to see if an account is active.
   *
   * @param status - the status of the account.
   * @return true if the account is active.
   * @throws InvalidTokenException if the account is not active.
   */
  public static boolean isAccountActive(AccountStatus status) throws InvalidTokenException {
    return AccountStatus.ACTIVE == status;
  }

  /**
   * Removes the {@code "Bearer "} prefix from a JWT token string if present.
   * <p>
   * Many HTTP Authorization headers are formatted as
   * {@code "Authorization: Bearer <token>"}. This method ensures that only the
   * raw token value (the {@code <token>} part) is returned for downstream
   * parsing and validation.
   * </p>
   *
   * <p>If the input is {@code null}, this method returns {@code null}.
   * If the input does not start with the {@code "Bearer "} prefix,
   * the original string is returned unchanged.</p>
   *
   * @param token the full token string, possibly prefixed with {@code "Bearer "}
   * @return the token string without the {@code "Bearer "} prefix,
   *         or {@code null} if the input was {@code null}
   */
  private static String stripBearer(String token) {
    return token != null && token.startsWith("Bearer ") ? token.substring(7) : token;
  }
}
