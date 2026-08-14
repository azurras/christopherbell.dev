package dev.christopherbell.configuration.postgresql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.christopherbell.admin.activity.AdminActivityQuery;
import dev.christopherbell.admin.activity.AdminActivityQueryPort;
import dev.christopherbell.admin.activity.AdminActivityRepository;
import dev.christopherbell.admin.commandcenter.action.CommandCenterActionType;
import dev.christopherbell.admin.commandcenter.action.PendingActionStore;
import dev.christopherbell.admin.commandcenter.metrics.DatabaseConnectivityProbe;
import dev.christopherbell.admin.model.AdminActivity;
import dev.christopherbell.canesboxtracker.CanesBoxPriceSnapshotRepository;
import dev.christopherbell.canesboxtracker.model.CanesBoxMetroPrice;
import dev.christopherbell.canesboxtracker.model.CanesBoxPriceSnapshot;
import dev.christopherbell.libs.lease.ScheduledCollectorRun;
import dev.christopherbell.libs.lease.ScheduledCollectorRunStatus;
import dev.christopherbell.libs.lease.ScheduledCollectorRunStore;
import dev.christopherbell.location.model.ZipCoordinate;
import dev.christopherbell.location.model.ZipCoordinateImportResult;
import dev.christopherbell.location.model.ZipCoordinateImportState;
import dev.christopherbell.location.zip.ZipCoordinateImportStateRepository;
import dev.christopherbell.location.zip.ZipCoordinateRepository;
import dev.christopherbell.vehicle.core.VehicleRepository;
import dev.christopherbell.vehicle.model.Vehicle;
import dev.christopherbell.vehicle.model.VehicleVinDecodeCache;
import dev.christopherbell.vehicle.model.VehicleVinDecodeResponse;
import dev.christopherbell.vehicle.nhtsa.decode.VehicleVinDecodeCacheRepository;
import dev.christopherbell.vehicle.nhtsa.enrichment.NhtsaVinImportStateRepository;
import dev.christopherbell.vehicle.nhtsa.model.NhtsaVinImportState;
import dev.christopherbell.vehicle.randomvin.importing.RandomVinImportStateRepository;
import dev.christopherbell.vehicle.randomvin.model.RandomVinImportState;
import dev.christopherbell.whatsforlunch.restaurant.DailyLunchPicksRepository;
import dev.christopherbell.whatsforlunch.restaurant.RestaurantDuplicateQueryPort;
import dev.christopherbell.whatsforlunch.restaurant.RestaurantImportStateRepository;
import dev.christopherbell.whatsforlunch.restaurant.RestaurantInventoryQueryPort;
import dev.christopherbell.whatsforlunch.restaurant.RestaurantRepository;
import dev.christopherbell.whatsforlunch.restaurant.favorite.RestaurantFavoriteRepository;
import dev.christopherbell.whatsforlunch.restaurant.importing.RestaurantImportPreviewCounts;
import dev.christopherbell.whatsforlunch.restaurant.importing.RestaurantImportPreviewDocument;
import dev.christopherbell.whatsforlunch.restaurant.importing.RestaurantImportPreviewPort;
import dev.christopherbell.whatsforlunch.restaurant.importing.RestaurantImportRunStatus;
import dev.christopherbell.whatsforlunch.restaurant.model.Address;
import dev.christopherbell.whatsforlunch.restaurant.model.DailyLunchPicks;
import dev.christopherbell.whatsforlunch.restaurant.model.Restaurant;
import dev.christopherbell.whatsforlunch.restaurant.model.RestaurantFavorite;
import dev.christopherbell.whatsforlunch.restaurant.model.RestaurantImportState;
import dev.christopherbell.whatsforlunch.restaurant.model.RestaurantVote;
import dev.christopherbell.whatsforlunch.restaurant.model.RestaurantVoteValue;
import dev.christopherbell.whatsforlunch.restaurant.model.WhatsForLunchPreference;
import dev.christopherbell.whatsforlunch.restaurant.model.WhatsForLunchSession;
import dev.christopherbell.whatsforlunch.restaurant.preference.WhatsForLunchPreferenceRepository;
import dev.christopherbell.whatsforlunch.restaurant.session.WhatsForLunchSessionMutationPort;
import dev.christopherbell.whatsforlunch.restaurant.session.WhatsForLunchSessionMutationStore;
import dev.christopherbell.whatsforlunch.restaurant.session.WhatsForLunchSessionRepository;
import dev.christopherbell.whatsforlunch.restaurant.vote.RestaurantVoteQueryPort;
import dev.christopherbell.whatsforlunch.restaurant.vote.RestaurantVoteRepository;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.PageRequest;

