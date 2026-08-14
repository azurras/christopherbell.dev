package dev.christopherbell.configuration.postgresql;

import static dev.christopherbell.persistence.jooq.platform.Tables.ADMIN_ACTIVITY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import dev.christopherbell.admin.activity.AdminActivityQuery;
import dev.christopherbell.admin.activity.PostgresAdminActivityQueryRepository;
import dev.christopherbell.admin.activity.PostgresAdminActivityRepository;
import dev.christopherbell.admin.model.AdminActivity;
import dev.christopherbell.configuration.persistence.PostgresqlConstraintViolationCause;
import dev.christopherbell.vehicle.core.PostgresVehicleRepository;
import dev.christopherbell.vehicle.model.Vehicle;
import dev.christopherbell.whatsforlunch.restaurant.PostgresRestaurantRepository;
import dev.christopherbell.whatsforlunch.restaurant.model.Address;
import dev.christopherbell.whatsforlunch.restaurant.model.Restaurant;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.jooq.DSLContext;
import org.jooq.ExecuteContext;
import org.jooq.impl.DSL;
import org.jooq.impl.DefaultExecuteListener;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.dao.DuplicateKeyException;

/** PostgreSQL-specific Task 5 query-shape and failure-redaction contracts. */
@EnabledIfEnvironmentVariable(named = "POSTGRESQL_INTEGRATION_TESTS", matches = "enabled")
class PostgresTask5BehaviorContractTest {
  private static final Instant NOW = Instant.parse("2026-08-14T00:00:00Z");
  private static Task3PostgresqlTestSupport schemas;
  private static Task3PostgresqlTestSupport.Database database;

  @BeforeAll
  static void migrateDatabase() throws Exception {
    schemas = Task3PostgresqlTestSupport.migrate();
    database = schemas.openDatabase();
  }

  @AfterAll
  static void cleanupDatabase() throws Exception {
    if (database != null) database.close();
    if (schemas != null) schemas.close();
  }

  @Test
  void adminActivityPageUsesTwoQueriesAtEveryPageSizeAndTheDeclaredOrderingIndex() {
    var activities = new PostgresAdminActivityRepository(database.dsl());
    activities.insert(activity("task5-query-a", "QUERY_TEST"));
    var executions = new AtomicInteger();
    var queries = new PostgresAdminActivityQueryRepository(countedDatabase(executions));

    assertQueryCount(executions, 2,
        () -> queries.query(new AdminActivityQuery(
            "QUERY_TEST", null, null, null, null, 0, 1)));
    assertQueryCount(executions, 2,
        () -> queries.query(new AdminActivityQuery(
            "QUERY_TEST", null, null, null, null, 0, 100)));

    database.dsl().execute("set enable_seqscan = off");
    try {
      assertThat(database.dsl().explain(database.dsl().selectFrom(ADMIN_ACTIVITY)
          .where(ADMIN_ACTIVITY.ACTION.eq("QUERY_TEST"))
          .orderBy(ADMIN_ACTIVITY.CREATED_ON.desc(), ADMIN_ACTIVITY.ADMIN_ACTIVITY_ID.desc())
          .limit(100)).plan())
          .contains("admin_activity__admin_activity_action_created_id_desc");
    } finally {
      database.dsl().execute("reset enable_seqscan");
    }
  }

  @Test
  void uniqueConstraintFailuresExposeOnlyTheirSqlStateCategory() {
    var vehicles = new PostgresVehicleRepository(database.dsl());
    var sensitiveVin = "T5SENSITIVEVIN001";
    vehicles.save(vehicle("task5-redaction-vehicle-a", sensitiveVin));
    assertSafeDuplicate(
        () -> vehicles.save(vehicle("task5-redaction-vehicle-b", sensitiveVin)), sensitiveVin);

    var restaurants = new PostgresRestaurantRepository(database.dsl());
    var sensitiveName = "task5 sensitive normalized name";
    restaurants.save(restaurant("task5-redaction-restaurant-a", sensitiveName));
    assertSafeDuplicate(
        () -> restaurants.save(restaurant("task5-redaction-restaurant-b", sensitiveName)),
        sensitiveName);

    var activities = new PostgresAdminActivityRepository(database.dsl());
    var sensitiveActivityId = "task5-sensitive-activity-id";
    activities.insert(activity(sensitiveActivityId, "REDACTION_TEST"));
    assertSafeDuplicate(
        () -> activities.insert(activity(sensitiveActivityId, "REDACTION_TEST")),
        sensitiveActivityId);
  }

  private static DSLContext countedDatabase(AtomicInteger executions) {
    return DSL.using(database.dsl().configuration().deriveAppending(new DefaultExecuteListener() {
      @Override
      public void executeStart(ExecuteContext context) {
        executions.incrementAndGet();
      }
    }));
  }

  private static void assertQueryCount(
      AtomicInteger executions, int expected, Runnable query) {
    executions.set(0);
    query.run();
    assertThat(executions).hasValue(expected);
  }

  private static void assertSafeDuplicate(Runnable write, String secret) {
    var failure = catchThrowable(write::run);
    assertThat(failure).isInstanceOf(DuplicateKeyException.class)
        .hasMessageNotContaining(secret)
        .hasCauseInstanceOf(PostgresqlConstraintViolationCause.class);
    assertThat(failure.getCause().getMessage()).doesNotContain(secret);
    assertThat(failure.getCause().getCause()).isNull();
  }

  private static Vehicle vehicle(String id, String vin) {
    return Vehicle.builder().id(id).vin(vin).make("Task5").model("Redaction").year(2026)
        .notes("task5").createdOn(NOW).lastUpdatedOn(NOW).build();
  }

  private static Restaurant restaurant(String id, String normalizedName) {
    return Restaurant.builder().id(id).name("Task 5 Redaction")
        .normalizedName(normalizedName).dedupeKey(normalizedName)
        .searchCity("austin").searchState("tx")
        .address(Address.builder().city("Austin").state("TX").country("US")
            .latitude(30.2672).longitude(-97.7431).postalCode("78701")
            .street1("100 Congress Ave").build())
        .createdOn(NOW).lastUpdatedOn(NOW).build();
  }

  private static AdminActivity activity(String id, String action) {
    return AdminActivity.builder().id(id).actorUsername("Task5Actor").action(action)
        .targetType("TASK5").targetId("task5-target").targetLabel("Task 5 Target")
        .reason("contract").message("contract").beforeValues(Map.of("state", "before"))
        .afterValues(Map.of("state", "after")).metadata(Map.of("source", "test"))
        .createdOn(NOW).build();
  }
}
