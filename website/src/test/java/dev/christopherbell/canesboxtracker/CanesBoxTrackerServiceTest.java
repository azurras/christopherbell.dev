package dev.christopherbell.canesboxtracker;

import dev.christopherbell.canesboxtracker.model.CanesBoxMetroPrice;
import dev.christopherbell.canesboxtracker.model.CanesBoxPriceSnapshot;
import dev.christopherbell.canesboxtracker.model.CanesBoxTrackerProperties;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("Raising Canes Box Index service")
class CanesBoxTrackerServiceTest {

  @Test
  void collectWeekAveragesSuccessfulMetroPricesAndStoresFailures() throws Exception {
    var repository = mock(CanesBoxPriceSnapshotRepository.class);
    when(repository.save(any(CanesBoxPriceSnapshot.class))).thenAnswer(invocation -> invocation.getArgument(0));
    var properties = properties(target("Dallas-Fort Worth", "101"), target("Houston", "202"), target("Phoenix", "303"));
    var client = new StubCanesBoxPriceClient(List.of(
        CanesBoxMetroPrice.success(properties.getMetros().get(0), new BigDecimal("12.99"), Instant.parse("2026-06-01T12:00:00Z")),
        CanesBoxMetroPrice.failure(properties.getMetros().get(1), "HTTP 403"),
        CanesBoxMetroPrice.success(properties.getMetros().get(2), new BigDecimal("13.49"), Instant.parse("2026-06-01T12:00:00Z"))
    ));
    var service = new CanesBoxTrackerService(
        repository,
        client,
        properties,
        Clock.fixed(Instant.parse("2026-06-04T12:00:00Z"), ZoneId.of("America/Chicago")));

    var snapshot = service.collectWeek(LocalDate.parse("2026-06-01"));

    assertEquals("2026-06-01", snapshot.getId());
    assertEquals(new BigDecimal("13.24"), snapshot.getAveragePrice());
    assertEquals(2, snapshot.getSuccessfulMetroCount());
    assertEquals(3, snapshot.getTotalMetroCount());
    assertEquals("SUCCESS", snapshot.getMetroPrices().get(0).getStatus());
    assertEquals("FAILED", snapshot.getMetroPrices().get(1).getStatus());
    assertNull(snapshot.getMetroPrices().get(1).getPrice());
    verify(repository).save(snapshot);
  }

  @Test
  void collectWeekOnlyAveragesVerifiedMetroPrices() {
    var repository = mock(CanesBoxPriceSnapshotRepository.class);
    when(repository.save(any(CanesBoxPriceSnapshot.class))).thenAnswer(invocation -> invocation.getArgument(0));
    var properties = properties(target("Dallas-Fort Worth", "101"), target("Houston", "202"));
    var client = new StubCanesBoxPriceClient(List.of(
        CanesBoxMetroPrice.success(properties.getMetros().get(0), new BigDecimal("12.99"), Instant.parse("2026-06-01T12:00:00Z"), "OFFICIAL_API", "https://official.example/dallas"),
        CanesBoxMetroPrice.success(properties.getMetros().get(1), new BigDecimal("11.50"), Instant.parse("2026-06-01T12:00:00Z"), "PUBLIC_MENU", "https://public.example/houston")
    ));
    var service = new CanesBoxTrackerService(
        repository,
        client,
        properties,
        Clock.fixed(Instant.parse("2026-06-04T12:00:00Z"), ZoneId.of("America/Chicago")));

    var snapshot = service.collectWeek(LocalDate.parse("2026-06-01"));

    assertEquals(new BigDecimal("12.99"), snapshot.getAveragePrice());
    assertEquals(1, snapshot.getVerifiedMetroCount());
    assertEquals(1, snapshot.getProvisionalMetroCount());
    assertEquals(0, snapshot.getExcludedMetroCount());
    assertEquals("VERIFIED", snapshot.getMetroPrices().get(0).getQualityStatus());
    assertEquals("PROVISIONAL", snapshot.getMetroPrices().get(1).getQualityStatus());
  }

  @Test
  void approveMetroPricePromotesProvisionalPriceAndRecalculatesAverage() {
    var repository = mock(CanesBoxPriceSnapshotRepository.class);
    var target = target("Dallas-Fort Worth", "101");
    var snapshot = new CanesBoxPriceSnapshot();
    snapshot.setId("2026-06-01");
    snapshot.setWeekStartDate("2026-06-01");
    snapshot.setMetroPrices(List.of(
        CanesBoxMetroPrice.success(target, new BigDecimal("11.49"), Instant.parse("2026-06-01T12:00:00Z"), "PUBLIC_MENU", "https://public.example/dallas")
    ));
    when(repository.findById("2026-06-01")).thenReturn(Optional.of(snapshot));
    when(repository.save(any(CanesBoxPriceSnapshot.class))).thenAnswer(invocation -> invocation.getArgument(0));
    var service = new CanesBoxTrackerService(
        repository,
        targetPrice -> CanesBoxMetroPrice.failure(targetPrice, "unused"),
        properties(target),
        Clock.fixed(Instant.parse("2026-06-04T12:00:00Z"), ZoneId.of("America/Chicago")));

    var detail = service.approveMetroPrice("2026-06-01", "Dallas-Fort Worth", "Looks right.");

    assertEquals(new BigDecimal("11.49"), detail.averagePrice());
    assertEquals(1, detail.verifiedMetroCount());
    assertEquals(0, detail.provisionalMetroCount());
    assertEquals("VERIFIED", detail.metroPrices().get(0).getQualityStatus());
    assertEquals("Looks right.", detail.metroPrices().get(0).getReviewNote());
  }

