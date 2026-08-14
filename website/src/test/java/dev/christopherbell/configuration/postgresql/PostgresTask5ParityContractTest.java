package dev.christopherbell.configuration.postgresql;

import dev.christopherbell.account.PostgresAccountRepository;
import dev.christopherbell.account.model.Account;
import dev.christopherbell.account.model.AccountStatus;
import dev.christopherbell.account.model.Role;
import dev.christopherbell.admin.activity.AdminActivityQueryPort;
import dev.christopherbell.admin.activity.AdminActivityRepository;
import dev.christopherbell.admin.activity.PostgresAdminActivityQueryRepository;
import dev.christopherbell.admin.activity.PostgresAdminActivityRepository;
import dev.christopherbell.admin.commandcenter.action.PendingActionStore;
import dev.christopherbell.admin.commandcenter.action.PostgresPendingActionStore;
import dev.christopherbell.admin.commandcenter.metrics.DatabaseConnectivityProbe;
import dev.christopherbell.admin.commandcenter.metrics.PostgresDatabaseConnectivityProbe;
import dev.christopherbell.canesboxtracker.CanesBoxPriceSnapshotRepository;
import dev.christopherbell.canesboxtracker.PostgresCanesBoxPriceSnapshotRepository;
import dev.christopherbell.configuration.persistence.PostgresScheduledCollectorRunStore;
import dev.christopherbell.libs.lease.ScheduledCollectorRunStore;
import dev.christopherbell.location.zip.PostgresZipCoordinateImportStateRepository;
import dev.christopherbell.location.zip.PostgresZipCoordinateRepository;
import dev.christopherbell.location.zip.ZipCoordinateImportStateRepository;
import dev.christopherbell.location.zip.ZipCoordinateRepository;
import dev.christopherbell.vehicle.core.PostgresVehicleRepository;
import dev.christopherbell.vehicle.core.VehicleRepository;
import dev.christopherbell.vehicle.nhtsa.decode.PostgresVehicleVinDecodeCacheRepository;
import dev.christopherbell.vehicle.nhtsa.decode.VehicleVinDecodeCacheRepository;
import dev.christopherbell.vehicle.nhtsa.enrichment.NhtsaVinImportStateRepository;
import dev.christopherbell.vehicle.nhtsa.enrichment.PostgresNhtsaVinImportStateRepository;
import dev.christopherbell.vehicle.randomvin.importing.PostgresRandomVinImportStateRepository;
import dev.christopherbell.vehicle.randomvin.importing.RandomVinImportStateRepository;
import dev.christopherbell.whatsforlunch.restaurant.DailyLunchPicksRepository;
import dev.christopherbell.whatsforlunch.restaurant.PostgresDailyLunchPicksRepository;
import dev.christopherbell.whatsforlunch.restaurant.PostgresRestaurantDuplicateQueryRepository;
import dev.christopherbell.whatsforlunch.restaurant.PostgresRestaurantImportStateRepository;
import dev.christopherbell.whatsforlunch.restaurant.PostgresRestaurantInventoryQueryRepository;
import dev.christopherbell.whatsforlunch.restaurant.PostgresRestaurantRepository;
import dev.christopherbell.whatsforlunch.restaurant.RestaurantDuplicateQueryPort;
import dev.christopherbell.whatsforlunch.restaurant.RestaurantImportStateRepository;
import dev.christopherbell.whatsforlunch.restaurant.RestaurantInventoryQueryPort;
import dev.christopherbell.whatsforlunch.restaurant.RestaurantRepository;
import dev.christopherbell.whatsforlunch.restaurant.favorite.PostgresRestaurantFavoriteRepository;
import dev.christopherbell.whatsforlunch.restaurant.favorite.RestaurantFavoriteRepository;
import dev.christopherbell.whatsforlunch.restaurant.importing.PostgresRestaurantImportPreviewStore;
import dev.christopherbell.whatsforlunch.restaurant.importing.RestaurantImportPreviewPort;
import dev.christopherbell.whatsforlunch.restaurant.preference.PostgresWhatsForLunchPreferenceRepository;
import dev.christopherbell.whatsforlunch.restaurant.preference.WhatsForLunchPreferenceRepository;
import dev.christopherbell.whatsforlunch.restaurant.session.PostgresWhatsForLunchSessionMutationStore;
import dev.christopherbell.whatsforlunch.restaurant.session.PostgresWhatsForLunchSessionRepository;
import dev.christopherbell.whatsforlunch.restaurant.session.WhatsForLunchSessionMutationPort;
import dev.christopherbell.whatsforlunch.restaurant.session.WhatsForLunchSessionRepository;
import dev.christopherbell.whatsforlunch.restaurant.vote.PostgresRestaurantVoteQueryRepository;
import dev.christopherbell.whatsforlunch.restaurant.vote.PostgresRestaurantVoteRepository;
import dev.christopherbell.whatsforlunch.restaurant.vote.RestaurantVoteQueryPort;
import dev.christopherbell.whatsforlunch.restaurant.vote.RestaurantVoteRepository;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** PostgreSQL runner for the identical Task 5 persistence contract. */
@EnabledIfEnvironmentVariable(named = "POSTGRESQL_INTEGRATION_TESTS", matches = "enabled")
class PostgresTask5ParityContractTest implements Task5PersistenceParityContract {
  private static Task3PostgresqlTestSupport schemas;
  private static Task3PostgresqlTestSupport.Database database;
  private static Task3PostgresqlTestSupport.Database contender;

