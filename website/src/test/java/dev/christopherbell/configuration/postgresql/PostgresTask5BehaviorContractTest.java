package dev.christopherbell.configuration.postgresql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import dev.christopherbell.account.PostgresAccountRepository;
import dev.christopherbell.account.model.Account;
import dev.christopherbell.account.model.AccountStatus;
import dev.christopherbell.account.model.Role;
import dev.christopherbell.admin.activity.AdminActivityQuery;
import dev.christopherbell.admin.activity.PostgresAdminActivityQueryRepository;
import dev.christopherbell.admin.activity.PostgresAdminActivityRepository;
import dev.christopherbell.admin.model.AdminActivity;
import dev.christopherbell.canesboxtracker.PostgresCanesBoxPriceSnapshotRepository;
import dev.christopherbell.canesboxtracker.model.CanesBoxPriceSnapshot;
import dev.christopherbell.configuration.persistence.PostgresqlConstraintViolationCause;
import dev.christopherbell.location.model.ZipCoordinate;
import dev.christopherbell.location.zip.PostgresZipCoordinateRepository;
import dev.christopherbell.vehicle.core.PostgresVehicleRepository;
import dev.christopherbell.vehicle.model.Vehicle;
import dev.christopherbell.whatsforlunch.restaurant.PostgresRestaurantRepository;
import dev.christopherbell.whatsforlunch.restaurant.RestaurantService;
import dev.christopherbell.whatsforlunch.restaurant.config.WflProperties;
import dev.christopherbell.whatsforlunch.restaurant.favorite.PostgresRestaurantFavoriteRepository;
import dev.christopherbell.whatsforlunch.restaurant.importing.RestaurantImportLeaseGuard;
import dev.christopherbell.whatsforlunch.restaurant.importing.RestaurantImportPreviewCounts;
import dev.christopherbell.whatsforlunch.restaurant.importing.RestaurantImportSnapshot;
import dev.christopherbell.whatsforlunch.restaurant.model.Address;
import dev.christopherbell.whatsforlunch.restaurant.model.Restaurant;
import dev.christopherbell.whatsforlunch.restaurant.model.RestaurantFavorite;
import dev.christopherbell.whatsforlunch.restaurant.model.RestaurantVote;
import dev.christopherbell.whatsforlunch.restaurant.model.RestaurantVoteValue;
import dev.christopherbell.whatsforlunch.restaurant.vote.PostgresRestaurantVoteRepository;
import java.time.Clock;
import java.time.Instant;
import java.math.BigDecimal;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.AbstractDataSource;

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
    var activities = new PostgresAdminActivityRepository(
        database.managedJdbc(), database.schemas(), database.transactions());
    activities.insert(activity("task5-query-a", "QUERY_TEST"));
    var executions = new AtomicInteger();
    var queries = new PostgresAdminActivityQueryRepository(
        countedJdbc(executions), database.schemas());

    assertQueryCount(executions, 2,
        () -> queries.query(new AdminActivityQuery(
            "QUERY_TEST", null, null, null, null, 0, 1)));
    assertQueryCount(executions, 2,
        () -> queries.query(new AdminActivityQuery(
            "QUERY_TEST", null, null, null, null, 0, 100)));

    database.jdbc().sql("set enable_seqscan = off").update();
    try {
      var table = database.schemas().qualifiedTable("platform", "admin_activity");
      var plan = String.join("\n", database.jdbc().sql("explain select * from " + table
          + " where action = 'QUERY_TEST' order by created_on desc, admin_activity_id desc"
          + " limit 100").query(String.class).list());
      assertThat(plan)
          .contains("admin_activity__admin_activity_action_created_id_desc");
    } finally {
      database.jdbc().sql("reset enable_seqscan").update();
    }
  }

  @Test
  void canesHistoryLoadsEverySnapshotAndMetroListInTwoQueries() {
    var snapshots = new PostgresCanesBoxPriceSnapshotRepository(
        database.managedJdbc(), database.schemas(), database.transactions());
    snapshots.save(canesSnapshot("task5-canes-query-a", "2026-08-03", "10.01"));
    snapshots.save(canesSnapshot("task5-canes-query-b", "2026-08-10", "10.02"));
    var executions = new AtomicInteger();
    var counted = new PostgresCanesBoxPriceSnapshotRepository(
        countedJdbc(executions), database.schemas(), database.transactions());

    executions.set(0);
    assertThat(counted.findTop60ByOrderByWeekStartDateDesc())
        .extracting(CanesBoxPriceSnapshot::getId)
        .containsSubsequence("task5-canes-query-b", "task5-canes-query-a");
    assertThat(executions).hasValue(2);
  }

  @Test
  void uniqueConstraintFailuresExposeOnlyTheirSqlStateCategory() {
    var vehicles = new PostgresVehicleRepository(
        database.managedJdbc(), database.schemas(), database.transactions());
    var sensitiveVin = "T5SENSITIVEVIN001";
    vehicles.save(vehicle("task5-redaction-vehicle-a", sensitiveVin));
    assertSafeDuplicate(
        () -> vehicles.save(vehicle("task5-redaction-vehicle-b", sensitiveVin)), sensitiveVin);

    var restaurants = new PostgresRestaurantRepository(
        database.managedJdbc(), database.schemas(), database.transactions());
    var sensitiveName = "task5 sensitive normalized name";
    restaurants.save(restaurant("task5-redaction-restaurant-a", sensitiveName));
    assertSafeDuplicate(
        () -> restaurants.save(restaurant("task5-redaction-restaurant-b", sensitiveName)),
        sensitiveName);

    var activities = new PostgresAdminActivityRepository(
        database.managedJdbc(), database.schemas(), database.transactions());
    var sensitiveActivityId = "task5-sensitive-activity-id";
    activities.insert(activity(sensitiveActivityId, "REDACTION_TEST"));
    assertSafeDuplicate(
        () -> activities.insert(activity(sensitiveActivityId, "REDACTION_TEST")),
        sensitiveActivityId);
  }

  @Test
  void vehiclePreservesHistoricalModifierWithoutLiveAccount() {
    var vehicles = new PostgresVehicleRepository(
        database.managedJdbc(), database.schemas(), database.transactions());
    var historical = Vehicle.builder().id("task8-historical-vehicle")
        .vin("TASK8HISTORYVIN02").make("Task8").model("History").year(2026)
        .lastModifiedBy("task8-retired-account").createdOn(NOW).lastUpdatedOn(NOW).build();

    vehicles.save(historical);

    assertThat(vehicles.findById(historical.getId()))
        .get()
        .extracting(Vehicle::getLastModifiedBy)
        .isEqualTo("task8-retired-account");
  }

  @Test
  void zipCoordinatePreservesMissingCreationTimestamp() {
    var coordinates = new PostgresZipCoordinateRepository(database.jdbc(), database.schemas());
    var legacy = ZipCoordinate.builder().zipCode("78702").latitude(30.2638)
        .longitude(-97.7169).source("TASK8").sourceYear(2026)
        .createdOn(null).lastUpdatedOn(NOW).build();

    coordinates.saveAll(List.of(legacy));

    assertThat(coordinates.findById(legacy.getZipCode()))
        .get()
        .extracting(ZipCoordinate::getCreatedOn, ZipCoordinate::getLastUpdatedOn)
        .containsExactly(null, NOW);
  }

  @Test
  void favoriteAndVoteClassifyOnlyUniqueViolationsAsDuplicates() {
    var ownerId = "task5-integrity-owner";
    new PostgresAccountRepository(
        database.managedJdbc(), database.schemas(), database.transactions()).save(Account.builder()
        .id(ownerId).username(ownerId).email(ownerId + "@example.test").passwordHash("hash")
        .role(Role.USER).status(AccountStatus.ACTIVE).createdOn(NOW).build());
    var restaurantId = "task5-integrity-restaurant";
    new PostgresRestaurantRepository(
        database.managedJdbc(), database.schemas(), database.transactions())
        .save(restaurant(restaurantId, restaurantId));

    var favorites = new PostgresRestaurantFavoriteRepository(database.jdbc(), database.schemas());
    favorites.save(RestaurantFavorite.builder().id("task5-integrity-favorite-a")
        .accountId(ownerId).restaurantId(restaurantId).createdOn(NOW).build());
    assertSafeDuplicate(() -> favorites.save(RestaurantFavorite.builder()
        .id("task5-integrity-favorite-b").accountId(ownerId).restaurantId(restaurantId)
        .createdOn(NOW).build()), restaurantId);
    assertSafeIntegrity(() -> favorites.save(RestaurantFavorite.builder()
        .id("task5-integrity-favorite-fk").accountId("task5-missing-account")
        .restaurantId(restaurantId).createdOn(NOW).build()), "task5-missing-account", "23503");

    var votes = new PostgresRestaurantVoteRepository(database.jdbc(), database.schemas());
    votes.save(RestaurantVote.builder().id("task5-integrity-vote-a").accountId(ownerId)
        .restaurantId(restaurantId).vote(RestaurantVoteValue.UP)
        .createdOn(NOW).lastUpdatedOn(NOW).build());
    assertSafeDuplicate(() -> votes.save(RestaurantVote.builder()
        .id("task5-integrity-vote-b").accountId(ownerId).restaurantId(restaurantId)
        .vote(RestaurantVoteValue.DOWN).createdOn(NOW).lastUpdatedOn(NOW).build()), restaurantId);
    assertSafeIntegrity(() -> votes.save(RestaurantVote.builder()
        .id("task5-integrity-vote-fk").accountId(ownerId)
        .restaurantId("task5-missing-restaurant").vote(RestaurantVoteValue.UP)
        .createdOn(NOW).lastUpdatedOn(NOW).build()), "task5-missing-restaurant", "23503");
  }

  @Test
  void realImportContinuesAfterConstraintCollisionToPersistLaterCandidate() throws Exception {
    var owner = restaurant("task5-import-owner", "task5 import collision");
    var stored = new PostgresRestaurantRepository(
        database.managedJdbc(), database.schemas(), database.transactions());
    stored.save(owner);
    var importRepository = new PostgresRestaurantRepository(
        database.managedJdbc(), database.schemas(), database.transactions()) {
      @Override
      public java.util.Optional<Restaurant> findByNormalizedName(String normalizedName) {
        return normalizedName.equals(owner.getNormalizedName())
            ? java.util.Optional.empty()
            : super.findByNormalizedName(normalizedName);
      }
    };
    var service = new RestaurantService(
        Clock.systemUTC(), null, null, null, null, null, null, null, null, null, null,
        importRepository, null, null, null, new WflProperties());
    var collision = restaurant("task5-import-collision", owner.getNormalizedName());
    collision.setName("Task5 Import Collision");
    var later = restaurant("task5-import-later", "task5 import later");
    later.setName("Task5 Import Later");
    var snapshot = new RestaurantImportSnapshot(
        "task5-import-checksum", List.of(collision, later),
        new RestaurantImportPreviewCounts(2, 2, 0, 0, 0, 0), List.of());

    var result = service.applyPreparedImport(snapshot, RestaurantImportLeaseGuard.NONE);

    assertThat(result.imported()).isEqualTo(1);
    assertThat(result.skippedExisting()).isEqualTo(1);
    assertThat(stored.findById(later.getId())).isPresent();
    assertThat(stored.findByNormalizedName(owner.getNormalizedName()))
        .get().extracting(Restaurant::getId).isEqualTo(owner.getId());
  }

  private static JdbcClient countedJdbc(AtomicInteger executions) {
    return JdbcClient.create(new AbstractDataSource() {
      @Override
      public Connection getConnection() throws SQLException {
        return countingConnection(database.dataSource().getConnection(), executions);
      }

      @Override
      public Connection getConnection(String username, String password) throws SQLException {
        return countingConnection(
            database.dataSource().getConnection(username, password), executions);
      }
    });
  }

  private static Connection countingConnection(Connection delegate, AtomicInteger executions) {
    return (Connection) Proxy.newProxyInstance(
        Connection.class.getClassLoader(), new Class<?>[] {Connection.class},
        (proxy, method, arguments) -> {
          if (method.getName().startsWith("prepareStatement")) executions.incrementAndGet();
          try {
            return method.invoke(delegate, arguments);
          } catch (InvocationTargetException failure) {
            throw failure.getCause();
          }
        });
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
    assertThat(((PostgresqlConstraintViolationCause) failure.getCause()).sqlState())
        .isEqualTo("23505");
  }

  private static void assertSafeIntegrity(Runnable write, String secret, String sqlState) {
    var failure = catchThrowable(write::run);
    assertThat(failure).isInstanceOf(DataIntegrityViolationException.class)
        .isNotInstanceOf(DuplicateKeyException.class)
        .hasMessageNotContaining(secret)
        .hasCauseInstanceOf(PostgresqlConstraintViolationCause.class);
    assertThat(failure.getCause().getMessage()).doesNotContain(secret);
    assertThat(failure.getCause().getCause()).isNull();
    assertThat(((PostgresqlConstraintViolationCause) failure.getCause()).sqlState())
        .isEqualTo(sqlState);
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

  private static CanesBoxPriceSnapshot canesSnapshot(
      String id, String weekStartDate, String averagePrice) {
    var snapshot = new CanesBoxPriceSnapshot();
    snapshot.setId(id);
    snapshot.setWeekStartDate(weekStartDate);
    snapshot.setCollectedOn(NOW);
    snapshot.setAveragePrice(new BigDecimal(averagePrice));
    snapshot.setCurrency("USD");
    snapshot.setSuccessfulMetroCount(0);
    snapshot.setTotalMetroCount(0);
    snapshot.setVerifiedMetroCount(0);
    snapshot.setProvisionalMetroCount(0);
    snapshot.setExcludedMetroCount(0);
    snapshot.setMetroPrices(List.of());
    return snapshot;
  }
}
