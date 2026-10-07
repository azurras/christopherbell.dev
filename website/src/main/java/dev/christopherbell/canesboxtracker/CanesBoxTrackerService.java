package dev.christopherbell.canesboxtracker;

import dev.christopherbell.canesboxtracker.model.CanesBoxMetroPrice;
import dev.christopherbell.canesboxtracker.model.CanesBoxPriceQuality;
import dev.christopherbell.canesboxtracker.model.CanesBoxPriceSnapshot;
import dev.christopherbell.canesboxtracker.model.CanesBoxPriceSource;
import dev.christopherbell.canesboxtracker.model.CanesBoxTrackerHistory;
import dev.christopherbell.canesboxtracker.model.CanesBoxTrackerProperties;
import dev.christopherbell.canesboxtracker.model.CanesBoxWeeklyPriceDetail;
import dev.christopherbell.libs.lease.CollectorLeaseGuard;
import dev.christopherbell.libs.lease.ScheduledCollectorCoordinator;
import dev.christopherbell.libs.lease.ScheduledCollectorRunStatus;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Collects and exposes weekly Raising Canes Box Index price history.
 */
@Service
@Slf4j
public class CanesBoxTrackerService {
  public static final String LEASE_NAME = "canes-box-price-collection";
  private static final int AVERAGE_PRICE_SCALE = 2;
  private static final String PUBLIC_MISSING_DETAIL = "details unavailable";

  private final CanesBoxPriceSnapshotRepository repository;
  private final CanesBoxPriceClient priceClient;
  private final CanesBoxTrackerProperties properties;
  private final Clock clock;
  private final ScheduledCollectorCoordinator coordinator;

  /**
   * Creates the service without a collector lease, for tests that run collection directly.
   */
  public CanesBoxTrackerService(
      CanesBoxPriceSnapshotRepository repository,
      CanesBoxPriceClient priceClient,
      CanesBoxTrackerProperties properties,
      Clock clock
  ) {
    this(repository, priceClient, properties, clock, null);
  }

  @Autowired
  public CanesBoxTrackerService(
      CanesBoxPriceSnapshotRepository repository,
      CanesBoxPriceClient priceClient,
      CanesBoxTrackerProperties properties,
      Clock clock,
      ScheduledCollectorCoordinator coordinator
  ) {
    this.repository = repository;
    this.priceClient = priceClient;
    this.properties = properties;
    this.clock = clock;
    this.coordinator = coordinator;
  }

  /**
   * Runs the configured weekly collection, under the collector lease when one is configured.
   */
  @Scheduled(
      cron = "${canes-box-tracker.collection.cron}",
      zone = "${canes-box-tracker.collection.zone:America/Chicago}"
  )
  public void collectCurrentWeek() {
    if (!properties.isEnabled()) {
      return;
    }
    LocalDate weekStart = currentWeekStart();
    if (coordinator != null) {
      coordinator.run(LEASE_NAME, properties.getLeaseDuration(), leaseGuard -> {
        collectAndLogWeek(weekStart, leaseGuard);
        return null;
      });
      return;
    }
    collectAndLogWeek(weekStart, CollectorLeaseGuard.NONE);
  }

  /** Runs one startup catch-up when the last complete metro snapshot predates a due schedule. */
  @EventListener(
      value = ApplicationReadyEvent.class,
      condition = "!@environment.acceptsProfiles('deploy-smoke')")
  public void collectMissedWeeklyCollection() {
    if (!properties.isEnabled()) {
      return;
    }
    Optional<CanesBoxPriceSnapshot> latestCompleteSnapshot =
        repository.findTop60ByOrderByWeekStartDateDesc().stream()
            .filter(this::hasAllConfiguredMetroPrices)
            .findFirst();
    boolean collectionIsDue = latestCompleteSnapshot
        .map(snapshot -> isWeeklyCollectionOverdue(snapshot, clock.instant()))
        .orElse(true);
    if (!collectionIsDue) {
      return;
    }
    try {
      collectCurrentWeek();
    } catch (RuntimeException catchUpFailure) {
      log.warn(
          "Raising Canes Box Index startup catch-up failed; the next scheduled run will retry.",
          catchUpFailure);
    }
  }

