package dev.christopherbell.admin.activity;

import dev.christopherbell.admin.model.AdminActivity;
import dev.christopherbell.configuration.persistence.PostgresPersistence;
import dev.christopherbell.configuration.persistence.PostgresqlConstraintViolationCause;
import dev.christopherbell.configuration.persistence.PostgresqlSchemaNames;
import java.sql.SQLException;
import java.sql.Types;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionOperations;

/** PostgreSQL append-only admin audit repository. */
@PostgresPersistence
public class PostgresAdminActivityRepository implements AdminActivityRepository {
  private final JdbcClient database;
  private final TransactionOperations transactions;
  private final String activityTable;
  private final String valueTable;

  public PostgresAdminActivityRepository(
      JdbcClient database, PostgresqlSchemaNames schemas, TransactionOperations transactions) {
    this.database = database;
    this.transactions = transactions;
    activityTable = schemas.qualifiedTable("platform", "admin_activity");
    valueTable = schemas.qualifiedTable("platform", "admin_activity_value");
  }

  @Override public AdminActivity insert(AdminActivity activity) { return append(activity); }
  @Override public AdminActivity save(AdminActivity activity) { return append(activity); }

  @Override
  public Optional<AdminActivity> findById(String id) {
    return database.sql("select * from %s where admin_activity_id = :id".formatted(activityTable))
        .param("id", id).query((row, ignored) -> map(row, id)).optional();
  }

  @Override
  public List<AdminActivity> findTop25ByOrderByCreatedOnDesc() {
    return database.sql("""
            select * from %s order by created_on desc, admin_activity_id desc limit 25
            """.formatted(activityTable))
        .query((row, ignored) -> map(row, row.getString("admin_activity_id"))).list();
  }

  private AdminActivity append(AdminActivity activity) {
    requireActivity(activity);
    String id = activity.getId() == null || activity.getId().isBlank()
        ? UUID.randomUUID().toString() : activity.getId();
    try {
      var saved = transactions.execute(ignored -> {
        database.sql("""
                insert into %s (
                  admin_activity_id, actor_account_id, actor_username, action,
                  target_type, target_id, target_label, reason, message,
                  before_values_present, after_values_present, metadata_present, created_on)
                values (
                  :id, :actorId, :actorUsername, :action,
                  :targetType, :targetId, :targetLabel, :reason, :message,
                  :beforePresent, :afterPresent, :metadataPresent, :createdOn)
                """.formatted(activityTable))
            .paramSource(new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("actorId", activity.getActorAccountId(), Types.VARCHAR)
                .addValue("actorUsername", activity.getActorUsername())
                .addValue("action", activity.getAction())
                .addValue("targetType", activity.getTargetType())
                .addValue("targetId", activity.getTargetId())
                .addValue("targetLabel", activity.getTargetLabel(), Types.VARCHAR)
                .addValue("reason", activity.getReason(), Types.VARCHAR)
                .addValue("message", activity.getMessage(), Types.VARCHAR)
                .addValue("beforePresent", activity.getBeforeValues() != null)
                .addValue("afterPresent", activity.getAfterValues() != null)
                .addValue("metadataPresent", activity.getMetadata() != null)
                .addValue("createdOn", activity.getCreatedOn().atOffset(ZoneOffset.UTC)))
            .update();
        insertValues(id, "before", activity.getBeforeValues());
        insertValues(id, "after", activity.getAfterValues());
        insertValues(id, "metadata", activity.getMetadata());
        return findById(id).orElseThrow();
      });
      if (saved == null) throw new IllegalStateException("Admin activity transaction returned no value");
      return saved;
    } catch (DataIntegrityViolationException failure) {
      var state = sqlState(failure);
      if ("23505".equals(state)) {
        throw new DuplicateKeyException(
            "PostgreSQL rejected a duplicate admin activity identity.",
            new PostgresqlConstraintViolationCause(state));
      }
      throw failure;
    }
  }

  private void insertValues(String id, String partition, Map<String, String> values) {
    if (values == null) return;
    values.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry ->
        database.sql("""
                insert into %s (admin_activity_id, partition_name, value_key, value_text)
                values (:id, :partition, :key, :value)
                """.formatted(valueTable))
            .param("id", id).param("partition", partition)
            .param("key", entry.getKey()).param("value", entry.getValue()).update());
  }

  private AdminActivity map(java.sql.ResultSet row, String id) throws SQLException {
    return AdminActivity.builder()
        .id(id).actorAccountId(row.getString("actor_account_id"))
        .actorUsername(row.getString("actor_username")).action(row.getString("action"))
        .targetType(row.getString("target_type")).targetId(row.getString("target_id"))
        .targetLabel(row.getString("target_label")).reason(row.getString("reason"))
        .message(row.getString("message"))
        .beforeValues(values(id, "before", row.getBoolean("before_values_present")))
        .afterValues(values(id, "after", row.getBoolean("after_values_present")))
        .metadata(values(id, "metadata", row.getBoolean("metadata_present")))
        .createdOn(row.getObject("created_on", OffsetDateTime.class).toInstant()).build();
  }

  private Map<String, String> values(String id, String partition, boolean present) {
    if (!present) return null;
    var result = new LinkedHashMap<String, String>();
    database.sql("""
            select value_key, value_text from %s
            where admin_activity_id = :id and partition_name = :partition order by value_key
            """.formatted(valueTable))
        .param("id", id).param("partition", partition)
        .query((row, ignored) -> Map.entry(row.getString(1), row.getString(2)))
        .list().forEach(entry -> result.put(entry.getKey(), entry.getValue()));
    return result;
  }

  private static void requireActivity(AdminActivity activity) {
    if (activity == null || blank(activity.getActorUsername()) || blank(activity.getAction())
        || blank(activity.getTargetType()) || blank(activity.getTargetId())
        || activity.getCreatedOn() == null) {
      throw new IllegalArgumentException("Audit identity, actor, action, target, and time are required.");
    }
  }

  private static boolean blank(String value) { return value == null || value.isBlank(); }

  private static String sqlState(Throwable failure) {
    for (var cause = failure; cause != null; cause = cause.getCause()) {
      if (cause instanceof SQLException sqlFailure) return sqlFailure.getSQLState();
    }
    return null;
  }
}
