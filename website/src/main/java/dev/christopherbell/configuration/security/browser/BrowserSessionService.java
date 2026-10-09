package dev.christopherbell.configuration.security.browser;

import dev.christopherbell.account.AccountRepository;
import dev.christopherbell.account.api.LoginTokens;
import dev.christopherbell.account.auth.AccountSecurityFingerprint;
import dev.christopherbell.account.auth.AccountSessionRevoker;
import dev.christopherbell.account.model.Account;
import dev.christopherbell.account.model.AccountStatus;
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
public class BrowserSessionService implements AccountSessionRevoker {
  static final Duration IDLE_LIFETIME = Duration.ofDays(7);
  static final Duration ABSOLUTE_LIFETIME = Duration.ofDays(30);
  static final Duration ROTATION_INTERVAL = Duration.ofDays(1);
  static final Duration ROTATION_OVERLAP = Duration.ofMinutes(2);
  static final Duration ACTIVITY_WRITE_INTERVAL = Duration.ofMinutes(5);
  private static final int TOKEN_BYTES = 32;
  private static final int MAX_RAW_TOKEN_LENGTH = 256;
  private static final int MIN_SECRET_LENGTH = 32;
  private static final int MAX_SECRET_LENGTH = 128;

  private final BrowserSessionRepository sessions;
  private final BrowserSessionActivityStore activity;
  private final BrowserSessionAuthenticationStore authentications;
  private final AccountRepository accounts;
  private final LoginTokens loginTokens;
  private final Clock clock;
  private final SecureRandom random = new SecureRandom();

  public BrowserSessionService(
      BrowserSessionRepository sessions,
      BrowserSessionActivityStore activity,
      BrowserSessionAuthenticationStore authentications,
      AccountRepository accounts,
      LoginTokens loginTokens,
      Clock clock) {
    this.sessions = sessions;
    this.activity = activity;
    this.authentications = authentications;
    this.accounts = accounts;
    this.loginTokens = loginTokens;
    this.clock = clock;
  }

  /** Exchanges a short-lived login JWT for a persisted opaque browser session. */
  public String create(String loginJwt) {
    var claims = loginTokens.verifiedClaimsOf(loginJwt);
    var accountId = claims.getSubject();
    var presentedFingerprint = claims.get(AccountSecurityFingerprint.CLAIM, String.class);
    var account = accounts.findById(accountId)
        .filter(this::isActive)
        .filter(current -> current.getRole() != null)
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
    return parse(rawToken).flatMap(credential -> authentications.findById(credential.sessionId())
        .flatMap(stored -> authenticate(stored, credential, interactive, clock.instant())));
  }

  /**
   * Validates a stored session against the presented credential and the current account, revoking
   * it when either no longer holds, then renews it for interactive requests.
   */
  private Optional<AuthenticatedBrowserSession> authenticate(
      BrowserSessionAuthentication stored,
      Credential credential,
      boolean interactive,
      Instant now
  ) {
    var session = stored.session();
    var account = stored.account();
    if (!usable(session, credential.secret(), now)
        || !account.validates(session.getAccountSecurityFingerprint())) {
      return revoked(session);
    }
    if (!interactive) {
      return Optional.of(authenticated(account, Optional.empty()));
    }

    var idleExpiresOn = earlier(now.plus(IDLE_LIFETIME), session.getAbsoluteExpiresOn());
    if (dueForRotation(session, credential, now)) {
      return rotate(session, account, credential, now, idleExpiresOn);
    }
    if (dueForActivityWrite(session, now)) {
      return activity.touch(session.getId(), session.getLastSeenOn(), now, idleExpiresOn)
          .filter(touched -> usable(touched, credential.secret(), now))
          .map(touched -> authenticated(account, Optional.empty()));
    }
    return Optional.of(authenticated(account, Optional.empty()));
  }

  /** Rotation is due daily, only for the current secret, and only while the overlap still fits. */
  private boolean dueForRotation(BrowserSession session, Credential credential, Instant now) {
    return !now.isBefore(session.getRotatedOn().plus(ROTATION_INTERVAL))
        && constantTimeEquals(session.getTokenHash(), hash(credential.secret()))
        // Rotating with less time would shorten the fixed previous-token overlap.
        && !session.getAbsoluteExpiresOn().isBefore(now.plus(ROTATION_OVERLAP));
  }

  private boolean dueForActivityWrite(BrowserSession session, Instant now) {
    return !now.isBefore(session.getLastSeenOn().plus(ACTIVITY_WRITE_INTERVAL));
  }