  @Test
  void manualVerifiedPriceCreatesVerifiedDatapointForCurrentWeek() {
    var repository = mock(CanesBoxPriceSnapshotRepository.class);
    var target = target("Dallas-Fort Worth", "101");
    when(repository.findById("2026-06-01")).thenReturn(Optional.empty());
    when(repository.save(any(CanesBoxPriceSnapshot.class))).thenAnswer(invocation -> invocation.getArgument(0));
    var service = new CanesBoxTrackerService(
        repository,
        targetPrice -> CanesBoxMetroPrice.failure(targetPrice, "unused"),
        properties(target),
        Clock.fixed(Instant.parse("2026-06-04T12:00:00Z"), ZoneId.of("America/Chicago")));

    var detail = service.recordManualVerifiedPrice(
        "Dallas-Fort Worth",
        new BigDecimal("12.49"),
        "https://receipt.example/dallas",
        "Receipt checked.");

    assertEquals("2026-06-01", detail.weekStartDate());
    assertEquals(new BigDecimal("12.49"), detail.averagePrice());
    assertEquals(1, detail.verifiedMetroCount());
    assertEquals("MANUAL_VERIFIED", detail.metroPrices().get(0).getSourceName());
    assertEquals("VERIFIED", detail.metroPrices().get(0).getQualityStatus());
  }

  @Test
  void collectWeekStoresFailedMetroWhenClientThrows() {
    var repository = mock(CanesBoxPriceSnapshotRepository.class);
    when(repository.save(any(CanesBoxPriceSnapshot.class))).thenAnswer(invocation -> invocation.getArgument(0));
    var properties = properties(target("Dallas-Fort Worth", "101"));
    var service = new CanesBoxTrackerService(
        repository,
        target -> {
          throw new IllegalStateException("network blocked");
        },
        properties,
        Clock.fixed(Instant.parse("2026-06-04T12:00:00Z"), ZoneId.of("America/Chicago")));

    var snapshot = service.collectWeek(LocalDate.parse("2026-06-01"));

    assertNull(snapshot.getAveragePrice());
    assertEquals(0, snapshot.getSuccessfulMetroCount());
    assertEquals(1, snapshot.getTotalMetroCount());
    assertEquals("FAILED", snapshot.getMetroPrices().get(0).getStatus());
    assertEquals("network blocked", snapshot.getMetroPrices().get(0).getFailureReason());
  }

  @Test
  void historyReturnsRecentSnapshotsAscendingByWeek() {
    var repository = mock(CanesBoxPriceSnapshotRepository.class);
    when(repository.findTop60ByOrderByWeekStartDateDesc()).thenReturn(List.of(
        snapshot("2026-06-08", "13.25"),
        snapshot("2026-06-01", "12.95")
    ));
    var service = new CanesBoxTrackerService(
        repository,
        target -> CanesBoxMetroPrice.failure(target, "unused"),
        properties(target("Dallas-Fort Worth", "101")),
        Clock.fixed(Instant.parse("2026-06-09T12:00:00Z"), ZoneId.of("America/Chicago")));

    var history = service.getHistory();

    assertEquals(2, history.weeks().size());
    assertEquals("2026-06-01", history.weeks().get(0).weekStartDate());
    assertEquals(new BigDecimal("12.95"), history.weeks().get(0).averagePrice());
    assertEquals("2026-06-08", history.latest().weekStartDate());
  }

  @Test
  void historyRecalculatesStoredSnapshotsWithMetroQualityData() {
    var repository = mock(CanesBoxPriceSnapshotRepository.class);
    var target = target("Dallas-Fort Worth", "101");
    var snapshot = new CanesBoxPriceSnapshot();
    snapshot.setId("2026-06-01");
    snapshot.setWeekStartDate("2026-06-01");
    snapshot.setAveragePrice(new BigDecimal("11.49"));
    snapshot.setSuccessfulMetroCount(1);
    snapshot.setTotalMetroCount(1);
    snapshot.setMetroPrices(List.of(
        CanesBoxMetroPrice.success(target, new BigDecimal("11.49"), Instant.parse("2026-06-01T12:00:00Z"), "PUBLIC_MENU", "https://public.example/dallas")
    ));
    when(repository.findTop60ByOrderByWeekStartDateDesc()).thenReturn(List.of(snapshot));
    var service = new CanesBoxTrackerService(
        repository,
        candidate -> CanesBoxMetroPrice.failure(candidate, "unused"),
        properties(target),
        Clock.fixed(Instant.parse("2026-06-09T12:00:00Z"), ZoneId.of("America/Chicago")));

    var history = service.getHistory();

    assertNull(history.latest().averagePrice());
    assertEquals(0, history.latest().verifiedMetroCount());
    assertEquals(1, history.latest().provisionalMetroCount());
  }

