package dev.christopherbell.music.security;

import dev.christopherbell.configuration.mongo.domain.DomainMongoOperationsFactory;
import dev.christopherbell.configuration.mongo.domain.KindScopedMongoOperations;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;

/** Atomically aggregates denied Music entry attempts without logging credentials. */
@Component
public class MusicAccessAuditRecorder {
  private static final Duration RETENTION = Duration.ofDays(30);
  private final KindScopedMongoOperations<MusicAccessAttempt> attempts;
  private final Clock clock;

  @Autowired
  public MusicAccessAuditRecorder(DomainMongoOperationsFactory factory) {
    this(factory, Clock.systemUTC());
  }

  MusicAccessAuditRecorder(DomainMongoOperationsFactory factory, Clock clock) {
    this.attempts = factory.forType(MusicAccessAttempt.class);
    this.clock = clock;
  }

  public MusicAccessAttempt deniedAccount(String accountId, String reason) {
    return record(MusicAccessPrincipalType.ACCOUNT, bounded(accountId, 128), reason);
  }

  public MusicAccessAttempt deniedIp(String ip, String reason) {
    return record(MusicAccessPrincipalType.IP, bounded(ip, 64), reason);
  }

  private MusicAccessAttempt record(
      MusicAccessPrincipalType type,
      String principal,
      String reason) {
    String safeReason = bounded(reason, 64);
    var now = clock.instant().truncatedTo(ChronoUnit.MILLIS);
    var bucket = now.atZone(ZoneOffset.UTC).truncatedTo(ChronoUnit.HOURS).toInstant();
    String id = hash(type + "\n" + principal + "\n" + safeReason + "\n" + bucket);
    var update = new Update()
        .inc("count", 1)
        .set("lastAttemptAt", now)
        .set("expiresAt", now.plus(RETENTION));
    var identity = Query.query(Criteria.where("id").is(id));
    var existing = attempts.findAndUpdate(identity, update);
    if (existing.isPresent()) {
      return existing.get();
    }
    var initial = new MusicAccessAttempt(
        id, type, principal, safeReason, 1, now, now, now.plus(RETENTION));
    try {
      return attempts.insert(initial);
    } catch (DuplicateKeyException concurrentInsert) {
      return attempts.findAndUpdate(identity, update)
          .orElseThrow(() -> new IllegalStateException(
              "Concurrent Music access audit insert did not leave a record."));
    }
  }

  private String bounded(String value, int maximum) {
    if (value == null || value.isBlank()) return "unknown";
    String clean = value.strip().replaceAll("[\\p{Cc}\\p{Cf}]", "");
    return clean.length() <= maximum ? clean : clean.substring(0, maximum);
  }

  private String hash(String material) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
          .digest(material.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 is unavailable.", impossible);
    }
  }
}
