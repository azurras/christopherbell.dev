package dev.christopherbell.configuration.postgresql;

import com.mongodb.ConnectionString;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.model.IndexOptions;
import dev.christopherbell.admin.activity.AdminActivityQueryPort;
import dev.christopherbell.admin.activity.AdminActivityRepository;
import dev.christopherbell.admin.activity.MongoAdminActivityQueryRepository;
import dev.christopherbell.admin.activity.MongoAdminActivityRepository;
import dev.christopherbell.admin.commandcenter.action.MongoPendingActionStore;
import dev.christopherbell.admin.commandcenter.action.PendingActionStore;
import dev.christopherbell.admin.commandcenter.metrics.DatabaseConnectivityProbe;
import dev.christopherbell.admin.commandcenter.metrics.MongoDatabaseConnectivityProbe;
import dev.christopherbell.canesboxtracker.CanesBoxPriceSnapshotRepository;
import dev.christopherbell.canesboxtracker.MongoCanesBoxPriceSnapshotRepository;
import dev.christopherbell.configuration.mongo.domain.DomainCollectionManifest;
import dev.christopherbell.configuration.mongo.domain.DomainMongoOperationsFactory;
import dev.christopherbell.configuration.mongo.domain.DomainMongoOperationsTestFactory;
import dev.christopherbell.configuration.mongo.runtime.MongoScheduledCollectorRunStore;
import dev.christopherbell.libs.lease.ScheduledCollectorRunStore;
import dev.christopherbell.location.zip.MongoZipCoordinateImportStateRepository;
import dev.christopherbell.location.zip.MongoZipCoordinateRepository;
import dev.christopherbell.location.zip.ZipCoordinateImportStateRepository;
import dev.christopherbell.location.zip.ZipCoordinateRepository;
import dev.christopherbell.vehicle.core.MongoVehicleRepository;
import dev.christopherbell.vehicle.core.VehicleRepository;
import dev.christopherbell.vehicle.nhtsa.decode.MongoVehicleVinDecodeCacheRepository;
import dev.christopherbell.vehicle.nhtsa.decode.VehicleVinDecodeCacheRepository;
import dev.christopherbell.vehicle.nhtsa.enrichment.MongoNhtsaVinImportStateRepository;
import dev.christopherbell.vehicle.nhtsa.enrichment.NhtsaVinImportStateRepository;
import dev.christopherbell.vehicle.randomvin.importing.MongoRandomVinImportStateRepository;
import dev.christopherbell.vehicle.randomvin.importing.RandomVinImportStateRepository;
import dev.christopherbell.whatsforlunch.restaurant.DailyLunchPicksRepository;
import dev.christopherbell.whatsforlunch.restaurant.MongoDailyLunchPicksRepository;
import dev.christopherbell.whatsforlunch.restaurant.MongoRestaurantImportStateRepository;
import dev.christopherbell.whatsforlunch.restaurant.MongoRestaurantRepository;
import dev.christopherbell.whatsforlunch.restaurant.RestaurantDuplicateQueryPort;
import dev.christopherbell.whatsforlunch.restaurant.RestaurantDuplicateQueryRepository;
import dev.christopherbell.whatsforlunch.restaurant.RestaurantImportStateRepository;
import dev.christopherbell.whatsforlunch.restaurant.RestaurantInventoryQueryPort;
import dev.christopherbell.whatsforlunch.restaurant.RestaurantInventoryQueryRepository;
import dev.christopherbell.whatsforlunch.restaurant.RestaurantRepository;
import dev.christopherbell.whatsforlunch.restaurant.favorite.MongoRestaurantFavoriteRepository;
import dev.christopherbell.whatsforlunch.restaurant.favorite.RestaurantFavoriteRepository;
import dev.christopherbell.whatsforlunch.restaurant.importing.RestaurantImportPreviewPort;
import dev.christopherbell.whatsforlunch.restaurant.importing.RestaurantImportPreviewStore;
import dev.christopherbell.whatsforlunch.restaurant.preference.MongoWhatsForLunchPreferenceRepository;
import dev.christopherbell.whatsforlunch.restaurant.preference.WhatsForLunchPreferenceRepository;
import dev.christopherbell.whatsforlunch.restaurant.session.MongoWhatsForLunchSessionRepository;
import dev.christopherbell.whatsforlunch.restaurant.session.WhatsForLunchSessionMutationPort;
import dev.christopherbell.whatsforlunch.restaurant.session.WhatsForLunchSessionMutationStore;
import dev.christopherbell.whatsforlunch.restaurant.session.WhatsForLunchSessionRepository;
import dev.christopherbell.whatsforlunch.restaurant.vote.MongoRestaurantVoteRepository;
import dev.christopherbell.whatsforlunch.restaurant.vote.RestaurantVoteQueryPort;
import dev.christopherbell.whatsforlunch.restaurant.vote.RestaurantVoteQueryRepository;
import dev.christopherbell.whatsforlunch.restaurant.vote.RestaurantVoteRepository;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.bson.Document;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.data.mongodb.core.MongoTemplate;

/** MongoDB runner for the identical Task 5 persistence contract. */
@EnabledIfEnvironmentVariable(named = "MONGODB_INTEGRATION_TESTS", matches = "enabled")
class MongoTask5ParityContractTest implements Task5PersistenceParityContract {
  private static final List<String> COLLECTIONS = List.of(
      "vehicles", "location", "whatsforlunch", "canes_box_tracker", "application_runtime",
      "admin_activity");
  private static MongoClient client;
  private static MongoClient contenderClient;
  private static MongoTemplate mongo;
  private static DomainMongoOperationsFactory factory;
  private static DomainMongoOperationsFactory contenderFactory;

