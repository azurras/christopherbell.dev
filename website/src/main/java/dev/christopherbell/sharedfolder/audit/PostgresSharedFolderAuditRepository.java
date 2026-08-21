package dev.christopherbell.sharedfolder.audit;

import static dev.christopherbell.persistence.jooq.shared_folder.Tables.AUDIT_EVENT;

import dev.christopherbell.configuration.persistence.PostgresPersistence;
import dev.christopherbell.configuration.persistence.PostgresqlRelativePath;
import dev.christopherbell.persistence.jooq.shared_folder.tables.records.AuditEventRecord;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;

/** PostgreSQL implementation of bounded shared-folder audit persistence. */
@PostgresPersistence
public class PostgresSharedFolderAuditRepository implements SharedFolderAuditRepository {
  private final DSLContext database;

  public PostgresSharedFolderAuditRepository(DSLContext database) {
    this.database = database;
  }

  @Override public SharedFolderAuditEvent save(SharedFolderAuditEvent event) {
    String id = event.id() == null ? UUID.randomUUID().toString() : event.id();
    String relativePath = event.relativePath() == null ? null
        : PostgresqlRelativePath.require(event.relativePath(), "Shared-folder audit path");
    database.insertInto(AUDIT_EVENT).set(AUDIT_EVENT.AUDIT_EVENT_ID, id)
        .set(AUDIT_EVENT.ACCOUNT_ID, event.accountId()).set(AUDIT_EVENT.ACTION, event.action())
        .set(AUDIT_EVENT.RELATIVE_PATH, relativePath).set(AUDIT_EVENT.SIZE_BYTES, event.size())
        .set(AUDIT_EVENT.OUTCOME, event.outcome()).set(AUDIT_EVENT.FAILURE_CATEGORY, event.failureCategory())
        .set(AUDIT_EVENT.CLIENT_IP, event.clientIp())
        .set(AUDIT_EVENT.OCCURRED_AT, event.occurredAt().atOffset(ZoneOffset.UTC))
        .set(AUDIT_EVENT.EXPIRES_AT, event.expiresAt().atOffset(ZoneOffset.UTC))
        .onConflict(AUDIT_EVENT.AUDIT_EVENT_ID).doUpdate()
        .set(AUDIT_EVENT.ACCOUNT_ID, event.accountId()).set(AUDIT_EVENT.ACTION, event.action())
        .set(AUDIT_EVENT.RELATIVE_PATH, relativePath).set(AUDIT_EVENT.SIZE_BYTES, event.size())
        .set(AUDIT_EVENT.OUTCOME, event.outcome()).set(AUDIT_EVENT.FAILURE_CATEGORY, event.failureCategory())
        .set(AUDIT_EVENT.CLIENT_IP, event.clientIp())
        .set(AUDIT_EVENT.OCCURRED_AT, event.occurredAt().atOffset(ZoneOffset.UTC))
        .set(AUDIT_EVENT.EXPIRES_AT, event.expiresAt().atOffset(ZoneOffset.UTC)).execute();
    return new SharedFolderAuditEvent(id, event.accountId(), event.action(), relativePath, event.size(),
        event.outcome(), event.failureCategory(), event.clientIp(), event.occurredAt(), event.expiresAt());
  }

  @Override
  public int deleteExpired(Instant cutoff, int limit) {
    var expiresAtOrBefore = cutoff.atOffset(ZoneOffset.UTC);
    var ids = database.select(AUDIT_EVENT.AUDIT_EVENT_ID).from(AUDIT_EVENT)
        .where(AUDIT_EVENT.EXPIRES_AT.le(expiresAtOrBefore))
        .orderBy(AUDIT_EVENT.EXPIRES_AT.asc(), AUDIT_EVENT.AUDIT_EVENT_ID.asc())
        .limit(limit);
    return database.deleteFrom(AUDIT_EVENT)
        .where(AUDIT_EVENT.AUDIT_EVENT_ID.in(ids)
            .and(AUDIT_EVENT.EXPIRES_AT.le(expiresAtOrBefore))).execute();
  }

  @Override
  public List<SharedFolderAuditEvent> search(String accountId, String action, String outcome,
      String relativePath, Instant from, Instant to, int limit) {
    Condition condition = DSL.noCondition();
    if (accountId != null) condition = condition.and(AUDIT_EVENT.ACCOUNT_ID.eq(accountId));
    if (action != null) condition = condition.and(AUDIT_EVENT.ACTION.eq(action));
    if (outcome != null) condition = condition.and(AUDIT_EVENT.OUTCOME.eq(outcome));
    if (relativePath != null) condition = condition.and(AUDIT_EVENT.RELATIVE_PATH.eq(relativePath));
    if (from != null) condition = condition.and(AUDIT_EVENT.OCCURRED_AT.ge(from.atOffset(ZoneOffset.UTC)));
    if (to != null) condition = condition.and(AUDIT_EVENT.OCCURRED_AT.le(to.atOffset(ZoneOffset.UTC)));
    return database.selectFrom(AUDIT_EVENT).where(condition)
        .orderBy(AUDIT_EVENT.OCCURRED_AT.desc(), AUDIT_EVENT.AUDIT_EVENT_ID.desc()).limit(limit)
        .fetch(PostgresSharedFolderAuditRepository::map);
  }

  private static SharedFolderAuditEvent map(AuditEventRecord row) {
    return new SharedFolderAuditEvent(row.getAuditEventId(), row.getAccountId(), row.getAction(),
        row.getRelativePath(), row.getSizeBytes(), row.getOutcome(), row.getFailureCategory(),
        row.getClientIp(), row.getOccurredAt().toInstant(), row.getExpiresAt().toInstant());
  }
}
