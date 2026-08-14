package dev.christopherbell.admin.activity;

import static dev.christopherbell.persistence.jooq.platform.Tables.ADMIN_ACTIVITY;
import static dev.christopherbell.persistence.jooq.platform.Tables.ADMIN_ACTIVITY_VALUE;

import dev.christopherbell.admin.model.AdminActivity;
import dev.christopherbell.configuration.persistence.PostgresPersistence;
import java.util.Map;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Record2;
import org.jooq.impl.DSL;

/** PostgreSQL bounded, stable admin activity page query. */
@PostgresPersistence
public class PostgresAdminActivityQueryRepository implements AdminActivityQueryPort {
  private final DSLContext database;

  public PostgresAdminActivityQueryRepository(DSLContext database) {
    this.database = database;
  }

  @Override
  public AdminActivityPage query(AdminActivityQuery request) {
    Condition filter = DSL.noCondition();
    if (hasText(request.action())) {
      filter = filter.and(ADMIN_ACTIVITY.ACTION.eq(request.action().strip()));
    }
    if (hasText(request.targetType())) {
      filter = filter.and(ADMIN_ACTIVITY.TARGET_TYPE.eq(request.targetType().strip()));
    }
    if (hasText(request.actor())) {
      filter = filter.and(ADMIN_ACTIVITY.ACTOR_USERNAME.containsIgnoreCase(request.actor().strip()));
    }
    if (request.from() != null) {
      filter = filter.and(ADMIN_ACTIVITY.CREATED_ON.between(
          request.from().atOffset(java.time.ZoneOffset.UTC),
          request.to().atOffset(java.time.ZoneOffset.UTC)));
    }

    long total = database.fetchCount(ADMIN_ACTIVITY, filter);
    Field<Map<String, String>> before = values("before").as("before_values");
    Field<Map<String, String>> after = values("after").as("after_values");
    Field<Map<String, String>> metadata = values("metadata").as("metadata_values");
    var items = database.select(
            ADMIN_ACTIVITY.ADMIN_ACTIVITY_ID,
            ADMIN_ACTIVITY.ACTOR_ACCOUNT_ID,
            ADMIN_ACTIVITY.ACTOR_USERNAME,
            ADMIN_ACTIVITY.ACTION,
            ADMIN_ACTIVITY.TARGET_TYPE,
            ADMIN_ACTIVITY.TARGET_ID,
            ADMIN_ACTIVITY.TARGET_LABEL,
            ADMIN_ACTIVITY.REASON,
            ADMIN_ACTIVITY.MESSAGE,
            ADMIN_ACTIVITY.CREATED_ON,
            before,
            after,
            metadata)
        .from(ADMIN_ACTIVITY)
        .where(filter)
        .orderBy(ADMIN_ACTIVITY.CREATED_ON.desc(), ADMIN_ACTIVITY.ADMIN_ACTIVITY_ID.desc())
        .offset(Math.multiplyExact(request.page(), request.size()))
        .limit(request.size())
        .fetch(row -> AdminActivity.builder()
            .id(row.get(ADMIN_ACTIVITY.ADMIN_ACTIVITY_ID))
            .actorAccountId(row.get(ADMIN_ACTIVITY.ACTOR_ACCOUNT_ID))
            .actorUsername(row.get(ADMIN_ACTIVITY.ACTOR_USERNAME))
            .action(row.get(ADMIN_ACTIVITY.ACTION))
            .targetType(row.get(ADMIN_ACTIVITY.TARGET_TYPE))
            .targetId(row.get(ADMIN_ACTIVITY.TARGET_ID))
            .targetLabel(row.get(ADMIN_ACTIVITY.TARGET_LABEL))
            .reason(row.get(ADMIN_ACTIVITY.REASON))
            .message(row.get(ADMIN_ACTIVITY.MESSAGE))
            .createdOn(row.get(ADMIN_ACTIVITY.CREATED_ON).toInstant())
            .beforeValues(row.get(before))
            .afterValues(row.get(after))
            .metadata(row.get(metadata))
            .build());
    int pages = total == 0 ? 0 : Math.toIntExact((total + request.size() - 1) / request.size());
    return new AdminActivityPage(items, request.page(), request.size(), total, pages);
  }

  private static Field<Map<String, String>> values(String partition) {
    return DSL.multiset(DSL.select(
            ADMIN_ACTIVITY_VALUE.VALUE_KEY,
            ADMIN_ACTIVITY_VALUE.VALUE_TEXT)
        .from(ADMIN_ACTIVITY_VALUE)
        .where(ADMIN_ACTIVITY_VALUE.ADMIN_ACTIVITY_ID.eq(ADMIN_ACTIVITY.ADMIN_ACTIVITY_ID)
            .and(ADMIN_ACTIVITY_VALUE.PARTITION_NAME.eq(partition)))
        .orderBy(ADMIN_ACTIVITY_VALUE.VALUE_KEY))
        .convertFrom(rows -> rows.intoMap(Record2::value1, Record2::value2));
  }

  private static boolean hasText(String value) {
    return value != null && !value.isBlank();
  }
}
