package dev.christopherbell.admin.activity;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.christopherbell.admin.model.AdminActivity;
import dev.christopherbell.configuration.persistence.PostgresPersistence;
import dev.christopherbell.configuration.persistence.PostgresqlSchemaNames;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;

/** PostgreSQL bounded, stable admin activity page query. */
@PostgresPersistence
public class PostgresAdminActivityQueryRepository implements AdminActivityQueryPort {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final TypeReference<Map<String, String>> STRING_MAP = new TypeReference<>() {};
  private final JdbcClient database;
  private final String activityTable;
  private final String valueTable;

  public PostgresAdminActivityQueryRepository(
      JdbcClient database, PostgresqlSchemaNames schemas) {
    this.database = database;
    activityTable = schemas.qualifiedTable("platform", "admin_activity");
    valueTable = schemas.qualifiedTable("platform", "admin_activity_value");
  }

  @Override
  public AdminActivityPage query(AdminActivityQuery request) {
    var clauses = new ArrayList<String>();
    var parameters = new HashMap<String, Object>();
    if (hasText(request.action())) { clauses.add("action = :action"); parameters.put("action", request.action().strip()); }
    if (hasText(request.targetType())) { clauses.add("target_type = :targetType"); parameters.put("targetType", request.targetType().strip()); }
    if (hasText(request.actor())) { clauses.add("lower(actor_username) like :actor"); parameters.put("actor", "%" + escapeLike(request.actor().strip().toLowerCase(java.util.Locale.ROOT)) + "%"); }
    if (request.from() != null) {
      clauses.add("created_on between :from and :to");
      parameters.put("from", request.from().atOffset(ZoneOffset.UTC));
      parameters.put("to", request.to().atOffset(ZoneOffset.UTC));
    }
    var where = clauses.isEmpty() ? "true" : String.join(" and ", clauses);
    var count = statement("select count(*) from %s where %s".formatted(activityTable, where), parameters)
        .query(Long.class).single();
    var items = statement("""
            select activity.*,
              (select jsonb_object_agg(value_key, value_text order by value_key)
                from %2$s where admin_activity_id = activity.admin_activity_id
                  and partition_name = 'before')::text as before_values,
              (select jsonb_object_agg(value_key, value_text order by value_key)
                from %2$s where admin_activity_id = activity.admin_activity_id
                  and partition_name = 'after')::text as after_values,
              (select jsonb_object_agg(value_key, value_text order by value_key)
                from %2$s where admin_activity_id = activity.admin_activity_id
                  and partition_name = 'metadata')::text as metadata_values
            from %1$s activity where %3$s
            order by created_on desc, admin_activity_id desc limit :limit offset :offset
            """.formatted(activityTable, valueTable, where), parameters)
        .param("limit", request.size())
        .param("offset", Math.multiplyExact(request.page(), request.size()))
        .query(PostgresAdminActivityQueryRepository::map).list();
    int pages = count == 0 ? 0 : Math.toIntExact((count + request.size() - 1) / request.size());
    return new AdminActivityPage(items, request.page(), request.size(), count, pages);
  }

  private JdbcClient.StatementSpec statement(String sql, Map<String, ?> parameters) {
    var result = database.sql(sql);
    for (var entry : parameters.entrySet()) result.param(entry.getKey(), entry.getValue());
    return result;
  }

  private static AdminActivity map(java.sql.ResultSet row, int rowNumber) throws SQLException {
    return AdminActivity.builder()
        .id(row.getString("admin_activity_id")).actorAccountId(row.getString("actor_account_id"))
        .actorUsername(row.getString("actor_username")).action(row.getString("action"))
        .targetType(row.getString("target_type")).targetId(row.getString("target_id"))
        .targetLabel(row.getString("target_label")).reason(row.getString("reason"))
        .message(row.getString("message"))
        .createdOn(row.getObject("created_on", OffsetDateTime.class).toInstant())
        .beforeValues(whenPresent(row.getString("before_values"), row.getBoolean("before_values_present")))
        .afterValues(whenPresent(row.getString("after_values"), row.getBoolean("after_values_present")))
        .metadata(whenPresent(row.getString("metadata_values"), row.getBoolean("metadata_present")))
        .build();
  }

  private static Map<String, String> whenPresent(String json, boolean present) throws SQLException {
    if (!present) return null;
    if (json == null) return Map.of();
    try {
      return JSON.readValue(json, STRING_MAP);
    } catch (java.io.IOException failure) {
      throw new SQLException("PostgreSQL returned invalid admin activity JSON.", failure);
    }
  }

  private static boolean hasText(String value) { return value != null && !value.isBlank(); }
  private static String escapeLike(String value) {
    return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
  }
}
