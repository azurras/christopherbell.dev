package dev.christopherbell.admin.activity;

import static dev.christopherbell.persistence.jooq.platform.Tables.ADMIN_ACTIVITY;
import static dev.christopherbell.persistence.jooq.platform.Tables.ADMIN_ACTIVITY_VALUE;

import dev.christopherbell.admin.model.AdminActivity;
import dev.christopherbell.configuration.persistence.PostgresPersistence;
import dev.christopherbell.configuration.persistence.PostgresqlConstraintViolationCause;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.springframework.dao.DuplicateKeyException;

/** PostgreSQL append-only admin audit repository. */
@PostgresPersistence
public class PostgresAdminActivityRepository implements AdminActivityRepository {
  private final DSLContext database;

  public PostgresAdminActivityRepository(DSLContext database) {
    this.database = database;
  }

  @Override public AdminActivity insert(AdminActivity activity) { return append(activity); }

  @Override public AdminActivity save(AdminActivity activity) { return append(activity); }

  @Override public Optional<AdminActivity> findById(String id) { return findById(database, id); }

  @Override
  public List<AdminActivity> findTop25ByOrderByCreatedOnDesc() {
    return database.select(ADMIN_ACTIVITY.ADMIN_ACTIVITY_ID)
        .from(ADMIN_ACTIVITY)
        .orderBy(ADMIN_ACTIVITY.CREATED_ON.desc(), ADMIN_ACTIVITY.ADMIN_ACTIVITY_ID.desc())
        .limit(25)
        .fetch(ADMIN_ACTIVITY.ADMIN_ACTIVITY_ID)
        .stream()
        .map(id -> findById(database, id).orElseThrow())
        .toList();
  }

  private AdminActivity append(AdminActivity activity) {
    requireActivity(activity);
    String id = activity.getId() == null || activity.getId().isBlank()
        ? UUID.randomUUID().toString() : activity.getId();
    try {
      return database.transactionResult(configuration -> {
        var transaction = DSL.using(configuration);
        transaction.insertInto(ADMIN_ACTIVITY)
          .set(ADMIN_ACTIVITY.ADMIN_ACTIVITY_ID, id)
          .set(ADMIN_ACTIVITY.ACTOR_ACCOUNT_ID, activity.getActorAccountId())
          .set(ADMIN_ACTIVITY.ACTOR_USERNAME, activity.getActorUsername())
          .set(ADMIN_ACTIVITY.ACTION, activity.getAction())
          .set(ADMIN_ACTIVITY.TARGET_TYPE, activity.getTargetType())
          .set(ADMIN_ACTIVITY.TARGET_ID, activity.getTargetId())
          .set(ADMIN_ACTIVITY.TARGET_LABEL, value(activity.getTargetLabel()))
          .set(ADMIN_ACTIVITY.REASON, value(activity.getReason()))
          .set(ADMIN_ACTIVITY.MESSAGE, value(activity.getMessage()))
          .set(ADMIN_ACTIVITY.CREATED_ON, activity.getCreatedOn().atOffset(ZoneOffset.UTC))
          .execute();
        insertValues(transaction, id, "before", activity.getBeforeValues());
        insertValues(transaction, id, "after", activity.getAfterValues());
        insertValues(transaction, id, "metadata", activity.getMetadata());
        return findById(transaction, id).orElseThrow();
      });
    } catch (org.jooq.exception.IntegrityConstraintViolationException failure) {
      if ("23505".equals(failure.sqlState())) {
        throw new DuplicateKeyException("PostgreSQL rejected a duplicate admin activity identity.",
            new PostgresqlConstraintViolationCause(failure.sqlState()));
      }
      throw failure;
    }
  }

  static Optional<AdminActivity> findById(DSLContext context, String id) {
    return context.selectFrom(ADMIN_ACTIVITY)
        .where(ADMIN_ACTIVITY.ADMIN_ACTIVITY_ID.eq(id))
        .fetchOptional(row -> AdminActivity.builder()
            .id(row.getAdminActivityId())
            .actorAccountId(row.getActorAccountId())
            .actorUsername(row.getActorUsername())
            .action(row.getAction())
            .targetType(row.getTargetType())
            .targetId(row.getTargetId())
            .targetLabel(row.getTargetLabel())
            .reason(row.getReason())
            .message(row.getMessage())
            .beforeValues(values(context, id, "before"))
            .afterValues(values(context, id, "after"))
            .metadata(values(context, id, "metadata"))
            .createdOn(row.getCreatedOn().toInstant())
            .build());
  }

  private static void insertValues(
      DSLContext context, String id, String partition, Map<String, String> values) {
    if (values == null) return;
    values.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry ->
        context.insertInto(ADMIN_ACTIVITY_VALUE)
            .set(ADMIN_ACTIVITY_VALUE.ADMIN_ACTIVITY_ID, id)
            .set(ADMIN_ACTIVITY_VALUE.PARTITION_NAME, partition)
            .set(ADMIN_ACTIVITY_VALUE.VALUE_KEY, entry.getKey())
            .set(ADMIN_ACTIVITY_VALUE.VALUE_TEXT, entry.getValue())
            .execute());
  }

  private static Map<String, String> values(DSLContext context, String id, String partition) {
    var result = new LinkedHashMap<String, String>();
    context.select(ADMIN_ACTIVITY_VALUE.VALUE_KEY, ADMIN_ACTIVITY_VALUE.VALUE_TEXT)
        .from(ADMIN_ACTIVITY_VALUE)
        .where(ADMIN_ACTIVITY_VALUE.ADMIN_ACTIVITY_ID.eq(id)
            .and(ADMIN_ACTIVITY_VALUE.PARTITION_NAME.eq(partition)))
        .orderBy(ADMIN_ACTIVITY_VALUE.VALUE_KEY)
        .forEach(row -> result.put(row.value1(), row.value2()));
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
  private static String value(String value) { return value == null ? "" : value; }
}