  @BeforeAll
  static void connectToDisposableMongo() {
    var uri = System.getenv("SPRING_MONGODB_URI");
    var connection = new ConnectionString(uri);
    if (!"test".equals(connection.getDatabase())
        || connection.getHosts().stream().anyMatch(host -> host.endsWith(":27017"))) {
      throw new IllegalStateException(
          "Task 5 Mongo contracts require disposable non-27017 database test.");
    }
    client = MongoClients.create(connection);
    contenderClient = MongoClients.create(connection);
    mongo = new MongoTemplate(client, "test");
    COLLECTIONS.forEach(collection -> mongo.getCollection(collection).deleteMany(new Document()));
    installManifestIndexes();
    factory = DomainMongoOperationsTestFactory.createForDisposableMongo(mongo);
    contenderFactory = DomainMongoOperationsTestFactory.createForDisposableMongo(
        new MongoTemplate(contenderClient, "test"));
  }

  @AfterAll
  static void disconnect() {
    if (contenderClient != null) contenderClient.close();
    if (client != null) client.close();
  }

  @Override public VehicleRepository vehicles() { return new MongoVehicleRepository(factory); }
  @Override public VehicleRepository vehicleContender() { return new MongoVehicleRepository(contenderFactory); }
  @Override public VehicleVinDecodeCacheRepository vinCache() { return new MongoVehicleVinDecodeCacheRepository(factory); }
  @Override public NhtsaVinImportStateRepository nhtsaState() { return new MongoNhtsaVinImportStateRepository(factory); }
  @Override public RandomVinImportStateRepository randomVinState() { return new MongoRandomVinImportStateRepository(factory); }
  @Override public ZipCoordinateRepository zipCoordinates() { return new MongoZipCoordinateRepository(factory); }
  @Override public ZipCoordinateImportStateRepository zipImportState() { return new MongoZipCoordinateImportStateRepository(factory); }
  @Override public RestaurantRepository restaurants() { return new MongoRestaurantRepository(factory); }
  @Override public RestaurantRepository restaurantContender() { return new MongoRestaurantRepository(contenderFactory); }
  @Override public DailyLunchPicksRepository dailyPicks() { return new MongoDailyLunchPicksRepository(factory); }
  @Override public RestaurantImportStateRepository restaurantImportState() { return new MongoRestaurantImportStateRepository(factory); }
  @Override public RestaurantImportPreviewPort importPreviews() { return new RestaurantImportPreviewStore(factory); }
  @Override public RestaurantFavoriteRepository favorites() { return new MongoRestaurantFavoriteRepository(factory); }
  @Override public WhatsForLunchPreferenceRepository preferences() { return new MongoWhatsForLunchPreferenceRepository(factory); }
  @Override public WhatsForLunchSessionRepository sessions() { return new MongoWhatsForLunchSessionRepository(factory); }
  @Override public WhatsForLunchSessionMutationPort sessionMutations() { return new WhatsForLunchSessionMutationStore(factory, sessions()); }
  @Override public RestaurantVoteRepository votes() { return new MongoRestaurantVoteRepository(factory); }
  @Override public RestaurantVoteQueryPort voteQueries() { return new RestaurantVoteQueryRepository(factory); }
  @Override public RestaurantInventoryQueryPort inventoryQueries() { return new RestaurantInventoryQueryRepository(factory); }
  @Override public RestaurantDuplicateQueryPort duplicateQueries() { return new RestaurantDuplicateQueryRepository(factory); }
  @Override public CanesBoxPriceSnapshotRepository canesSnapshots() { return new MongoCanesBoxPriceSnapshotRepository(factory); }
  @Override public AdminActivityRepository adminActivities() { return new MongoAdminActivityRepository(factory); }
  @Override public AdminActivityQueryPort adminActivityQueries() { return new MongoAdminActivityQueryRepository(factory); }
  @Override public PendingActionStore pendingActions() { return new MongoPendingActionStore(factory); }
  @Override public PendingActionStore pendingActionContender() { return new MongoPendingActionStore(contenderFactory); }
  @Override public ScheduledCollectorRunStore scheduledRuns() { return new MongoScheduledCollectorRunStore(factory); }
  @Override public DatabaseConnectivityProbe databaseProbe() { return new MongoDatabaseConnectivityProbe(mongo); }

  private static void installManifestIndexes() {
    DomainCollectionManifest.ALL_INDEXES.stream()
        .filter(index -> COLLECTIONS.contains(index.collection()))
        .filter(index -> !"_id_".equals(index.name()))
        .forEach(index -> {
          var keys = new Document();
          index.keys().forEach(key -> keys.append(key.path(), key.direction()));
          var options = new IndexOptions().name(index.name()).unique(index.unique()).sparse(index.sparse());
          if (!index.partialFilterExpression().isEmpty()) {
            options.partialFilterExpression(new Document(index.partialFilterExpression()));
          }
          index.expireAfterSeconds().ifPresent(seconds -> options.expireAfter(seconds, TimeUnit.SECONDS));
          mongo.getCollection(index.collection()).createIndex(keys, options);
        });
  }
}
