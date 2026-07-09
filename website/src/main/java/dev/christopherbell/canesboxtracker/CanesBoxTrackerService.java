package dev.christopherbell.canesboxtracker;

import dev.christopherbell.canesboxtracker.model.CanesBoxMetroPrice;
import dev.christopherbell.canesboxtracker.model.CanesBoxPriceSnapshot;
import dev.christopherbell.canesboxtracker.model.CanesBoxTrackerHistory;
import dev.christopherbell.canesboxtracker.model.CanesBoxTrackerProperties;
import dev.christopherbell.canesboxtracker.model.CanesBoxWeeklyPriceDetail;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Collects and exposes weekly Raising Canes Box Index price history.
 */
@Service
@Slf4j
public class CanesBoxTrackerService {
  private final CanesBoxPriceSnapshotRepository repository;
  private final CanesBoxPriceClient priceClient;
  private final CanesBoxTrackerProperties properties;
  private final Clock clock;

  /**
   * Creates the Raising Canes Box Index service.
   */
  public CanesBoxTrackerService(
      CanesBoxPriceSnapshotRepository repository,
      CanesBoxPriceClient priceClient,
      CanesBoxTrackerProperties properties,
      Clock clock
  ) {
    this.repository = repository;
    this.priceClient = priceClient;
    this.properties = properties;
    this.clock = clock;
  }

  /**
   * Runs the configured weekly Raising Canes Box Index collection job.
   */
  @Scheduled(
      cron = "${canes-box-tracker.collection.cron:0 0 6 * * MON}",
      zone = "${canes-box-tracker.collection.zone:America/Chicago}"
  )
  public void collectCurrentWeek() {
    if (!properties.isEnabled()) {
      return;
    }
    var weekStart = currentWeekStart();
    log.info("Raising Canes Box Index weekly collection started. Week: {}.", weekStart);
    var snapshot = collectWeek(weekStart);
    log.info(
        "Raising Canes Box Index weekly collection completed. Week: {}, successful metros: {}/{}, average price: {}.",
        snapshot.getWeekStartDate(),
        snapshot.getSuccessfulMetroCount(),
        snapshot.getTotalMetroCount(),
        snapshot.getAveragePrice());
  }

  /**
   * Forces a current-week collection for an admin Back Office operation.
   *
   * @return chart/API detail for the saved snapshot
   */
  public CanesBoxWeeklyPriceDetail collectCurrentWeekForAdmin() {
    var weekStart = currentWeekStart();
    log.info("Raising Canes Box Index admin collection started. Week: {}.", weekStart);
    var snapshot = collectWeek(weekStart);
    log.info(
        "Raising Canes Box Index admin collection completed. Week: {}, successful metros: {}/{}, average price: {}.",
        snapshot.getWeekStartDate(),
        snapshot.getSuccessfulMetroCount(),
        snapshot.getTotalMetroCount(),
        snapshot.getAveragePrice());
    return toDetail(snapshot);
  }

  /**
   * Collects one weekly snapshot.
   *
   * @param weekStartDate Monday date represented by this snapshot
   * @return saved snapshot
   */
  CanesBoxPriceSnapshot collectWeek(LocalDate weekStartDate) {
    var prices = properties.getMetros().stream()
        .map(this::fetchMetroPrice)
        .toList();
    var snapshot = new CanesBoxPriceSnapshot();
    snapshot.setId(weekStartDate.toString());
    snapshot.setWeekStartDate(weekStartDate.toString());
    snapshot.setCollectedOn(Instant.now(clock));
    snapshot.setMetroPrices(prices);
    recalculateSnapshot(snapshot, prices.size());
    return repository.save(snapshot);
  }

  /**
   * Approves one provisional metro datapoint so it can participate in the public index.
   */
  public CanesBoxWeeklyPriceDetail approveMetroPrice(String weekStartDate, String metroName, String reviewNote) {
    var snapshot = findSnapshot(weekStartDate);
    var metroPrice = findMetroPrice(snapshot, metroName);
    metroPrice.verify(reviewNote);
    metroPrice.setReviewedOn(Instant.now(clock));
    recalculateSnapshot(snapshot);
    return toDetail(repository.save(snapshot));
  }

  /**
   * Rejects one metro datapoint and excludes it from index calculations.
   */
  public CanesBoxWeeklyPriceDetail rejectMetroPrice(String weekStartDate, String metroName, String reviewNote) {
    var snapshot = findSnapshot(weekStartDate);
    var metroPrice = findMetroPrice(snapshot, metroName);
    metroPrice.exclude(reviewNote);
    metroPrice.setReviewedOn(Instant.now(clock));
    recalculateSnapshot(snapshot);
    return toDetail(repository.save(snapshot));
  }

  /**
   * Records an admin-verified current-week price from a manually checked source.
   */
  public CanesBoxWeeklyPriceDetail recordManualVerifiedPrice(
      String metroName,
      BigDecimal price,
      String sourceUrl,
      String reviewNote
  ) {
    var weekStart = currentWeekStart();
    var snapshot = repository.findById(weekStart.toString()).orElseGet(() -> newSnapshot(weekStart));
    var target = properties.getMetros().stream()
        .filter(candidate -> metroMatches(candidate.getMetroName(), metroName))
        .findFirst()
        .orElseThrow(() -> new IllegalArgumentException("Metro is not configured: " + metroName));
    var collectedOn = Instant.now(clock);
    var metroPrice = CanesBoxMetroPrice.success(target, price, collectedOn, "MANUAL_VERIFIED", sourceUrl);
    metroPrice.verify(reviewNote);
    metroPrice.setReviewedOn(collectedOn);

    var metroPrices = new ArrayList<>(snapshot.getMetroPrices());
    metroPrices.removeIf(existing -> metroMatches(existing.getMetroName(), target.getMetroName()));
    metroPrices.add(metroPrice);
    snapshot.setMetroPrices(metroPrices);
    recalculateSnapshot(snapshot);
    return toDetail(repository.save(snapshot));
  }