/** Identical substantive Task 5 assertions run against real MongoDB and PostgreSQL. */
interface Task5PersistenceParityContract {
  Instant NOW = Instant.parse("2026-08-14T04:00:00Z");
  String OWNER_ID = "task5-owner";
  String MEMBER_ID = "task5-member";

  VehicleRepository vehicles();
  VehicleRepository vehicleContender();
  VehicleVinDecodeCacheRepository vinCache();
  NhtsaVinImportStateRepository nhtsaState();
  RandomVinImportStateRepository randomVinState();
  ZipCoordinateRepository zipCoordinates();
  ZipCoordinateImportStateRepository zipImportState();
  RestaurantRepository restaurants();
  RestaurantRepository restaurantContender();
  DailyLunchPicksRepository dailyPicks();
  RestaurantImportStateRepository restaurantImportState();
  RestaurantImportPreviewPort importPreviews();
  RestaurantFavoriteRepository favorites();
  WhatsForLunchPreferenceRepository preferences();
  WhatsForLunchSessionRepository sessions();
  WhatsForLunchSessionMutationPort sessionMutations();
  RestaurantVoteRepository votes();
  RestaurantVoteQueryPort voteQueries();
  RestaurantInventoryQueryPort inventoryQueries();
  RestaurantDuplicateQueryPort duplicateQueries();
  CanesBoxPriceSnapshotRepository canesSnapshots();
  AdminActivityRepository adminActivities();
  AdminActivityQueryPort adminActivityQueries();
  PendingActionStore pendingActions();
  PendingActionStore pendingActionContender();
  ScheduledCollectorRunStore scheduledRuns();
  DatabaseConnectivityProbe databaseProbe();

  @Test
  default void mobilityPortsPreserveVinCacheStateZipIntegrityAndStableOrdering() {
    var alpha = vehicle("task5-vehicle-a", "1HGCM82633A004352", "Mazda", "3", 2019);
    var beta = vehicle("task5-vehicle-b", "1FTFW1ET1EFA00001", "Ford", "F-150", 2022);
    vehicles().saveAll(List.of(beta, alpha));
    assertThat(vehicles().findAllByOrderByMakeAscModelAscYearDesc())
        .extracting(Vehicle::getId)
        .containsExactly("task5-vehicle-b", "task5-vehicle-a");
    assertThat(vehicles().existsByVin(alpha.getVin())).isTrue();
    assertThat(vehicles().findByMakeIgnoreCase("mAzDa"))
        .extracting(Vehicle::getId).containsExactly(alpha.getId());
    assertThatThrownBy(() -> vehicleContender().save(
        vehicle("task5-vehicle-racer", alpha.getVin(), "Other", "Owner", 2020)))
        .isInstanceOf(DuplicateKeyException.class);

    var response = VehicleVinDecodeResponse.builder()
        .vin(alpha.getVin()).make("Mazda").model("3").year(2019)
        .rawDecodedValues(Map.of("BodyClass", "Hatchback"))
        .build();
    vinCache().save(VehicleVinDecodeCache.builder()
        .vin(alpha.getVin()).response(response).decoderVersion("v1")
        .refreshedOn(NOW).expiresOn(NOW.plus(Duration.ofDays(1)))
        .createdOn(NOW).lastUpdatedOn(NOW).build());
    assertThat(vinCache().findById(alpha.getVin()).orElseThrow().getResponse().make())
        .isEqualTo("Mazda");

    nhtsaState().save(NhtsaVinImportState.builder()
        .id("task5-nhtsa").callsToday(2).callsOnDate(LocalDate.parse("2026-08-14"))
        .lifetimeCalls(10L).lifetimeVinsProcessed(8L).permanentlyDisabled(false)
        .vinsProcessedToday(1).build());
    randomVinState().save(RandomVinImportState.builder()
        .id("task5-random-vin").callsToday(3).callsOnDate(LocalDate.parse("2026-08-14"))
        .lifetimeCalls(11L).lifetimeVinsProcessed(9L).permanentlyDisabled(false)
        .vinsProcessedToday(2).build());
    assertThat(nhtsaState().findById("task5-nhtsa")).isPresent();
    assertThat(randomVinState().findById("task5-random-vin")).isPresent();

    var firstZip = ZipCoordinate.builder().zipCode("78701").latitude(30.2711)
        .longitude(-97.7437).source("CENSUS").sourceYear(2025)
        .createdOn(NOW).lastUpdatedOn(NOW).build();
    var secondZip = ZipCoordinate.builder().zipCode("78660").latitude(30.4394)
        .longitude(-97.6200).source("CENSUS").sourceYear(2025)
        .createdOn(NOW).lastUpdatedOn(NOW).build();
    zipCoordinates().saveAll(List.of(firstZip, secondZip));
    assertThat(zipCoordinates().findAllBySource("CENSUS"))
        .extracting(ZipCoordinate::getZipCode).containsExactlyInAnyOrder("78701", "78660");
    var importResult = ZipCoordinateImportResult.builder()
        .processed(2).created(2).source("CENSUS").sourceYear(2025)
        .checksum("task5-checksum").importedOn(NOW).build();
    zipImportState().save(ZipCoordinateImportState.builder()
        .id("task5-zip-import").checksum("task5-checksum").source("CENSUS")
        .sourceYear(2025).importedOn(NOW).result(importResult).build());
    assertThat(zipImportState().findById("task5-zip-import").orElseThrow().getResult().processed())
        .isEqualTo(2);
  }

