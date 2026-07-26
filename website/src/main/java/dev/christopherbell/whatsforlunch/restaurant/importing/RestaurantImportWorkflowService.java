package dev.christopherbell.whatsforlunch.restaurant.importing;

import dev.christopherbell.configuration.mongo.lease.MongoLeaseService;
import dev.christopherbell.configuration.mongo.lease.RenewingMongoLease;
import dev.christopherbell.permission.PermissionService;
import dev.christopherbell.whatsforlunch.restaurant.RestaurantImportStateRepository;
import dev.christopherbell.whatsforlunch.restaurant.RestaurantService;
import dev.christopherbell.whatsforlunch.restaurant.config.WflProperties;
import dev.christopherbell.whatsforlunch.restaurant.model.RestaurantImportResult;
import dev.christopherbell.whatsforlunch.restaurant.model.RestaurantImportState;
import java.time.Clock;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.context.event.EventListener;
import org.springframework.web.server.ResponseStatusException;

/** Coordinates previewed and scheduled restaurant imports behind one durable lease. */
@RequiredArgsConstructor
@Service
@Slf4j
public class RestaurantImportWorkflowService {
  public static final String LEASE_NAME = "wfl-openstreetmap-import";
  public static final String STATE_ID = "openstreetmap-monthly";
  private static final int PUBLIC_FRESHNESS_DAYS = 45;

  private final Clock clock;
  private final MongoLeaseService leases;
  private final PermissionService permissionService;
  private final RestaurantImportPreviewStore previews;
  private final RestaurantImportStateRepository states;
  private final RestaurantService restaurantService;
  private final WflProperties properties;

  /** Builds a non-mutating preview and binds its token to the current operator. */
  public RestaurantImportPreviewResponse previewOpenStreetMapImport() throws Exception {
    var actor = requireActor();
    var snapshot = restaurantService.prepareConfiguredMetroImport();
    var createdOn = Instant.now(clock);
    var expiresOn = createdOn.plus(properties.getRestaurantImport().getPreviewTtl());
    var token = UUID.randomUUID().toString();
    previews.save(RestaurantImportPreviewDocument.builder()
        .id(token)
        .actorAccountId(actor)
        .checksum(snapshot.checksum())
        .createdOn(createdOn)
        .expiresOn(expiresOn)
        .counts(snapshot.counts())
        .build());
    return new RestaurantImportPreviewResponse(
        token,
        snapshot.checksum(),
        expiresOn,
        snapshot.counts(),
        snapshot.representativeChanges());
  }

  /** Applies a previously reviewed preview after re-fetching and verifying the source checksum. */
  public RestaurantImportRunDetail applyOpenStreetMapImport(String token) throws Exception {
    if (token == null || token.isBlank()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Import preview token is required");
    }
    var actor = requireActor();
    return runWithLease("manual", actor, token);
  }

  /** Returns the latest durable operator status, if any. */
  public Optional<RestaurantImportState> getStatus() {
    return states.findById(STATE_ID);
  }

  /** Returns a public freshness view without operator, error, token, or lease details. */
  public RestaurantDataFreshness getPublicFreshness() {
    var refreshedOn = states.findById(STATE_ID)
        .map(RestaurantImportState::getLastCompletedOn)
        .orElse(null);
    var currentAfter = Instant.now(clock).minus(java.time.Duration.ofDays(PUBLIC_FRESHNESS_DAYS));
    var cities = properties.getRestaurantImport().getOsm().getMetros().stream()
        .flatMap(metro -> metro.getCities().stream()
            .map(city -> city + ", " + metro.getState()))
        .sorted()
        .toList();
    return new RestaurantDataFreshness(
        "OpenStreetMap",
        refreshedOn,
        refreshedOn != null && !refreshedOn.isBefore(currentAfter),
        PUBLIC_FRESHNESS_DAYS,
        cities);
  }

  @Scheduled(
      cron = "${wfl.restaurant-import.monthly.cron:0 0 3 15 * *}",
      zone = "${wfl.restaurant-import.monthly.zone:America/Chicago}"
  )
  public void runMonthlyOpenStreetMapImport() {
    if (properties.getRestaurantImport().getMonthly().isEnabled()) {
      runScheduled("scheduled-monthly");
    }
  }

  @EventListener(ApplicationReadyEvent.class)
  public void runMissedMonthlyOpenStreetMapImport() {
    if (!properties.getRestaurantImport().getMonthly().isEnabled()) {
      return;
    }
    var previousMonth = currentMonth().minusMonths(1);
    var completedMonth = states.findById(STATE_ID)
        .map(RestaurantImportState::getLastCompletedMonth)
        .flatMap(this::parseYearMonth)
        .orElse(null);
    if (completedMonth == null || completedMonth.isBefore(previousMonth)) {
      runScheduled("startup-catch-up");
    }
  }

  private void runScheduled(String trigger) {
    try {
      runWithLease(trigger, "system", null);
    } catch (Exception failure) {
      log.error("OpenStreetMap import failed. Trigger: {}.", trigger, failure);
    }
  }