  @BeforeAll
  static void migrateDatabase() throws Exception {
    schemas = Task3PostgresqlTestSupport.migrate();
    database = schemas.openDatabase();
    contender = schemas.openDatabase();
    var accounts = new PostgresAccountRepository(database.dsl());
    accounts.save(account(OWNER_ID, "task5-owner", "task5-owner@example.test"));
    accounts.save(account(MEMBER_ID, "task5-member", "task5-member@example.test"));
  }

  @AfterAll
  static void cleanupDatabase() throws Exception {
    if (contender != null) contender.close();
    if (database != null) database.close();
    if (schemas != null) schemas.close();
  }

  @Override public VehicleRepository vehicles() { return new PostgresVehicleRepository(database.dsl()); }
  @Override public VehicleRepository vehicleContender() { return new PostgresVehicleRepository(contender.dsl()); }
  @Override public VehicleVinDecodeCacheRepository vinCache() { return new PostgresVehicleVinDecodeCacheRepository(database.dsl()); }
  @Override public NhtsaVinImportStateRepository nhtsaState() { return new PostgresNhtsaVinImportStateRepository(database.dsl()); }
  @Override public RandomVinImportStateRepository randomVinState() { return new PostgresRandomVinImportStateRepository(database.dsl()); }
  @Override public ZipCoordinateRepository zipCoordinates() { return new PostgresZipCoordinateRepository(database.dsl()); }
  @Override public ZipCoordinateImportStateRepository zipImportState() { return new PostgresZipCoordinateImportStateRepository(database.dsl()); }
  @Override public RestaurantRepository restaurants() { return new PostgresRestaurantRepository(database.dsl()); }
  @Override public RestaurantRepository restaurantContender() { return new PostgresRestaurantRepository(contender.dsl()); }
  @Override public DailyLunchPicksRepository dailyPicks() { return new PostgresDailyLunchPicksRepository(database.dsl()); }
  @Override public RestaurantImportStateRepository restaurantImportState() { return new PostgresRestaurantImportStateRepository(database.dsl()); }
  @Override public RestaurantImportPreviewPort importPreviews() { return new PostgresRestaurantImportPreviewStore(database.dsl()); }
  @Override public RestaurantFavoriteRepository favorites() { return new PostgresRestaurantFavoriteRepository(database.dsl()); }
  @Override public WhatsForLunchPreferenceRepository preferences() { return new PostgresWhatsForLunchPreferenceRepository(database.dsl()); }
  @Override public WhatsForLunchSessionRepository sessions() { return new PostgresWhatsForLunchSessionRepository(database.dsl()); }
  @Override public WhatsForLunchSessionMutationPort sessionMutations() { return new PostgresWhatsForLunchSessionMutationStore(database.dsl()); }
  @Override public RestaurantVoteRepository votes() { return new PostgresRestaurantVoteRepository(database.dsl()); }
  @Override public RestaurantVoteQueryPort voteQueries() { return new PostgresRestaurantVoteQueryRepository(database.dsl()); }
  @Override public RestaurantInventoryQueryPort inventoryQueries() { return new PostgresRestaurantInventoryQueryRepository(database.dsl()); }
  @Override public RestaurantDuplicateQueryPort duplicateQueries() { return new PostgresRestaurantDuplicateQueryRepository(database.dsl()); }
  @Override public CanesBoxPriceSnapshotRepository canesSnapshots() { return new PostgresCanesBoxPriceSnapshotRepository(database.dsl()); }
  @Override public AdminActivityRepository adminActivities() { return new PostgresAdminActivityRepository(database.dsl()); }
  @Override public AdminActivityQueryPort adminActivityQueries() { return new PostgresAdminActivityQueryRepository(database.dsl()); }
  @Override public PendingActionStore pendingActions() { return new PostgresPendingActionStore(database.dsl()); }
  @Override public PendingActionStore pendingActionContender() { return new PostgresPendingActionStore(contender.dsl()); }
  @Override public ScheduledCollectorRunStore scheduledRuns() { return new PostgresScheduledCollectorRunStore(database.dsl()); }
  @Override public DatabaseConnectivityProbe databaseProbe() { return new PostgresDatabaseConnectivityProbe(database.dsl()); }

  private static Account account(String id, String username, String email) {
    return Account.builder().id(id).username(username).email(email).passwordHash("hash")
        .role(Role.USER).status(AccountStatus.ACTIVE).createdOn(NOW).build();
  }
}