  @Test
  default void lunchPortsPreserveLocationOwnershipOrderingClaimsMutationsVotesAndQueries()
      throws Exception {
    var first = restaurant("task5-restaurant-a", "Alpha Cafe", "alpha cafe", "Austin", "TX");
    var second = restaurant("task5-restaurant-b", "Beta Bistro", "beta bistro", "Pflugerville", "TX");
    restaurants().save(first);
    restaurants().save(second);
    assertThat(restaurants().findByNormalizedName("alpha cafe")).isPresent();
    assertThatThrownBy(() -> restaurantContender().save(
        restaurant("task5-restaurant-racer", "Alpha Cafe", "alpha cafe", "Round Rock", "TX")))
        .isInstanceOf(DuplicateKeyException.class);
    var fabricated = restaurant(
        "task5-restaurant-fabricated", "Fake", "fake", "Imported Metro", "TX");
    assertThatThrownBy(() -> restaurants().save(fabricated))
        .isInstanceOf(IllegalArgumentException.class);

    dailyPicks().save(DailyLunchPicks.builder().id("2026-08-14").pickDate("2026-08-14")
        .restaurantIds(List.of(second.getId(), first.getId())).generatedOn(NOW).build());
    assertThat(dailyPicks().findById("2026-08-14").orElseThrow().getRestaurantIds())
        .containsExactly(second.getId(), first.getId());

    restaurantImportState().save(RestaurantImportState.builder()
        .id("task5-osm-state").status(RestaurantImportRunStatus.RUNNING)
        .trigger("parity").actorAccountId(OWNER_ID).lastStartedOn(NOW).build());
    assertThat(restaurantImportState().findById("task5-osm-state")).isPresent();
    importPreviews().save(RestaurantImportPreviewDocument.builder()
        .id("task5-preview").actorAccountId(OWNER_ID).checksum("checksum")
        .createdOn(NOW).expiresOn(NOW.plusSeconds(60))
        .counts(new RestaurantImportPreviewCounts(2, 1, 0, 0, 1, 0)).build());
    assertThat(importPreviews().claim("task5-preview", OWNER_ID, NOW.plusSeconds(1))).isPresent();
    assertThat(importPreviews().claim("task5-preview", OWNER_ID, NOW.plusSeconds(2))).isEmpty();

    favorites().save(RestaurantFavorite.builder().id("task5-favorite")
        .restaurantId(first.getId()).accountId(OWNER_ID).createdOn(NOW).build());
    assertThat(favorites().findByAccountIdOrderByCreatedOnDesc(OWNER_ID))
        .extracting(RestaurantFavorite::getRestaurantId).containsExactly(first.getId());
    preferences().save(WhatsForLunchPreference.builder().accountId(OWNER_ID)
        .cuisines(List.of("Thai", "Mexican")).radiusMiles(15).build());
    assertThat(preferences().findById(OWNER_ID).orElseThrow().getCuisines())
        .containsExactly("Thai", "Mexican");

    sessions().save(WhatsForLunchSession.builder()
        .id("task5-session").createdByAccountId(OWNER_ID).createdByUsername("owner")
        .participantAccountIds(List.of(OWNER_ID))
        .participantUsernamesByAccountId(Map.of(OWNER_ID, "owner"))
        .restaurantIds(List.of(first.getId(), second.getId())).votesByAccountId(Map.of())
        .revision(0).activeUntil(NOW.plusSeconds(3_600)).deleteOn(NOW.plusSeconds(7_200))
        .restaurantResetCount(0).restaurantResetAudit(List.of())
        .createdOn(NOW).lastUpdatedOn(NOW).build());
    var joined = sessionMutations().join(
        "task5-session", MEMBER_ID, "member", NOW.plusSeconds(1), 10);
    assertThat(joined.status()).isEqualTo(WhatsForLunchSessionMutationStore.Status.UPDATED);
    var voted = sessionMutations().vote(
        "task5-session", MEMBER_ID, second.getId(), NOW.plusSeconds(2));
    assertThat(voted.status()).isEqualTo(WhatsForLunchSessionMutationStore.Status.UPDATED);
    assertThat(sessions().findByParticipantAccountIdsContainingAndDeleteOnAfterOrderByCreatedOnDesc(
        MEMBER_ID, NOW, PageRequest.of(0, 10)))
        .extracting(WhatsForLunchSession::getId).containsExactly("task5-session");

    votes().save(RestaurantVote.builder().id("task5-vote-owner").restaurantId(first.getId())
        .accountId(OWNER_ID).vote(RestaurantVoteValue.UP).createdOn(NOW).lastUpdatedOn(NOW).build());
    votes().save(RestaurantVote.builder().id("task5-vote-member").restaurantId(first.getId())
        .accountId(MEMBER_ID).vote(RestaurantVoteValue.DOWN).createdOn(NOW).lastUpdatedOn(NOW).build());
    assertThat(voteQueries().summariesForRestaurants(List.of(first.getId())))
        .singleElement().satisfies(summary -> {
          assertThat(summary.upVotes()).isEqualTo(1);
          assertThat(summary.downVotes()).isEqualTo(1);
        });
    assertThat(voteQueries().topLiked(10)).extracting(summary -> summary.restaurantId())
        .contains(first.getId());
    assertThat(inventoryQueries().find("alpha", "Austin", "TX", null, 10).items())
        .extracting(Restaurant::getId).containsExactly(first.getId());
    assertThat(duplicateQueries().find(null, 10).keys()).isEmpty();
  }

