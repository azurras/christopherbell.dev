package dev.christopherbell.music.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** Atomically aggregates denied Music entry attempts without logging credentials. */
@Component
public class MusicAccessAuditRecorder {
  private static final Duration RETENTION = Duration.ofDays(30);
  private final MusicAccessAttemptRepository attempts;
  private final Clock clock;

  @Autowired
  public MusicAccessAuditRecorder(MusicAccessAttemptRepository attempts) {
    this(attempts, Clock.systemUTC());
  }

  MusicAccessAuditRecorder(MusicAccessAttemptRepository attempts, Clock clock) {
    this.attempts = attempts;
    this.clock = clock;
  }

  public MusicAccessAttempt deniedAccount(String accountId, String reason) {
    return record(MusicAccessPrincipalType.ACCOUNT, bounded(accountId, 128), reason);
  }

  public MusicAccessAttempt deniedIp(String ip, String reason) {
    return record(MusicAccessPrincipalType.IP, bounded(ip, 64), reason);
  }

  private MusicAccessAttempt record(MusicAccessPrincipalType type, String principal, String reason) {
    String safeReason = bounded(reason, 64);
    var now = clock.instant().truncatedTo(ChronoUnit.MILLIS);
    var bucket = now.atZone(ZoneOffset.UTC).truncatedTo(ChronoUnit.HOURS).toInstant();
    String id = hash(type + "\n" + principal + "\n" + safeReason + "\n" + bucket);
    return attempts.record(id, type, principal, safeReason, now, now.plus(RETENTION));
  }

  private static String bounded(String value, int maximum) {
    if (value == null || value.isBlank()) return "unknown";
    String clean = value.strip().replaceAll("[\\p{Cc}\\p{Cf}]", "");
    return clean.length() <= maximum ? clean : clean.substring(0, maximum);
  }

  private static String hash(String material) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
          .digest(material.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 is unavailable.", impossible);
    }
  }
}