  @Test
  void historyExcludesStoredImplausiblyLowPublicMenuPrices() {
    var repository = mock(CanesBoxPriceSnapshotRepository.class);
    var target = target("Dallas-Fort Worth", "101");
    var snapshot = new CanesBoxPriceSnapshot();
    snapshot.setId("2026-06-01");
    snapshot.setWeekStartDate("2026-06-01");
    snapshot.setAveragePrice(new BigDecimal("7.80"));
    snapshot.setSuccessfulMetroCount(1);
    snapshot.setTotalMetroCount(1);
    snapshot.setMetroPrices(List.of(
        CanesBoxMetroPrice.success(target, new BigDecimal("7.80"), Instant.parse("2026-06-01T12:00:00Z"), "PUBLIC_MENU", "https://public.example/dallas")
    ));
    when(repository.findTop60ByOrderByWeekStartDateDesc()).thenReturn(List.of(snapshot));
    var service = new CanesBoxTrackerService(
        repository,
        candidate -> CanesBoxMetroPrice.failure(candidate, "unused"),
        properties(target),
        Clock.fixed(Instant.parse("2026-06-09T12:00:00Z"), ZoneId.of("America/Chicago")));

    var history = service.getHistory();

    var stalePrice = history.latest().metroPrices().get(0);
    assertNull(history.latest().averagePrice());
    assertEquals(0, history.latest().provisionalMetroCount());
    assertEquals(1, history.latest().excludedMetroCount());
    assertEquals("EXCLUDED", stalePrice.getQualityStatus());
    assertEquals("FAILED", stalePrice.getStatus());
    assertNull(stalePrice.getPrice());
    assertTrue(stalePrice.getFailureReason().contains("implausibly low"));
  }

  @Test
  void collectCurrentWeekForAdminUsesCurrentCollectionWeek() {
    var repository = mock(CanesBoxPriceSnapshotRepository.class);
    when(repository.save(any(CanesBoxPriceSnapshot.class))).thenAnswer(invocation -> invocation.getArgument(0));
    var properties = properties(target("Dallas-Fort Worth", "101"));
    var service = new CanesBoxTrackerService(
        repository,
        target -> CanesBoxMetroPrice.success(target, new BigDecimal("12.99"), Instant.parse("2026-06-04T12:00:00Z")),
        properties,
        Clock.fixed(Instant.parse("2026-06-04T12:00:00Z"), ZoneId.of("America/Chicago")));

    var detail = service.collectCurrentWeekForAdmin();

    assertEquals("2026-06-01", detail.weekStartDate());
    assertEquals(new BigDecimal("12.99"), detail.averagePrice());
    assertEquals(1, detail.successfulMetroCount());
    assertEquals(1, detail.totalMetroCount());
  }

  private CanesBoxTrackerProperties properties(CanesBoxTrackerProperties.MetroTarget... targets) {
    var properties = new CanesBoxTrackerProperties();
    properties.setEnabled(true);
    properties.setMetros(new ArrayList<>(List.of(targets)));
    return properties;
  }

  private CanesBoxTrackerProperties.MetroTarget target(String metroName, String restaurantRef) {
    var target = new CanesBoxTrackerProperties.MetroTarget();
    target.setMetroName(metroName);
    target.setCity(metroName.split("-")[0]);
    target.setState("TX");
    target.setRestaurantRef(restaurantRef);
    target.setRestaurantName(metroName + " Cane's");
    target.setAddress("1 Main St");
    target.setSourceUrl("https://order.raisingcanes.com/location/" + restaurantRef);
    return target;
  }

  private CanesBoxPriceSnapshot snapshot(String weekStartDate, String averagePrice) {
    var snapshot = new CanesBoxPriceSnapshot();
    snapshot.setId(weekStartDate);
    snapshot.setWeekStartDate(weekStartDate);
    snapshot.setAveragePrice(new BigDecimal(averagePrice));
    snapshot.setSuccessfulMetroCount(15);
    snapshot.setTotalMetroCount(15);
    snapshot.setMetroPrices(List.of());
    return snapshot;
  }

  private static class StubCanesBoxPriceClient implements CanesBoxPriceClient {
    private final List<CanesBoxMetroPrice> prices;
    private int index;

    StubCanesBoxPriceClient(List<CanesBoxMetroPrice> prices) {
      this.prices = prices;
    }

    @Override
    public CanesBoxMetroPrice fetchBoxComboPrice(CanesBoxTrackerProperties.MetroTarget target) {
      return prices.get(index++);
    }
  }
}
