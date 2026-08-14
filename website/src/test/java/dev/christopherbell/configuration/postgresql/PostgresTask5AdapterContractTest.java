package dev.christopherbell.configuration.postgresql;

import static org.assertj.core.api.Assertions.assertThat;

import dev.christopherbell.configuration.persistence.PostgresPersistence;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Exact Task 5 PostgreSQL adapter inventory. */
class PostgresTask5AdapterContractTest {
  private static final Map<String, String> TASK5_PORT_BY_ADAPTER = Map.ofEntries(
      entry("dev.christopherbell.vehicle.core.PostgresVehicleRepository",
          "dev.christopherbell.vehicle.core.VehicleRepository"),
      entry("dev.christopherbell.vehicle.nhtsa.decode.PostgresVehicleVinDecodeCacheRepository",
          "dev.christopherbell.vehicle.nhtsa.decode.VehicleVinDecodeCacheRepository"),
      entry("dev.christopherbell.vehicle.nhtsa.enrichment.PostgresNhtsaVinImportStateRepository",
          "dev.christopherbell.vehicle.nhtsa.enrichment.NhtsaVinImportStateRepository"),
      entry("dev.christopherbell.vehicle.randomvin.importing.PostgresRandomVinImportStateRepository",
          "dev.christopherbell.vehicle.randomvin.importing.RandomVinImportStateRepository"),
      entry("dev.christopherbell.location.zip.PostgresZipCoordinateRepository",
          "dev.christopherbell.location.zip.ZipCoordinateRepository"),
      entry("dev.christopherbell.location.zip.PostgresZipCoordinateImportStateRepository",
          "dev.christopherbell.location.zip.ZipCoordinateImportStateRepository"),
      entry("dev.christopherbell.whatsforlunch.restaurant.PostgresRestaurantRepository",
          "dev.christopherbell.whatsforlunch.restaurant.RestaurantRepository"),
      entry("dev.christopherbell.whatsforlunch.restaurant.PostgresDailyLunchPicksRepository",
          "dev.christopherbell.whatsforlunch.restaurant.DailyLunchPicksRepository"),
      entry("dev.christopherbell.whatsforlunch.restaurant.PostgresRestaurantImportStateRepository",
          "dev.christopherbell.whatsforlunch.restaurant.RestaurantImportStateRepository"),
      entry(
          "dev.christopherbell.whatsforlunch.restaurant.importing.PostgresRestaurantImportPreviewStore",
          "dev.christopherbell.whatsforlunch.restaurant.importing.RestaurantImportPreviewPort"),
      entry(
          "dev.christopherbell.whatsforlunch.restaurant.favorite.PostgresRestaurantFavoriteRepository",
          "dev.christopherbell.whatsforlunch.restaurant.favorite.RestaurantFavoriteRepository"),
      entry(
          "dev.christopherbell.whatsforlunch.restaurant.preference.PostgresWhatsForLunchPreferenceRepository",
          "dev.christopherbell.whatsforlunch.restaurant.preference.WhatsForLunchPreferenceRepository"),
      entry(
          "dev.christopherbell.whatsforlunch.restaurant.session.PostgresWhatsForLunchSessionRepository",
          "dev.christopherbell.whatsforlunch.restaurant.session.WhatsForLunchSessionRepository"),
      entry(
          "dev.christopherbell.whatsforlunch.restaurant.session.PostgresWhatsForLunchSessionMutationStore",
          "dev.christopherbell.whatsforlunch.restaurant.session.WhatsForLunchSessionMutationPort"),
      entry(
          "dev.christopherbell.whatsforlunch.restaurant.vote.PostgresRestaurantVoteRepository",
          "dev.christopherbell.whatsforlunch.restaurant.vote.RestaurantVoteRepository"),
      entry(
          "dev.christopherbell.whatsforlunch.restaurant.vote.PostgresRestaurantVoteQueryRepository",
          "dev.christopherbell.whatsforlunch.restaurant.vote.RestaurantVoteQueryPort"),
      entry(
          "dev.christopherbell.whatsforlunch.restaurant.PostgresRestaurantInventoryQueryRepository",
          "dev.christopherbell.whatsforlunch.restaurant.RestaurantInventoryQueryPort"),
      entry(
          "dev.christopherbell.whatsforlunch.restaurant.PostgresRestaurantDuplicateQueryRepository",
          "dev.christopherbell.whatsforlunch.restaurant.RestaurantDuplicateQueryPort"),
      entry("dev.christopherbell.canesboxtracker.PostgresCanesBoxPriceSnapshotRepository",
          "dev.christopherbell.canesboxtracker.CanesBoxPriceSnapshotRepository"),
      entry("dev.christopherbell.admin.activity.PostgresAdminActivityRepository",
          "dev.christopherbell.admin.activity.AdminActivityRepository"),
      entry("dev.christopherbell.admin.activity.PostgresAdminActivityQueryRepository",
          "dev.christopherbell.admin.activity.AdminActivityQueryPort"),
      entry("dev.christopherbell.admin.commandcenter.action.PostgresPendingActionStore",
          "dev.christopherbell.admin.commandcenter.action.PendingActionStore"),
      entry("dev.christopherbell.configuration.persistence.PostgresScheduledCollectorRunStore",
          "dev.christopherbell.libs.lease.ScheduledCollectorRunStore"),
      entry("dev.christopherbell.admin.commandcenter.metrics.PostgresDatabaseConnectivityProbe",
          "dev.christopherbell.admin.commandcenter.metrics.DatabaseConnectivityProbe"));

  @Test
  void everyRemainingRuntimePortHasExactlyOneSelectedPostgresqlAdapter() {
    assertThat(TASK5_PORT_BY_ADAPTER).hasSize(24);
    assertThat(TASK5_PORT_BY_ADAPTER.values()).doesNotHaveDuplicates();
    TASK5_PORT_BY_ADAPTER.forEach((adapterName, portName) -> {
      try {
        var adapter = Class.forName(adapterName);
        var port = Class.forName(portName);
        assertThat(adapter.isAnnotationPresent(PostgresPersistence.class))
            .as(adapterName)
            .isTrue();
        assertThat(port.isAssignableFrom(adapter))
            .as("%s implements %s", adapterName, portName)
            .isTrue();
      } catch (ClassNotFoundException missing) {
        throw new AssertionError("Missing Task 5 adapter or port.", missing);
      }
    });
  }

  private static Map.Entry<String, String> entry(String adapter, String port) {
    return Map.entry(adapter, port);
  }
}
