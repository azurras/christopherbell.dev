package dev.christopherbell.configuration.security.browser;

import dev.christopherbell.account.model.Role;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

@Entity
@Table(name = "browser_session", schema = "identity")
final class BrowserSessionEntity {
  @Id
  @Column(name = "browser_session_id", nullable = false, length = 128)
  private String id;

  @Column(name = "account_id", nullable = false, length = 128)
  private String accountId;

  @Column(name = "role", length = 16)
  private String role;

  @Column(name = "token_hash", nullable = false, length = 512)
  private String tokenHash;

  @Column(name = "previous_token_hash", length = 512)
  private String previousTokenHash;

  @Column(name = "previous_token_expires_on")
  private OffsetDateTime previousTokenExpiresOn;

  @Column(name = "account_security_fingerprint", nullable = false, length = 512)
  private String accountSecurityFingerprint;

  @Column(name = "created_on", nullable = false)
  private OffsetDateTime createdOn;

  @Column(name = "last_seen_on", nullable = false)
  private OffsetDateTime lastSeenOn;

  @Column(name = "rotated_on")
  private OffsetDateTime rotatedOn;

  @Column(name = "idle_expires_on", nullable = false)
  private OffsetDateTime idleExpiresOn;

  @Column(name = "absolute_expires_on", nullable = false)
  private OffsetDateTime absoluteExpiresOn;

  @Version
  @Column(name = "version", nullable = false)
  private Long version;

  protected BrowserSessionEntity() {}

  static BrowserSessionEntity create(BrowserSession session) {
    var entity = new BrowserSessionEntity();
    entity.id = session.getId();
    entity.createdOn = offset(session.getCreatedOn());
    entity.apply(session);
    return entity;
  }

  void apply(BrowserSession session) {
    accountId = session.getAccountId();
    role = session.getRole() == null ? null : session.getRole().name();
    tokenHash = session.getTokenHash();
    previousTokenHash = session.getPreviousTokenHash();
    previousTokenExpiresOn = offset(session.getPreviousTokenExpiresOn());
    accountSecurityFingerprint = session.getAccountSecurityFingerprint();
    lastSeenOn = offset(session.getLastSeenOn());
    rotatedOn = offset(session.getRotatedOn());
    idleExpiresOn = offset(session.getIdleExpiresOn());
    absoluteExpiresOn = offset(session.getAbsoluteExpiresOn());
  }

  BrowserSession toDomain() {
    return BrowserSession.builder()
        .id(id)
        .accountId(accountId)
        .role(role == null ? null : Role.valueOf(role))
        .tokenHash(tokenHash)
        .previousTokenHash(previousTokenHash)
        .previousTokenExpiresOn(instant(previousTokenExpiresOn))
        .accountSecurityFingerprint(accountSecurityFingerprint)
        .createdOn(instant(createdOn))
        .lastSeenOn(instant(lastSeenOn))
        .rotatedOn(instant(rotatedOn))
        .idleExpiresOn(instant(idleExpiresOn))
        .absoluteExpiresOn(instant(absoluteExpiresOn))
        .build();
  }

  private static OffsetDateTime offset(java.time.Instant value) {
    return value == null ? null : value.atOffset(ZoneOffset.UTC);
  }

  private static java.time.Instant instant(OffsetDateTime value) {
    return value == null ? null : value.toInstant();
  }
}
