package dev.christopherbell.account.deletion;

import static dev.christopherbell.persistence.jooq.identity.Tables.ACCOUNT_DELETION_JOB;

import dev.christopherbell.configuration.persistence.PostgresPersistence;
import dev.christopherbell.persistence.jooq.identity.tables.records.AccountDeletionJobRecord;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import org.jooq.DSLContext;

/** PostgreSQL persistence for durable account-deletion checkpoints. */
@PostgresPersistence
public class PostgresAccountDeletionJobRepository implements AccountDeletionJobRepository {
  private final DSLContext database;

  public PostgresAccountDeletionJobRepository(DSLContext database) {
    this.database = database;
  }

  @Override
  public Optional<AccountDeletionJob> findById(String id) {
    return database.selectFrom(ACCOUNT_DELETION_JOB)
        .where(ACCOUNT_DELETION_JOB.ACCOUNT_DELETION_JOB_ID.eq(id))
        .fetchOptional(PostgresAccountDeletionJobRepository::map);
  }

  @Override
  public AccountDeletionJob save(AccountDeletionJob job) {
    database.insertInto(ACCOUNT_DELETION_JOB)
        .set(ACCOUNT_DELETION_JOB.ACCOUNT_DELETION_JOB_ID, job.getId())
        .set(ACCOUNT_DELETION_JOB.STATUS, job.getStatus().name())
        .set(ACCOUNT_DELETION_JOB.NEXT_STEP,
            job.getNextStep() == null ? null : job.getNextStep().name())
        .set(ACCOUNT_DELETION_JOB.FAILURE_CATEGORY, job.getFailureCategory())
        .set(ACCOUNT_DELETION_JOB.CREATED_ON, job.getCreatedOn().atOffset(ZoneOffset.UTC))
        .set(ACCOUNT_DELETION_JOB.LAST_UPDATED_ON,
            job.getLastUpdatedOn().atOffset(ZoneOffset.UTC))
        .set(ACCOUNT_DELETION_JOB.COMPLETED_ON, job.getCompletedOn() == null
            ? null : job.getCompletedOn().atOffset(ZoneOffset.UTC))
        .onConflict(ACCOUNT_DELETION_JOB.ACCOUNT_DELETION_JOB_ID)
        .doUpdate()
        .set(ACCOUNT_DELETION_JOB.STATUS, job.getStatus().name())
        .set(ACCOUNT_DELETION_JOB.NEXT_STEP,
            job.getNextStep() == null ? null : job.getNextStep().name())
        .set(ACCOUNT_DELETION_JOB.FAILURE_CATEGORY, job.getFailureCategory())
        .set(ACCOUNT_DELETION_JOB.LAST_UPDATED_ON,
            job.getLastUpdatedOn().atOffset(ZoneOffset.UTC))
        .set(ACCOUNT_DELETION_JOB.COMPLETED_ON, job.getCompletedOn() == null
            ? null : job.getCompletedOn().atOffset(ZoneOffset.UTC))
        .set(ACCOUNT_DELETION_JOB.VERSION, ACCOUNT_DELETION_JOB.VERSION.plus(1L))
        .execute();
    return findById(job.getId()).orElseThrow();
  }

  private static AccountDeletionJob map(AccountDeletionJobRecord record) {
    var job = new AccountDeletionJob();
    job.setId(record.getAccountDeletionJobId());
    job.setStatus(AccountDeletionStatus.valueOf(record.getStatus()));
    job.setNextStep(record.getNextStep() == null
        ? null : AccountDeletionStep.valueOf(record.getNextStep()));
    job.setFailureCategory(record.getFailureCategory());
    job.setCreatedOn(record.getCreatedOn().toInstant());
    job.setLastUpdatedOn(record.getLastUpdatedOn().toInstant());
    job.setCompletedOn(instant(record.getCompletedOn()));
    return job;
  }

  private static java.time.Instant instant(OffsetDateTime value) {
    return value == null ? null : value.toInstant();
  }
}