  private RestaurantImportRunDetail runWithLease(
      String trigger,
      String actor,
      String previewToken
  ) throws Exception {
    var startedOn = Instant.now(clock);
    var ownerToken = UUID.randomUUID().toString();
    var expiresOn = startedOn.plus(properties.getRestaurantImport().getLeaseDuration());
    if (!leases.tryAcquire(LEASE_NAME, ownerToken, startedOn, expiresOn)) {
      var skipped = detail(RestaurantImportRunStatus.SKIPPED_LOCKED, trigger, startedOn, startedOn, null, null);
      saveState(skipped, actor);
      return skipped;
    }

    saveState(detail(RestaurantImportRunStatus.RUNNING, trigger, startedOn, null, null, null), actor);
    try {
      var expectedChecksum = previewToken == null
          ? null
          : previews.claim(previewToken, actor, startedOn)
              .orElseThrow(() -> new ResponseStatusException(
                  HttpStatus.CONFLICT, "Import preview is missing, expired, consumed, or belongs to another operator"))
              .getChecksum();
      var snapshot = restaurantService.prepareConfiguredMetroImport();
      if (expectedChecksum != null && !expectedChecksum.equals(snapshot.checksum())) {
        throw new ResponseStatusException(
            HttpStatus.CONFLICT, "OpenStreetMap data changed after preview; create a new preview");
      }
      var renewedOn = Instant.now(clock);
      if (!leases.renew(
          LEASE_NAME,
          ownerToken,
          renewedOn,
          renewedOn.plus(properties.getRestaurantImport().getLeaseDuration()))) {
        throw new ResponseStatusException(HttpStatus.CONFLICT, "OpenStreetMap import lease was lost");
      }
      var result = restaurantService.applyPreparedImport(
          snapshot,
          renewingLeaseGuard(ownerToken, renewedOn));
      var succeeded = detail(
          RestaurantImportRunStatus.SUCCEEDED,
          trigger,
          startedOn,
          Instant.now(clock),
          result,
          null);
      saveState(succeeded, actor);
      return succeeded;
    } catch (Exception failure) {
      var failed = detail(
          RestaurantImportRunStatus.FAILED,
          trigger,
          startedOn,
          Instant.now(clock),
          null,
          safeCategory(failure));
      saveState(failed, actor);
      throw failure;
    } finally {
      if (!leases.release(LEASE_NAME, ownerToken)) {
        log.warn("OpenStreetMap import lease was not released by its owner. Trigger: {}.", trigger);
      }
    }
  }

  private RestaurantImportRunDetail detail(
      RestaurantImportRunStatus status,
      String trigger,
      Instant startedOn,
      Instant endedOn,
      RestaurantImportResult result,
      String errorCategory
  ) {
    return new RestaurantImportRunDetail(status, trigger, startedOn, endedOn, result, errorCategory);
  }

  private void saveState(RestaurantImportRunDetail detail, String actor) {
    var state = states.findById(STATE_ID)
        .orElseGet(() -> RestaurantImportState.builder().id(STATE_ID).build());
    if (detail.status() == RestaurantImportRunStatus.SKIPPED_LOCKED) {
      state.setLastSkippedOn(detail.endedOn());
      state.setLastSkippedTrigger(detail.trigger());
      if (state.getStatus() == null) {
        state.setStatus(RestaurantImportRunStatus.SKIPPED_LOCKED);
      }
      states.save(state);
      return;
    }
    state.setStatus(detail.status());
    state.setTrigger(detail.trigger());
    state.setActorAccountId(actor);
    state.setLastStartedOn(detail.startedOn());
    state.setLastErrorCategory(detail.errorCategory());
    if (detail.status() == RestaurantImportRunStatus.SUCCEEDED) {
      state.setLastCompletedOn(detail.endedOn());
      state.setLastCompletedMonth(currentMonth().toString());
      state.setLastFailedOn(null);
      state.setLastFailureMessage(null);
      state.setLastResult(detail.result());
    } else if (detail.status() == RestaurantImportRunStatus.FAILED) {
      state.setLastFailedOn(detail.endedOn());
      state.setLastFailureMessage(detail.errorCategory());
    }
    states.save(state);
  }

  private String requireActor() {
    var actor = permissionService.getSelfId();
    if (actor == null || actor.isBlank()) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authenticated operator is required");
    }
    return actor;
  }

  private RestaurantImportLeaseGuard renewingLeaseGuard(String ownerToken, Instant renewedOn) {
    var duration = properties.getRestaurantImport().getLeaseDuration();
    var guard = new RenewingMongoLease(leases, clock, LEASE_NAME, ownerToken, duration, renewedOn);
    return guard::verifyHeld;
  }

  private String safeCategory(Exception failure) {
    if (failure instanceof ResponseStatusException status) {
      return "HTTP_" + status.getStatusCode().value();
    }
    if (failure instanceof java.net.http.HttpTimeoutException) {
      return "REMOTE_TIMEOUT";
    }
    if (failure instanceof java.io.IOException) {
      return "REMOTE_IO";
    }
    if (failure instanceof InterruptedException) {
      Thread.currentThread().interrupt();
      return "INTERRUPTED";
    }
    return "IMPORT_FAILED";
  }

  private YearMonth currentMonth() {
    var zone = ZoneId.of(properties.getRestaurantImport().getMonthly().getZone());
    return YearMonth.now(clock.withZone(zone));
  }

  private Optional<YearMonth> parseYearMonth(String value) {
    try {
      return value == null || value.isBlank() ? Optional.empty() : Optional.of(YearMonth.parse(value));
    } catch (Exception ignored) {
      return Optional.empty();
    }
  }
}