  /** Atomically rotates the secret; when another request rotated first, falls back to the overlap. */
  private Optional<AuthenticatedBrowserSession> rotate(
      BrowserSession session,
      BrowserSessionAccount account,
      Credential credential,
      Instant now,
      Instant idleExpiresOn
  ) {
    var rotated = credential(session.getId());
    var updated = activity.rotate(
        session.getId(),
        session.getTokenHash(),
        session.getRotatedOn(),
        hash(rotated.secret()),
        now,
        earlier(now.plus(ROTATION_OVERLAP), session.getAbsoluteExpiresOn()),
        idleExpiresOn);
    if (updated.isEmpty()) {
      return afterLostRotation(session.getId(), credential, now);
    }
    return updated
        .filter(current -> usable(current, credential.secret(), now))
        .map(current -> authenticated(account, Optional.of(rotated.raw())));
  }

  /**
   * Another request rotated this session first, so the presented secret is accepted only as the
   * previous token within its overlap, and the reloaded account must still validate.
   */
  private Optional<AuthenticatedBrowserSession> afterLostRotation(
      String sessionId,
      Credential credential,
      Instant now
  ) {
    return authentications.findById(sessionId).flatMap(reloaded -> {
      var session = reloaded.session();
      var account = reloaded.account();
      if (!validPreviousCredential(session, hash(credential.secret()), now)
          || expired(session, now)
          || !completeSnapshot(session)
          || !account.validates(session.getAccountSecurityFingerprint())) {
        return revoked(session);
      }
      return Optional.of(authenticated(account, Optional.empty()));
    });
  }

  /** A current or overlapping credential for a complete session that has not expired. */
  private boolean usable(BrowserSession session, String secret, Instant now) {
    return validCredential(session, secret, now) && !expired(session, now) && completeSnapshot(session);
  }

  private Optional<AuthenticatedBrowserSession> revoked(BrowserSession session) {
    sessions.delete(session);
    return Optional.empty();
  }

  private static AuthenticatedBrowserSession authenticated(
      BrowserSessionAccount account,
      Optional<String> rotatedToken
  ) {
    return new AuthenticatedBrowserSession(account.id(), account.role(), rotatedToken);
  }

  /** Revokes the session named by a cookie without revealing whether it existed. */
  public void revoke(String rawToken) {
    parse(rawToken).ifPresent(token -> sessions.deleteById(token.sessionId()));
  }

  /** Revokes every browser session for an account. */
  @Override
  public void revokeAll(String accountId) {
    if (accountId != null && !accountId.isBlank()) {
      sessions.deleteByAccountId(accountId);
    }
  }

  private boolean isActive(Account account) {
    return AccountStatus.ACTIVE.equals(account.getStatus());
  }

  private boolean completeSnapshot(BrowserSession session) {
    return session.getAccountId() != null
        && !session.getAccountId().isBlank()
        && session.getRole() != null
        && session.getAccountSecurityFingerprint() != null
        && !session.getAccountSecurityFingerprint().isBlank();
  }

  private boolean expired(BrowserSession session, Instant now) {
    return !now.isBefore(session.getIdleExpiresOn())
        || !now.isBefore(session.getAbsoluteExpiresOn());
  }

  private boolean validCredential(BrowserSession session, String secret, Instant now) {
    var candidateHash = hash(secret);
    return constantTimeEquals(session.getTokenHash(), candidateHash)
        || validPreviousCredential(session, candidateHash, now);
  }

  private boolean validPreviousCredential(
      BrowserSession session, String candidateHash, Instant now) {
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
    if (rawToken == null || rawToken.length() > MAX_RAW_TOKEN_LENGTH) {
      return Optional.empty();
    }
    int separator = rawToken.indexOf('.');
    if (separator <= 0 || separator != rawToken.lastIndexOf('.')) {
      return Optional.empty();
    }
    try {
      UUID.fromString(rawToken.substring(0, separator));
    } catch (IllegalArgumentException invalidId) {
      return Optional.empty();
    }
    var secret = rawToken.substring(separator + 1);
    if (secret.length() < MIN_SECRET_LENGTH || secret.length() > MAX_SECRET_LENGTH) {
      return Optional.empty();
    }
    return Optional.of(new Credential(rawToken.substring(0, separator), secret));
  }

  private static Instant earlier(Instant first, Instant second) {
    return first.isBefore(second) ? first : second;
  }

  private static boolean constantTimeEquals(String expected, String actual) {
    if (expected == null || actual == null) {
      return false;
    }
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