  /**
   * Forces a current-week collection for an admin Back Office operation.
   *
   * @return chart/API detail for the saved snapshot
   * @throws ResponseStatusException 409 when another collection holds the lease
   */
  public CanesBoxWeeklyPriceDetail collectCurrentWeekForAdmin() {
    LocalDate weekStart = currentWeekStart();
    if (coordinator == null) {
      return detailOf(collectAndLogWeek(weekStart, CollectorLeaseGuard.NONE));
    }
    ScheduledCollectorCoordinator.Outcome<CanesBoxPriceSnapshot> leasedRun = coordinator.run(
        LEASE_NAME, properties.getLeaseDuration(), leaseGuard -> collectAndLogWeek(weekStart, leaseGuard));
    if (leasedRun.status() == ScheduledCollectorRunStatus.SKIPPED_LOCKED) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "Price collection is already running");
    }
    return detailOf(leasedRun.value());
  }

  /**
   * Collects and saves one weekly snapshot without a lease.
   *
   * @param weekStartDate Monday date represented by this snapshot
   * @return saved snapshot
   */
  CanesBoxPriceSnapshot collectWeek(LocalDate weekStartDate) {
    return collectWeek(weekStartDate, CollectorLeaseGuard.NONE);
  }

  /** Approves one provisional metro price so it can count toward the public index. */
  public CanesBoxWeeklyPriceDetail approveMetroPrice(String weekStartDate, String metroName, String reviewNote) {
    CanesBoxPriceSnapshot snapshot = findSnapshot(weekStartDate);
    findMetroPrice(snapshot, metroName).verify(reviewNote, clock.instant());
    recalculateSnapshot(snapshot);
    return detailOf(repository.save(snapshot));
  }

  /** Rejects one metro price so it shows as excluded and is never averaged. */
  public CanesBoxWeeklyPriceDetail rejectMetroPrice(String weekStartDate, String metroName, String reviewNote) {
    CanesBoxPriceSnapshot snapshot = findSnapshot(weekStartDate);
    findMetroPrice(snapshot, metroName).exclude(reviewNote, clock.instant());
    recalculateSnapshot(snapshot);
    return detailOf(repository.save(snapshot));
  }

  /**
   * Records an admin-verified current-week price from a manually checked source, replacing any
   * existing price for the metro.
   *
   * @throws IllegalArgumentException if the metro is not configured
   */
  public CanesBoxWeeklyPriceDetail recordManualVerifiedPrice(
      String metroName, BigDecimal price, String sourceUrl, String reviewNote) {
    LocalDate weekStart = currentWeekStart();
    CanesBoxPriceSnapshot snapshot =
        repository.findById(weekStart.toString()).orElseGet(() -> newSnapshot(weekStart));
    CanesBoxTrackerProperties.MetroTarget target = properties.getMetros().stream()
        .filter(candidate -> metroMatches(candidate.getMetroName(), metroName))
        .findFirst()
        .orElseThrow(() -> new IllegalArgumentException("Metro is not configured: " + metroName));
    Instant recordedOn = clock.instant();
    CanesBoxMetroPrice manualPrice = CanesBoxMetroPrice.success(
        target, price, recordedOn, CanesBoxPriceSource.MANUAL_VERIFIED, sourceUrl);
    manualPrice.verify(reviewNote, recordedOn);
    List<CanesBoxMetroPrice> metroPrices = new ArrayList<>(snapshot.getMetroPrices());
    metroPrices.removeIf(existing -> metroMatches(existing.getMetroName(), target.getMetroName()));
    metroPrices.add(manualPrice);
    snapshot.setMetroPrices(metroPrices);
    recalculateSnapshot(snapshot);
    return detailOf(repository.save(snapshot));
  }

  /** Returns chart-ready weekly history, oldest to newest, with the newest week as latest. */
  public CanesBoxTrackerHistory getHistory() {
    List<CanesBoxWeeklyPriceDetail> weeks = repository.findTop60ByOrderByWeekStartDateDesc().stream()
        .map(this::detailOf)
        .sorted(Comparator.comparing(CanesBoxWeeklyPriceDetail::weekStartDate))
        .toList();
    CanesBoxWeeklyPriceDetail latestWeek = weeks.isEmpty() ? null : weeks.getLast();
    return new CanesBoxTrackerHistory(latestWeek, weeks);
  }

  private boolean hasAllConfiguredMetroPrices(CanesBoxPriceSnapshot snapshot) {
    List<CanesBoxMetroPrice> metroPrices = snapshot.getMetroPrices();
    return metroPrices != null && properties.getMetros().stream().allMatch(target ->
        metroPrices.stream().anyMatch(metroPrice -> metroPrice != null
            && target.getMetroName().equals(metroPrice.getMetroName())));
  }

  private boolean isWeeklyCollectionOverdue(CanesBoxPriceSnapshot snapshot, Instant now) {
    if (snapshot.getCollectedOn() == null) {
      return true;
    }
    CronExpression collectionSchedule = CronExpression.parse(properties.getCollection().getCron());
    ZonedDateTime nextScheduledOn = collectionSchedule.next(snapshot.getCollectedOn().atZone(collectionZone()));
    return nextScheduledOn != null && !nextScheduledOn.toInstant().isAfter(now);
  }

  private CanesBoxPriceSnapshot collectAndLogWeek(LocalDate weekStart, CollectorLeaseGuard leaseGuard) {
    log.info("Raising Canes Box Index weekly collection started. Week: {}.", weekStart);
    CanesBoxPriceSnapshot snapshot = collectWeek(weekStart, leaseGuard);
    log.info(
        "Raising Canes Box Index weekly collection completed. Week: {}, successful metros: {}/{}, average price: {}.",
        snapshot.getWeekStartDate(),
        snapshot.getSuccessfulMetroCount(),
        snapshot.getTotalMetroCount(),
        snapshot.getAveragePrice());
    return snapshot;
  }

  /** Fetches every configured metro, checking the lease before each fetch and before saving. */
  private CanesBoxPriceSnapshot collectWeek(LocalDate weekStartDate, CollectorLeaseGuard leaseGuard) {
    List<CanesBoxMetroPrice> metroPrices = new ArrayList<>();
    for (CanesBoxTrackerProperties.MetroTarget target : properties.getMetros()) {
      leaseGuard.verifyHeld();
      metroPrices.add(fetchMetroPrice(target));
    }
    CanesBoxPriceSnapshot snapshot = new CanesBoxPriceSnapshot();
    snapshot.setId(weekStartDate.toString());
    snapshot.setWeekStartDate(weekStartDate.toString());
    snapshot.setCollectedOn(clock.instant());
    snapshot.setMetroPrices(metroPrices);
    recalculateSnapshot(snapshot, metroPrices.size());
    leaseGuard.verifyHeld();
    return repository.save(snapshot);
  }

  /** Records any client failure as a failed metro price so one metro cannot stop the week. */
  private CanesBoxMetroPrice fetchMetroPrice(CanesBoxTrackerProperties.MetroTarget target) {
    try {
      return priceClient.fetchBoxComboPrice(target);
    } catch (RuntimeException fetchFailure) {
      log.warn("Raising Canes Box Index price fetch failed for {}.", target.getMetroName(), fetchFailure);
      return CanesBoxMetroPrice.failure(target, fetchFailure.getMessage(), clock.instant());
    }
  }

  private BigDecimal averagePriceOf(List<CanesBoxMetroPrice> verifiedPrices) {
    if (verifiedPrices.isEmpty()) {
      return null;
    }
    BigDecimal total = verifiedPrices.stream()
        .map(CanesBoxMetroPrice::getPrice)
        .reduce(BigDecimal.ZERO, BigDecimal::add);
    return total.divide(BigDecimal.valueOf(verifiedPrices.size()), AVERAGE_PRICE_SCALE, RoundingMode.HALF_UP);
  }

  private CanesBoxPriceSnapshot findSnapshot(String weekStartDate) {
    return repository.findById(weekStartDate)
        .orElseThrow(() -> new IllegalArgumentException(
            "Raising Canes Box Index week was not found: " + weekStartDate));
  }

  private CanesBoxMetroPrice findMetroPrice(CanesBoxPriceSnapshot snapshot, String metroName) {
    return snapshot.getMetroPrices().stream()
        .filter(metroPrice -> metroMatches(metroPrice.getMetroName(), metroName))
        .findFirst()
        .orElseThrow(() -> new IllegalArgumentException("Metro was not found in snapshot: " + metroName));
  }

  private CanesBoxPriceSnapshot newSnapshot(LocalDate weekStart) {
    CanesBoxPriceSnapshot snapshot = new CanesBoxPriceSnapshot();
    snapshot.setId(weekStart.toString());
    snapshot.setWeekStartDate(weekStart.toString());
    snapshot.setCollectedOn(clock.instant());
    snapshot.setTotalMetroCount(properties.getMetros().size());
    return snapshot;
  }

  private void recalculateSnapshot(CanesBoxPriceSnapshot snapshot) {
    recalculateSnapshot(snapshot, Math.max(snapshot.getTotalMetroCount(), properties.getMetros().size()));
  }

  /**
   * Re-applies the public-menu plausibility floor, then recomputes the snapshot's counts and the
   * average of verified prices.
   */
  private void recalculateSnapshot(CanesBoxPriceSnapshot snapshot, int totalMetroCount) {
    List<CanesBoxMetroPrice> metroPrices = snapshot.getMetroPrices();
    for (CanesBoxMetroPrice metroPrice : metroPrices) {
      excludeImplausiblePublicMenuPrice(metroPrice);
    }
    List<CanesBoxMetroPrice> collectedPrices = metroPrices.stream()
        .filter(CanesBoxMetroPrice::hasCollectedPrice)
        .toList();
    List<CanesBoxMetroPrice> verifiedPrices = collectedPrices.stream()
        .filter(metroPrice -> metroPrice.effectiveQuality() == CanesBoxPriceQuality.VERIFIED)
        .toList();
    snapshot.setTotalMetroCount(Math.max(totalMetroCount, metroPrices.size()));
    snapshot.setSuccessfulMetroCount(collectedPrices.size());
    snapshot.setVerifiedMetroCount(verifiedPrices.size());
    snapshot.setProvisionalMetroCount(countWithQuality(metroPrices, CanesBoxPriceQuality.PROVISIONAL));
    snapshot.setExcludedMetroCount(countWithQuality(metroPrices, CanesBoxPriceQuality.EXCLUDED));
    snapshot.setAveragePrice(averagePriceOf(verifiedPrices));
  }

  private static int countWithQuality(List<CanesBoxMetroPrice> metroPrices, CanesBoxPriceQuality quality) {
    return (int) metroPrices.stream()
        .filter(metroPrice -> metroPrice.effectiveQuality() == quality)
        .count();
  }

  private void excludeImplausiblePublicMenuPrice(CanesBoxMetroPrice metroPrice) {
    boolean isPublicMenuPrice =
        metroPrice.isFromSource(CanesBoxPriceSource.PUBLIC_MENU) && metroPrice.getPrice() != null;
    if (isPublicMenuPrice && metroPrice.getPrice().compareTo(properties.getMinimumPublicMenuPrice()) < 0) {
      metroPrice.excludeAsImplausible(
          "Public menu fallback price was implausibly low: " + metroPrice.getPrice());
    }
  }

  /** Whether two metro names match ignoring case and punctuation, or one is a prefix of the other. */
  private static boolean metroMatches(String storedMetroName, String requestedMetroName) {
    String normalizedStored = lettersAndDigitsOf(storedMetroName);
    String normalizedRequested = lettersAndDigitsOf(requestedMetroName);
    return normalizedStored.equals(normalizedRequested)
        || normalizedStored.startsWith(normalizedRequested)
        || normalizedRequested.startsWith(normalizedStored);
  }

  private static String lettersAndDigitsOf(String text) {
    return String.valueOf(text)
        .toLowerCase(Locale.ROOT)
        .replaceAll("[^a-z0-9]+", "");
  }

  /**
   * Builds the public week detail. Stored snapshots are recalculated in memory first, so current
   * plausibility and quality rules apply to old weeks; nothing is saved.
   */
  private CanesBoxWeeklyPriceDetail detailOf(CanesBoxPriceSnapshot snapshot) {
    if (!snapshot.getMetroPrices().isEmpty()) {
      recalculateSnapshot(snapshot);
    }
    return new CanesBoxWeeklyPriceDetail(
        snapshot.getWeekStartDate(),
        snapshot.getCollectedOn(),
        snapshot.getAveragePrice(),
        snapshot.getCurrency(),
        snapshot.getSuccessfulMetroCount(),
        snapshot.getTotalMetroCount(),
        snapshot.getVerifiedMetroCount(),
        snapshot.getProvisionalMetroCount(),
        snapshot.getExcludedMetroCount(),
        snapshot.getMetroPrices().stream()
            .map(metroPrice -> metroPrice.copyWithFailureReason(publicFailureReason(metroPrice.getFailureReason())))
            .toList());
  }

  /** Replaces a bare {@code null} left in legacy failure messages with readable text. */
  private static String publicFailureReason(String failureReason) {
    if (failureReason == null || failureReason.isBlank()) {
      return failureReason;
    }
    return failureReason.replaceAll("(?i)\\bnull\\b", PUBLIC_MISSING_DETAIL);
  }

  private ZoneId collectionZone() {
    return ZoneId.of(properties.getCollection().getZone());
  }

  private LocalDate currentWeekStart() {
    return LocalDate.now(clock.withZone(collectionZone()))
        .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
  }
}
