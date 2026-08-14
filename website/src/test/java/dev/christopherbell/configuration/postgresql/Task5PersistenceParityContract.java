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
import dev.christopherbell.vehicle.randomvin.model.RandomVinRobotsPolicyState;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
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
  @Task5ContractPorts({
      VehicleRepository.class,
      VehicleVinDecodeCacheRepository.class,
      NhtsaVinImportStateRepository.class,
      RandomVinImportStateRepository.class,
      ZipCoordinateRepository.class,
      ZipCoordinateImportStateRepository.class
  })
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
  @Task5ContractPorts({
      VehicleRepository.class,
      VehicleVinDecodeCacheRepository.class,
      NhtsaVinImportStateRepository.class,
      RandomVinImportStateRepository.class,
      ZipCoordinateRepository.class,
      ZipCoordinateImportStateRepository.class
  })
  default void mobilityPortsPreserveUpdatesDeletesAndPresentPolicyState() {
    var vehicle = vehicle("task5-mobility-update", "JM1BN1L30K1234567", "Mazda", "3", 2019);
    vehicles().save(vehicle);
    vehicle.setNotes("updated");
    vehicles().save(vehicle);
    assertThat(vehicles().findById(vehicle.getId()).orElseThrow().getNotes()).isEqualTo("updated");
    vehicles().delete(vehicle);
    assertThat(vehicles().findById(vehicle.getId())).isEmpty();

    var cache = VehicleVinDecodeCache.builder().vin("T5CACHEUPDATE0001")
        .response(VehicleVinDecodeResponse.builder().make("Before").build()).build();
    vinCache().save(cache);
    cache.setResponse(null);
    vinCache().save(cache);
    assertThat(vinCache().findById(cache.getVin()).orElseThrow().getResponse()).isNull();

    var nhtsa = NhtsaVinImportState.builder().id("task5-nhtsa-update").callsToday(1).build();
    nhtsaState().save(nhtsa);
    nhtsa.setCallsToday(2);
    assertThat(nhtsaState().save(nhtsa).getCallsToday()).isEqualTo(2);
    var random = RandomVinImportState.builder().id("task5-random-update")
        .robotsPolicy(new RandomVinRobotsPolicyState(NOW, true, "allowed", false)).build();
    randomVinState().save(random);
    random.getRobotsPolicy().setReason("updated");
    assertThat(randomVinState().save(random).getRobotsPolicy().getReason()).isEqualTo("updated");

    var coordinate = ZipCoordinate.builder().zipCode("73301").latitude(30.0).longitude(-97.0)
        .source("TASK5_UPDATE").sourceYear(2026).createdOn(NOW).lastUpdatedOn(NOW).build();
    zipCoordinates().saveAll(List.of(coordinate));
    zipCoordinates().deleteAll(List.of(coordinate));
    assertThat(zipCoordinates().findById(coordinate.getZipCode())).isEmpty();
    var importState = ZipCoordinateImportState.builder().id("task5-zip-update")
        .checksum("initial-checksum")
        .source("TASK5").sourceYear(2026).importedOn(NOW)
        .result(ZipCoordinateImportResult.builder().processed(1).created(1)
            .source("TASK5").sourceYear(2026).checksum("initial-checksum")
            .importedOn(NOW).build())
        .build();
    zipImportState().save(importState);
    importState.setChecksum("updated-checksum");
    assertThat(zipImportState().save(importState).getChecksum()).isEqualTo("updated-checksum");
  }

  @Test
  @Task5ContractPorts({
      VehicleVinDecodeCacheRepository.class,
      NhtsaVinImportStateRepository.class,
      RandomVinImportStateRepository.class,
      AdminActivityRepository.class,
      RestaurantRepository.class,
      RestaurantVoteRepository.class
  })
  default void nullableSourceStatesRemainDistinctFromPresentDefaultValues() {
    var absentResponse = VehicleVinDecodeCache.builder()
        .vin("T5NULLRESPONSE001").response(null).build();
    var presentEmptyResponse = VehicleVinDecodeCache.builder()
        .vin("T5EMPTYRESPONSE01")
        .response(VehicleVinDecodeResponse.builder().rawDecodedValues(Map.of()).build())
        .build();
    vinCache().save(absentResponse);
    vinCache().save(presentEmptyResponse);
    assertThat(vinCache().findById(absentResponse.getVin()).orElseThrow().getResponse()).isNull();
    assertThat(vinCache().findById(presentEmptyResponse.getVin()).orElseThrow().getResponse())
        .isNotNull()
        .extracting(VehicleVinDecodeResponse::rawDecodedValues)
        .isEqualTo(Map.of());

    nhtsaState().save(NhtsaVinImportState.builder().id("task5-null-nhtsa").build());
    assertThat(nhtsaState().findById("task5-null-nhtsa").orElseThrow())
        .satisfies(state -> {
          assertThat(state.getCallsToday()).isNull();
          assertThat(state.getLifetimeCalls()).isNull();
          assertThat(state.getLifetimeVinsProcessed()).isNull();
          assertThat(state.getPermanentlyDisabled()).isNull();
          assertThat(state.getVinsProcessedToday()).isNull();
        });

    randomVinState().save(RandomVinImportState.builder().id("task5-null-random").build());
    assertThat(randomVinState().findById("task5-null-random").orElseThrow())
        .satisfies(state -> {
          assertThat(state.getCallsToday()).isNull();
          assertThat(state.getLifetimeCalls()).isNull();
          assertThat(state.getLifetimeVinsProcessed()).isNull();
          assertThat(state.getPermanentlyDisabled()).isNull();
          assertThat(state.getRobotsPolicy()).isNull();
          assertThat(state.getVinsProcessedToday()).isNull();
        });
    randomVinState().save(RandomVinImportState.builder().id("task5-null-random-policy")
        .robotsPolicy(new RandomVinRobotsPolicyState(null, null, null, null)).build());
    assertThat(randomVinState().findById("task5-null-random-policy").orElseThrow()
        .getRobotsPolicy()).isNotNull().satisfies(policy -> {
          assertThat(policy.getAllowed()).isNull();
          assertThat(policy.getFailClosed()).isNull();
        });

    adminActivities().insert(AdminActivity.builder().id("task5-null-admin")
        .actorUsername("Task5Owner").action("NULL_PARITY").targetType("RESTAURANT")
        .targetId("task5-null-target").createdOn(NOW).build());
    assertThat(adminActivities().findById("task5-null-admin").orElseThrow())
        .satisfies(activity -> {
          assertThat(activity.getTargetLabel()).isNull();
          assertThat(activity.getReason()).isNull();
          assertThat(activity.getMessage()).isNull();
          assertThat(activity.getBeforeValues()).isNull();
          assertThat(activity.getAfterValues()).isNull();
          assertThat(activity.getMetadata()).isNull();
        });

    var restaurant = restaurant(
        "task5-null-vote-restaurant", "Null Vote Cafe", "null vote cafe", "Austin", "TX");
    restaurants().save(restaurant);
    votes().save(RestaurantVote.builder().id("task5-null-vote")
        .restaurantId(restaurant.getId()).accountId(OWNER_ID).vote(null)
        .createdOn(NOW).lastUpdatedOn(NOW).build());
    assertThat(votes().findById("task5-null-vote").orElseThrow().getVote()).isNull();
    assertThat(voteQueries().summariesForRestaurants(List.of(restaurant.getId()))).isEmpty();
    assertThat(voteQueries().topLiked(50))
        .extracting(summary -> summary.restaurantId()).doesNotContain(restaurant.getId());
  }

  @Test
  @Task5ContractPorts({
      RestaurantRepository.class,
      DailyLunchPicksRepository.class,
      RestaurantImportStateRepository.class,
      RestaurantImportPreviewPort.class,
      RestaurantFavoriteRepository.class,
      WhatsForLunchPreferenceRepository.class,
      WhatsForLunchSessionRepository.class,
      WhatsForLunchSessionMutationPort.class,
      RestaurantVoteRepository.class,
      RestaurantVoteQueryPort.class,
      RestaurantInventoryQueryPort.class,
      RestaurantDuplicateQueryPort.class
  })
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
  @Task5ContractPorts({
      RestaurantRepository.class,
      DailyLunchPicksRepository.class,
      RestaurantImportStateRepository.class,
      RestaurantImportPreviewPort.class,
      RestaurantFavoriteRepository.class,
      WhatsForLunchPreferenceRepository.class,
      WhatsForLunchSessionRepository.class,
      WhatsForLunchSessionMutationPort.class,
      RestaurantVoteRepository.class,
      RestaurantDuplicateQueryPort.class
  })
  default void lunchPortsPreserveUpdatesDeletesIdempotencyAndStaleOutcomes() {
    var first = restaurant("task5-lunch-update-a", "Update Alpha", "update alpha", "Austin", "TX");
    var second = restaurant("task5-lunch-update-b", "Update Beta", "update beta", "Austin", "TX");
    first.setDedupeKey("task5-duplicate-key");
    second.setDedupeKey("task5-duplicate-key");
    restaurants().save(first);
    restaurants().save(second);
    first.setCuisine("Thai");
    assertThat(restaurants().save(first).getCuisine()).isEqualTo("Thai");
    assertThat(restaurants().findAllById(List.of(second.getId(), first.getId())))
        .extracting(Restaurant::getId).containsExactly(first.getId(), second.getId());
    assertThat(duplicateQueries().find(null, 10).keys()).contains("task5-duplicate-key");

    var picks = DailyLunchPicks.builder().id("task5-picks-update").pickDate("2026-08-15")
        .restaurantIds(List.of(first.getId(), second.getId())).generatedOn(NOW).build();
    dailyPicks().save(picks);
    picks.setRestaurantIds(List.of(second.getId()));
    assertThat(dailyPicks().save(picks).getRestaurantIds()).containsExactly(second.getId());
    var importState = RestaurantImportState.builder().id("task5-import-update")
        .status(RestaurantImportRunStatus.RUNNING).lastStartedOn(NOW).build();
    restaurantImportState().save(importState);
    importState.setStatus(RestaurantImportRunStatus.SUCCEEDED);
    importState.setLastCompletedOn(NOW.plusSeconds(1));
    assertThat(restaurantImportState().save(importState).getStatus())
        .isEqualTo(RestaurantImportRunStatus.SUCCEEDED);

    importPreviews().save(RestaurantImportPreviewDocument.builder()
        .id("task5-preview-conditional").actorAccountId(OWNER_ID).checksum("conditional")
        .createdOn(NOW).expiresOn(NOW.plusSeconds(60))
        .counts(new RestaurantImportPreviewCounts(1, 1, 0, 0, 0, 0)).build());
    assertThat(importPreviews().claim(
        "task5-preview-conditional", MEMBER_ID, NOW.plusSeconds(1))).isEmpty();
    assertThat(importPreviews().claim(
        "task5-preview-conditional", OWNER_ID, NOW.plusSeconds(1))).isPresent();
    assertThat(importPreviews().claim(
        "task5-preview-conditional", OWNER_ID, NOW.plusSeconds(2))).isEmpty();

    var favorite = RestaurantFavorite.builder().id("task5-favorite-delete")
        .restaurantId(first.getId()).accountId(OWNER_ID).createdOn(NOW).build();
    favorites().save(favorite);
    favorites().deleteByRestaurantIdAndAccountId(first.getId(), OWNER_ID);
    assertThat(favorites().findByRestaurantIdAndAccountId(first.getId(), OWNER_ID)).isEmpty();
    var preference = WhatsForLunchPreference.builder().accountId(OWNER_ID)
        .cuisines(List.of("Thai")).radiusMiles(5).build();
    preferences().save(preference);
    preference.setRadiusMiles(20);
    assertThat(preferences().save(preference).getRadiusMiles()).isEqualTo(20);

    sessions().save(WhatsForLunchSession.builder().id("task5-session-conditional")
        .createdByAccountId(OWNER_ID).createdByUsername("owner")
        .participantAccountIds(List.of(OWNER_ID))
        .participantUsernamesByAccountId(Map.of(OWNER_ID, "owner"))
        .restaurantIds(List.of(first.getId())).votesByAccountId(Map.of())
        .revision(0).activeUntil(NOW.plusSeconds(60)).deleteOn(NOW.plusSeconds(120))
        .restaurantResetCount(0).restaurantResetAudit(List.of())
        .createdOn(NOW).lastUpdatedOn(NOW).build());
    assertThat(sessionMutations().join(
        "task5-session-conditional", OWNER_ID, "owner", NOW.plusSeconds(1), 10).status())
        .isEqualTo(WhatsForLunchSessionMutationStore.Status.UNCHANGED);
    assertThat(sessionMutations().vote(
        "task5-session-conditional", MEMBER_ID, first.getId(), NOW.plusSeconds(2)).status())
        .isEqualTo(WhatsForLunchSessionMutationStore.Status.NOT_PARTICIPANT);
    assertThat(sessionMutations().join(
        "task5-missing-session", MEMBER_ID, "member", NOW.plusSeconds(1), 10).status())
        .isEqualTo(WhatsForLunchSessionMutationStore.Status.MISSING);

    var vote = RestaurantVote.builder().id("task5-vote-update").restaurantId(second.getId())
        .accountId(OWNER_ID).vote(RestaurantVoteValue.UP)
        .createdOn(NOW).lastUpdatedOn(NOW).build();
    votes().save(vote);
    vote.setVote(RestaurantVoteValue.DOWN);
    assertThat(votes().save(vote).getVote()).isEqualTo(RestaurantVoteValue.DOWN);
    votes().deleteById(vote.getId());
    assertThat(votes().findById(vote.getId())).isEmpty();

    second.setDedupeKey("update beta");
    restaurants().save(second);
    assertThat(duplicateQueries().find(null, 10).keys()).doesNotContain("task5-duplicate-key");

    var deleteFirst = restaurant(
        "task5-lunch-delete-a", "Delete Alpha", "delete alpha", "Austin", "TX");
    var deleteSecond = restaurant(
        "task5-lunch-delete-b", "Delete Beta", "delete beta", "Austin", "TX");
    restaurants().save(deleteFirst);
    restaurants().save(deleteSecond);
    restaurants().deleteAll(List.of(deleteFirst, deleteSecond));
    assertThat(restaurants().findAllById(List.of(deleteFirst.getId(), deleteSecond.getId())))
        .isEmpty();
  }

  @Test
  @Task5ContractPorts(RestaurantRepository.class)
  default void normalizedNameRaceHasExactlyOneOwnerAcrossIndependentConnections()
      throws Exception {
    var first = restaurant(
        "task5-race-a", "Task 5 Race Cafe", "task 5 race cafe", "Austin", "TX");
    var second = restaurant(
        "task5-race-b", "Task 5 Race Cafe", "task 5 race cafe", "Round Rock", "TX");
    var ready = new CountDownLatch(2);
    var start = new CountDownLatch(1);
    try (var workers = Executors.newFixedThreadPool(2)) {
      var firstResult = workers.submit(() -> raceSave(restaurants(), first, ready, start));
      var secondResult = workers.submit(
          () -> raceSave(restaurantContender(), second, ready, start));
      assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
      start.countDown();
      var outcomes = List.of(
          firstResult.get(10, TimeUnit.SECONDS), secondResult.get(10, TimeUnit.SECONDS));
      assertThat(outcomes).filteredOn(Restaurant.class::isInstance).singleElement();
      assertThat(outcomes).filteredOn(DuplicateKeyException.class::isInstance).singleElement();
    }
    assertThat(restaurants().findByNormalizedName("task 5 race cafe"))
        .isPresent().get().extracting(Restaurant::getId)
        .isIn(first.getId(), second.getId());
  }

  @Test
  @Task5ContractPorts(CanesBoxPriceSnapshotRepository.class)
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
    snapshot.setAveragePrice(new BigDecimal("10.26"));
    snapshot.setMetroPrices(List.of(metro("Houston", "10.26")));
    var updated = canesSnapshots().save(snapshot);
    assertThat(updated.getAveragePrice()).isEqualByComparingTo("10.26");
    assertThat(updated.getMetroPrices()).extracting(CanesBoxMetroPrice::getMetroName)
        .containsExactly("Houston");
  }

  @Test
  @Task5ContractPorts({
      AdminActivityRepository.class,
      AdminActivityQueryPort.class,
      PendingActionStore.class,
      ScheduledCollectorRunStore.class,
      DatabaseConnectivityProbe.class
  })
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
    assertThat(pendingActions().clear(first)).isFalse();
    pendingActions().reconcile(NOW.plusSeconds(120));

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

  private static Object raceSave(
      RestaurantRepository repository,
      Restaurant restaurant,
      CountDownLatch ready,
      CountDownLatch start) throws InterruptedException {
    ready.countDown();
    start.await();
    try {
      return repository.save(restaurant);
    } catch (DuplicateKeyException duplicate) {
      return duplicate;
    }
  }
}