  /**
   * Gets chart-ready weekly history sorted from oldest to newest.
   */
  public CanesBoxTrackerHistory getHistory() {
    var weeks = repository.findTop60ByOrderByWeekStartDateDesc().stream()
        .map(this::toDetail)
        .sorted(Comparator.comparing(CanesBoxWeeklyPriceDetail::weekStartDate))
        .toList();
    var latest = weeks.isEmpty() ? null : weeks.get(weeks.size() - 1);
    return new CanesBoxTrackerHistory(latest, weeks);
  }

  private BigDecimal average(List<CanesBoxMetroPrice> successes) {
    if (successes.isEmpty()) {
      return null;
    }
    var total = successes.stream()
        .map(CanesBoxMetroPrice::getPrice)
        .reduce(BigDecimal.ZERO, BigDecimal::add);
    return total.divide(BigDecimal.valueOf(successes.size()), 2, RoundingMode.HALF_UP);
  }

  private CanesBoxPriceSnapshot findSnapshot(String weekStartDate) {
    return repository.findById(weekStartDate)
        .orElseThrow(() -> new IllegalArgumentException("Raising Canes Box Index week was not found: " + weekStartDate));
  }

  private CanesBoxMetroPrice findMetroPrice(CanesBoxPriceSnapshot snapshot, String metroName) {
    return snapshot.getMetroPrices().stream()
        .filter(price -> metroMatches(price.getMetroName(), metroName))
        .findFirst()
        .orElseThrow(() -> new IllegalArgumentException("Metro was not found in snapshot: " + metroName));
  }

  private CanesBoxPriceSnapshot newSnapshot(LocalDate weekStart) {
    var snapshot = new CanesBoxPriceSnapshot();
    snapshot.setId(weekStart.toString());
    snapshot.setWeekStartDate(weekStart.toString());
    snapshot.setCollectedOn(Instant.now(clock));
    snapshot.setTotalMetroCount(properties.getMetros().size());
    return snapshot;
  }

  private void recalculateSnapshot(CanesBoxPriceSnapshot snapshot) {
    recalculateSnapshot(snapshot, Math.max(snapshot.getTotalMetroCount(), properties.getMetros().size()));
  }

  private void recalculateSnapshot(CanesBoxPriceSnapshot snapshot, int totalMetroCount) {
    var prices = snapshot.getMetroPrices();
    prices.forEach(this::excludeImplausiblePublicMenuPrice);
    var successes = prices.stream()
        .filter(price -> "SUCCESS".equals(price.getStatus()))
        .filter(price -> price.getPrice() != null)
        .toList();
    var verified = successes.stream()
        .filter(price -> "VERIFIED".equals(normalizedQualityStatus(price)))
        .toList();
    snapshot.setTotalMetroCount(Math.max(totalMetroCount, prices.size()));
    snapshot.setSuccessfulMetroCount(successes.size());
    snapshot.setVerifiedMetroCount(verified.size());
    snapshot.setProvisionalMetroCount((int) prices.stream()
        .filter(price -> "PROVISIONAL".equals(normalizedQualityStatus(price)))
        .count());
    snapshot.setExcludedMetroCount((int) prices.stream()
        .filter(price -> "EXCLUDED".equals(normalizedQualityStatus(price)))
        .count());
    snapshot.setAveragePrice(average(verified));
  }

  private void excludeImplausiblePublicMenuPrice(CanesBoxMetroPrice price) {
    if (!"PUBLIC_MENU".equals(price.getSourceName()) || price.getPrice() == null) {
      return;
    }
    if (price.getPrice().compareTo(properties.getMinimumPublicMenuPrice()) >= 0) {
      return;
    }
    price.setFailureReason("Public menu fallback price was implausibly low: " + price.getPrice());
    price.setPrice(null);
    price.setStatus("FAILED");
    price.setQualityStatus("EXCLUDED");
    price.setConfidenceLevel("NONE");
  }

  private String normalizedQualityStatus(CanesBoxMetroPrice price) {
    if (price.getQualityStatus() != null && !price.getQualityStatus().isBlank()) {
      return price.getQualityStatus();
    }
    if ("SUCCESS".equals(price.getStatus()) && price.getPrice() != null) {
      return "VERIFIED";
    }
    return "EXCLUDED";
  }

  private boolean metroMatches(String actual, String requested) {
    var normalizedActual = normalize(actual);
    var normalizedRequested = normalize(requested);
    return normalizedActual.equals(normalizedRequested)
        || normalizedActual.startsWith(normalizedRequested)
        || normalizedRequested.startsWith(normalizedActual);
  }

  private String normalize(String value) {
    return String.valueOf(value)
        .toLowerCase(Locale.ROOT)
        .replaceAll("[^a-z0-9]+", "");
  }

  private CanesBoxMetroPrice fetchMetroPrice(CanesBoxTrackerProperties.MetroTarget target) {
    try {
      return priceClient.fetchBoxComboPrice(target);
    } catch (Exception e) {
      return CanesBoxMetroPrice.failure(target, e.getMessage());
    }
  }

  private CanesBoxWeeklyPriceDetail toDetail(CanesBoxPriceSnapshot snapshot) {
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
        snapshot.getMetroPrices());
  }

  private ZoneId collectionZone() {
    return ZoneId.of(properties.getCollection().getZone());
  }

  private LocalDate currentWeekStart() {
    return LocalDate.now(clock.withZone(collectionZone())).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
  }
}