  @Test
  default void canesPortPreservesCentPrecisionAndMetroOrdering() {
    var snapshot = new CanesBoxPriceSnapshot();
    snapshot.setId("task5-canes");
    snapshot.setWeekStartDate("2026-08-10");
    snapshot.setCollectedOn(NOW);
    snapshot.setAveragePrice(new BigDecimal("10.25"));
    snapshot.setCurrency("USD");
    snapshot.setSuccessfulMetroCount(2);
    snapshot.setTotalMetroCount(2);
    snapshot.setVerifiedMetroCount(2);
    snapshot.setProvisionalMetroCount(0);
    snapshot.setExcludedMetroCount(0);
    snapshot.setMetroPrices(List.of(
        metro("Austin", "10.10"), metro("Dallas", "10.40")));
    canesSnapshots().save(snapshot);
    var persisted = canesSnapshots().findById(snapshot.getId()).orElseThrow();
    assertThat(persisted.getAveragePrice()).isEqualByComparingTo("10.25");
    assertThat(persisted.getMetroPrices()).extracting(CanesBoxMetroPrice::getMetroName)
        .containsExactly("Austin", "Dallas");
    assertThat(canesSnapshots().findTop60ByOrderByWeekStartDateDesc())
        .extracting(CanesBoxPriceSnapshot::getId).contains("task5-canes");
  }

