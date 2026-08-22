package dev.christopherbell.account.deletion;

import dev.christopherbell.configuration.persistence.PostgresPersistence;
import dev.christopherbell.configuration.persistence.PostgresqlSchemaNames;
import java.sql.SQLException;
import java.sql.Types;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;

/** PostgreSQL persistence for durable account-deletion checkpoints. */
@PostgresPersistence
public class PostgresAccountDeletionJobRepository implements AccountDeletionJobRepository {
  private final JdbcClient database;
  private final String table;

  public PostgresAccountDeletionJobRepository(
      JdbcClient database, PostgresqlSchemaNames schemas) {
    this.database = database;
    table = schemas.qualifiedTable("identity", "account_deletion_job");
  }

  @Override
  public Optional<AccountDeletionJob> findById(String id) {
    return database.sql("select * from %s where account_deletion_job_id = :id".formatted(table))
        .param("id", id).query(PostgresAccountDeletionJobRepository::map).optional();
  }

  @Override
  public AccountDeletionJob save(AccountDeletionJob job) {
    return database.sql("""
            insert into %s
              (account_deletion_job_id, status, next_step, failure_category,
               created_on, last_updated_on, completed_on)
            values (:id, :status, :nextStep, :failureCategory, :createdOn, :updatedOn, :completedOn)
            on conflict (account_deletion_job_id) do update set
              status = excluded.status,
              next_step = excluded.next_step,
              failure_category = excluded.failure_category,
              last_updated_on = excluded.last_updated_on,
              completed_on = excluded.completed_on,
              version = %s.version + 1
            returning *
            """.formatted(table, table))
        .param("id", job.getId()).param("status", job.getStatus().name())
        .param("nextStep", job.getNextStep() == null ? null : job.getNextStep().name(), Types.VARCHAR)
        .param("failureCategory", job.getFailureCategory(), Types.VARCHAR)
        .param("createdOn", job.getCreatedOn().atOffset(ZoneOffset.UTC))
        .param("updatedOn", job.getLastUpdatedOn().atOffset(ZoneOffset.UTC))
        .param("completedOn", job.getCompletedOn() == null
            ? null : job.getCompletedOn().atOffset(ZoneOffset.UTC), Types.TIMESTAMP_WITH_TIMEZONE)
        .query(PostgresAccountDeletionJobRepository::map).single();
  }

  private static AccountDeletionJob map(java.sql.ResultSet record, int rowNumber)
      throws SQLException {
    var job = new AccountDeletionJob();
    job.setId(record.getString("account_deletion_job_id"));
    job.setStatus(AccountDeletionStatus.valueOf(record.getString("status")));
    var nextStep = record.getString("next_step");
    job.setNextStep(nextStep == null ? null : AccountDeletionStep.valueOf(nextStep));
    job.setFailureCategory(record.getString("failure_category"));
    job.setCreatedOn(record.getObject("created_on", OffsetDateTime.class).toInstant());
    job.setLastUpdatedOn(record.getObject("last_updated_on", OffsetDateTime.class).toInstant());
    job.setCompletedOn(instant(record.getObject("completed_on", OffsetDateTime.class)));
    return job;
  }

  private static java.time.Instant instant(OffsetDateTime value) {
    return value == null ? null : value.toInstant();
  }
}
