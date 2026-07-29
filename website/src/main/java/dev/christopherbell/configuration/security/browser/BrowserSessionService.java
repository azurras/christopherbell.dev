package dev.christopherbell.configuration.security.browser;

import dev.christopherbell.account.AccountRepository;
import dev.christopherbell.account.auth.AccountSecurityFingerprint;
import dev.christopherbell.account.model.Account;
import dev.christopherbell.account.model.AccountStatus;
import dev.christopherbell.permission.PermissionService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

/** Creates, resolves, rotates, and revokes opaque browser sessions. */
public class BrowserSessionService {
  static final Duration IDLE_LIFETIME = Duration.ofDays(7);
  static final Duration ABSOLUTE_LIFETIME = Duration.ofDays(30);
  static final Duration ROTATION_INTERVAL = Duration.ofDays(1);
  static final Duration ROTATION_OVERLAP = Duration.ofMinutes(2);
  private static final int TOKEN_BYTES = 32;

  private final BrowserSessionRepository sessions;
  private final AccountRepository accounts;
  private final Clock clock;
  private final SecureRandom random = new SecureRandom();

  public BrowserSessionService(
      BrowserSessionRepository sessions,
      AccountRepository accounts,
      Clock clock) {
    this.sessions = sessions;
    this.accounts = accounts;
    this.clock = clock;
  }

  /** Exchanges a short-lived login JWT for a persisted opaque browser session. */
  public String create(String loginJwt) {
    var claims = PermissionService.validateToken(loginJwt);
    var accountId = claims.getSubject();
    var presentedFingerprint = claims.get(AccountSecurityFingerprint.CLAIM, String.class);
    var account = accounts.findById(accountId)
        .filter(this::isActive)
        .filter(current -> AccountSecurityFingerprint.matches(presentedFingerprint, current))
        .orElseThrow(() -> new IllegalArgumentException("Browser session account is unavailable."));
    var now = clock.instant();
    var credential = credential(UUID.randomUUID().toString());
    sessions.save(BrowserSession.builder()
        .id(credential.sessionId())
        .accountId(account.getId())
        .role(account.getRole())
        .tokenHash(hash(credential.secret()))
        .accountSecurityFingerprint(AccountSecurityFingerprint.from(account))
        .createdOn(now)
        .lastSeenOn(now)
        .rotatedOn(now)
        .idleExpiresOn(now.plus(IDLE_LIFETIME))
        .absoluteExpiresOn(now.plus(ABSOLUTE_LIFETIME))
        .build());
    return credential.raw();
  }

  /** Resolves a cookie credential, renewing only user-driven requests. */
  public Optional<AuthenticatedBrowserSession> authenticate(String rawToken, boolean interactive) {
    var parsed = parse(rawToken);
    if (parsed.isEmpty()) return Optional.empty();

    var session = sessions.findById(parsed.get().sessionId()).orElse(null);
    if (session == null) return Optional.empty();
    var now = clock.instant();
    if (!validCredential(session, parsed.get().secret(), now)
        || expired(session, now)) {
      sessions.delete(session);
      return Optional.empty();
    }

    if (session.getAccountId() == null || session.getAccountId().isBlank()
        || session.getRole() == null
        || session.getAccountSecurityFingerprint() == null
        || session.getAccountSecurityFingerprint().isBlank()) {
      sessions.delete(session);
      return Optional.empty();
    }

    Optional<String> rotatedToken = Optional.empty();
    if (interactive) {
      session.setLastSeenOn(now);
      session.setIdleExpiresOn(earlier(now.plus(IDLE_LIFETIME), session.getAbsoluteExpiresOn()));
      if (!now.isBefore(session.getRotatedOn().plus(ROTATION_INTERVAL))) {
        var rotated = credential(session.getId());
        session.setPreviousTokenHash(session.getTokenHash());
        session.setPreviousTokenExpiresOn(earlier(
            now.plus(ROTATION_OVERLAP), session.getAbsoluteExpiresOn()));
        session.setTokenHash(hash(rotated.secret()));
        session.setRotatedOn(now);
        rotatedToken = Optional.of(rotated.raw());
      }
      sessions.save(session);
    }
    return Optional.of(new AuthenticatedBrowserSession(
        session.getAccountId(), session.getRole(), rotatedToken));
  }

  /** Revokes the session named by a cookie without revealing whether it existed. */
  public void revoke(String rawToken) {
    parse(rawToken).ifPresent(token -> sessions.deleteById(token.sessionId()));
  }

  /** Revokes every browser session for an account. */
  public void revokeAll(String accountId) {
    if (accountId != null && !accountId.isBlank()) sessions.deleteByAccountId(accountId);
  }

  private boolean isActive(Account account) {
    return AccountStatus.ACTIVE.equals(account.getStatus());
  }

  private boolean expired(BrowserSession session, Instant now) {
    return !now.isBefore(session.getIdleExpiresOn())
        || !now.isBefore(session.getAbsoluteExpiresOn());
  }

  private boolean validCredential(BrowserSession session, String secret, Instant now) {
    String candidateHash = hash(secret);
    if (constantTimeEquals(session.getTokenHash(), candidateHash)) return true;
    return session.getPreviousTokenHash() != null
        && session.getPreviousTokenExpiresOn() != null
        && now.isBefore(session.getPreviousTokenExpiresOn())
        && constantTimeEquals(session.getPreviousTokenHash(), candidateHash);
  }

  private Credential credential(String sessionId) {
    var bytes = new byte[TOKEN_BYTES];
    random.nextBytes(bytes);
    var secret = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    return new Credential(sessionId, secret);
  }

  private Optional<Credential> parse(String rawToken) {
    if (rawToken == null || rawToken.length() > 256) return Optional.empty();
    int separator = rawToken.indexOf('.');
    if (separator <= 0 || separator != rawToken.lastIndexOf('.')) return Optional.empty();
    try {
      UUID.fromString(rawToken.substring(0, separator));
    } catch (IllegalArgumentException invalidId) {
      return Optional.empty();
    }
    var secret = rawToken.substring(separator + 1);
    if (secret.length() < 32 || secret.length() > 128) return Optional.empty();
    return Optional.of(new Credential(rawToken.substring(0, separator), secret));
  }

  private static Instant earlier(Instant first, Instant second) {
    return first.isBefore(second) ? first : second;
  }

  private static boolean constantTimeEquals(String expected, String actual) {
    if (expected == null || actual == null) return false;
    return MessageDigest.isEqual(
        expected.getBytes(StandardCharsets.US_ASCII),
        actual.getBytes(StandardCharsets.US_ASCII));
  }

  private static String hash(String value) {
    try {
      return HexFormat.of().formatHex(
          MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 is unavailable.", impossible);
    }
  }

  private record Credential(String sessionId, String secret) {
    private String raw() {
      return sessionId + "." + secret;
    }
  }
}
