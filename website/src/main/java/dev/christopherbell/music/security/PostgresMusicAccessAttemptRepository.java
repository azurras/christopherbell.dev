package dev.christopherbell.music.security;

import static dev.christopherbell.persistence.jooq.music.Tables.ACCESS_ATTEMPT;

import dev.christopherbell.configuration.persistence.PostgresPersistence;
import dev.christopherbell.persistence.jooq.music.tables.records.AccessAttemptRecord;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.jooq.DSLContext;

/** PostgreSQL adapter for atomic aggregation and bounded access-attempt queries. */
@PostgresPersistence
public class PostgresMusicAccessAttemptRepository implements MusicAccessAttemptRepository {
  private final DSLContext database;

  public PostgresMusicAccessAttemptRepository(DSLContext database) {
    this.database = database;
  }

  @Override
  public MusicAccessAttempt record(String id, MusicAccessPrincipalType type, String principal,
      String reason, Instant occurredAt, Instant expiresAt) {
    AccessAttemptRecord row = database.insertInto(ACCESS_ATTEMPT)
        .set(ACCESS_ATTEMPT.ACCESS_ATTEMPT_ID, id).set(ACCESS_ATTEMPT.PRINCIPAL_TYPE, type.name())
        .set(ACCESS_ATTEMPT.PRINCIPAL, principal).set(ACCESS_ATTEMPT.REASON, reason)
        .set(ACCESS_ATTEMPT.FIRST_ATTEMPT_AT, occurredAt.atOffset(ZoneOffset.UTC))
        .set(ACCESS_ATTEMPT.LAST_ATTEMPT_AT, occurredAt.atOffset(ZoneOffset.UTC))
        .set(ACCESS_ATTEMPT.ATTEMPT_COUNT, 1L)
        .set(ACCESS_ATTEMPT.EXPIRES_AT, expiresAt.atOffset(ZoneOffset.UTC))
        .onConflict(ACCESS_ATTEMPT.ACCESS_ATTEMPT_ID).doUpdate()
        .set(ACCESS_ATTEMPT.LAST_ATTEMPT_AT, occurredAt.atOffset(ZoneOffset.UTC))
        .set(ACCESS_ATTEMPT.ATTEMPT_COUNT, ACCESS_ATTEMPT.ATTEMPT_COUNT.plus(1L))
        .set(ACCESS_ATTEMPT.EXPIRES_AT, expiresAt.atOffset(ZoneOffset.UTC))
        .returning().fetchOne();
    if (row == null) throw new IllegalStateException("Music access audit upsert returned no row.");
    return map(row);
  }

  @Override public List<MusicAccessAttempt> recent(int limit) {
    return database.selectFrom(ACCESS_ATTEMPT)
        .orderBy(ACCESS_ATTEMPT.LAST_ATTEMPT_AT.desc(), ACCESS_ATTEMPT.ACCESS_ATTEMPT_ID.asc())
        .limit(limit).fetch(PostgresMusicAccessAttemptRepository::map);
  }

  @Override public int deleteExpired(Instant cutoff, int limit) {
    var ids = database.select(ACCESS_ATTEMPT.ACCESS_ATTEMPT_ID).from(ACCESS_ATTEMPT)
        .where(ACCESS_ATTEMPT.EXPIRES_AT.le(cutoff.atOffset(ZoneOffset.UTC)))
        .orderBy(ACCESS_ATTEMPT.EXPIRES_AT.asc(), ACCESS_ATTEMPT.ACCESS_ATTEMPT_ID.asc())
        .limit(limit);
    return database.deleteFrom(ACCESS_ATTEMPT)
        .where(ACCESS_ATTEMPT.ACCESS_ATTEMPT_ID.in(ids)).execute();
  }

  private static MusicAccessAttempt map(AccessAttemptRecord row) {
    return new MusicAccessAttempt(row.getAccessAttemptId(),
        MusicAccessPrincipalType.valueOf(row.getPrincipalType()), row.getPrincipal(), row.getReason(),
        row.getAttemptCount(), row.getFirstAttemptAt().toInstant(), row.getLastAttemptAt().toInstant(),
        row.getExpiresAt().toInstant());
  }
}
