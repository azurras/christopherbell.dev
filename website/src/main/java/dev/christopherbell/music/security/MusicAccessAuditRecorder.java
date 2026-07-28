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
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;

/** Atomically aggregates denied Music entry attempts without logging credentials. */
@Component
public class MusicAccessAuditRecorder {
  private static final Duration RETENTION = Duration.ofDays(30);
  private final MongoTemplate mongo;
  private final Clock clock;

  @Autowired
  public MusicAccessAuditRecorder(MongoTemplate mongo) {
    this(mongo, Clock.systemUTC());
  }

  MusicAccessAuditRecorder(MongoTemplate mongo, Clock clock) {
    this.mongo = mongo;
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
    var now = clock.instant();
    var bucket = now.atZone(ZoneOffset.UTC).truncatedTo(ChronoUnit.HOURS).toInstant();
    String id = hash(type + "\n" + principal + "\n" + safeReason + "\n" + bucket);
    var update = new Update()
        .setOnInsert("principalType", type)
        .setOnInsert("principal", principal)
        .setOnInsert("reason", safeReason)
        .setOnInsert("firstAttemptAt", now)
        .inc("count", 1)
        .set("lastAttemptAt", now)
        .set("expiresAt", now.plus(RETENTION));
    return mongo.findAndModify(
        Query.query(Criteria.where("_id").is(id)),
        update,
        FindAndModifyOptions.options().upsert(true).returnNew(true),
        MusicAccessAttempt.class);
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