  @Test
  default void adminAndPlatformPortsPreserveImmutableAuditAtomicReservationAndSafeRuns()
      throws Exception {
    var activity = AdminActivity.builder().id("task5-activity").actorAccountId(OWNER_ID)
        .actorUsername("Task5Owner").action("UPDATE").targetType("RESTAURANT")
        .targetId("task5-restaurant-a").targetLabel("Alpha Cafe").reason("parity")
        .message("updated").beforeValues(Map.of("name", "Old"))
        .afterValues(Map.of("name", "Alpha Cafe")).metadata(Map.of("source", "test"))
        .createdOn(NOW).build();
    adminActivities().insert(activity);
    assertThat(adminActivities().findTop25ByOrderByCreatedOnDesc())
        .extracting(AdminActivity::getId).contains("task5-activity");
    assertThat(adminActivityQueries().query(new AdminActivityQuery(
        "UPDATE", "RESTAURANT", "5own", NOW.minusSeconds(1), NOW.plusSeconds(1), 0, 10))
        .items()).extracting(AdminActivity::getId).containsExactly("task5-activity");
    assertThatThrownBy(() -> adminActivities().save(activity))
        .isInstanceOf(DuplicateKeyException.class);

    var first = new PendingActionStore.Reservation(
        CommandCenterActionType.RESTART_COMPUTER, NOW, NOW.plusSeconds(60));
    var second = new PendingActionStore.Reservation(
        CommandCenterActionType.SHUTDOWN_COMPUTER, NOW.plusSeconds(1), NOW.plusSeconds(61));
    assertThat(pendingActions().reserve(first, NOW)).isTrue();
    assertThat(pendingActionContender().reserve(second, NOW.plusSeconds(1))).isFalse();
    assertThat(pendingActions().active(NOW.plusSeconds(2))).contains(first);
    assertThat(pendingActions().clear(first)).isTrue();

    var running = ScheduledCollectorRun.builder().id("task5-run").collectorName("task5")
        .ownerToken("owner-token").status(ScheduledCollectorRunStatus.RUNNING)
        .startedOn(NOW).build();
    scheduledRuns().save(running);
    running.setStatus(ScheduledCollectorRunStatus.SUCCEEDED);
    running.setCompletedOn(NOW.plusSeconds(1));
    var completed = scheduledRuns().save(running);
    assertThat(completed.getStatus()).isEqualTo(ScheduledCollectorRunStatus.SUCCEEDED);
    assertThat(completed.getErrorCategory()).isNull();
    assertThat(databaseProbe().backendName()).isNotBlank();
    assertThat(databaseProbe().ping(Duration.ofSeconds(2))).isTrue();
  }

  private static Vehicle vehicle(String id, String vin, String make, String model, int year) {
    return Vehicle.builder().id(id).vin(vin).make(make).model(model).year(year)
        .notes("task5").createdOn(NOW).lastUpdatedOn(NOW).build();
  }

  private static Restaurant restaurant(
      String id, String name, String normalizedName, String city, String state) {
    return Restaurant.builder().id(id).name(name).normalizedName(normalizedName)
        .dedupeKey(normalizedName).searchCity(city.toLowerCase(java.util.Locale.ROOT))
        .searchState(state.toLowerCase(java.util.Locale.ROOT))
        .address(Address.builder().city(city).state(state).country("US")
            .latitude(30.2672).longitude(-97.7431).postalCode("78701")
            .street1("100 Congress Ave").build())
        .createdOn(NOW).lastUpdatedOn(NOW).build();
  }

  private static CanesBoxMetroPrice metro(String name, String amount) {
    var price = new CanesBoxMetroPrice();
    price.setMetroName(name);
    price.setCity(name);
    price.setState("TX");
    price.setPrice(new BigDecimal(amount));
    price.setCurrency("USD");
    price.setStatus("SUCCESS");
    price.setQualityStatus("VERIFIED");
    price.setCollectedOn(NOW);
    return price;
  }
}
