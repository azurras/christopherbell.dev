package dev.christopherbell.vehicle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import dev.christopherbell.vehicle.model.VehicleProperties;
import dev.christopherbell.vehicle.nhtsa.NhtsaVinImportStateRepository;
import dev.christopherbell.vehicle.nhtsa.model.NhtsaVinImportState;
import dev.christopherbell.vehicle.randomvin.RandomVinImportStateRepository;
import dev.christopherbell.vehicle.randomvin.model.RandomVinImportState;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class VehicleDataCollectionStateServiceTest {
  private static final Instant NOW = Instant.parse("2026-05-18T15:00:00Z");

  @Mock private NhtsaVinImportStateRepository nhtsaVinImportStateRepository;
  @Mock private RandomVinImportStateRepository randomVinImportStateRepository;

  @Test
  @DisplayName("State uses persisted import state when both records exist")
  void getState_whenPersistedStateExists_returnsPersistedState() {
    var properties = properties();
    var nhtsa = NhtsaVinImportState.builder().id("nhtsa-state").callsToday(7).build();
    var randomVin = RandomVinImportState.builder().id("randomvin-state").callsToday(3).build();
    var service = service(properties);

    when(nhtsaVinImportStateRepository.findById(eq("nhtsa-state"))).thenReturn(Optional.of(nhtsa));
    when(randomVinImportStateRepository.findById(eq("randomvin-state"))).thenReturn(Optional.of(randomVin));

    var result = service.getState();

    assertSame(nhtsa, result.nhtsa());
    assertSame(randomVin, result.randomVin());
    verify(nhtsaVinImportStateRepository).findById(eq("nhtsa-state"));
    verify(randomVinImportStateRepository).findById(eq("randomvin-state"));
    verifyNoMoreInteractions(nhtsaVinImportStateRepository, randomVinImportStateRepository);
  }

  @Test
  @DisplayName("State creates defaults from properties when records are missing")
  void getState_whenPersistedStateMissing_returnsDefaults() {
    var properties = properties();
    var service = service(properties);

    when(nhtsaVinImportStateRepository.findById(eq("nhtsa-state"))).thenReturn(Optional.empty());
    when(randomVinImportStateRepository.findById(eq("randomvin-state"))).thenReturn(Optional.empty());

    var result = service.getState();

    assertEquals("nhtsa-state", result.nhtsa().getId());
    assertEquals(LocalDate.of(2026, 5, 18), result.nhtsa().getCallsOnDate());
    assertEquals(0, result.nhtsa().getCallsToday());
    assertEquals(0L, result.nhtsa().getLifetimeCalls());
    assertEquals(0L, result.nhtsa().getLifetimeVinsProcessed());
    assertEquals(0, result.nhtsa().getVinsProcessedToday());
    assertEquals("NHTSA note", result.nhtsa().getNotes());

    assertEquals("randomvin-state", result.randomVin().getId());
    assertEquals(LocalDate.of(2026, 5, 18), result.randomVin().getCallsOnDate());
    assertEquals(0, result.randomVin().getCallsToday());
    assertEquals(0L, result.randomVin().getLifetimeCalls());
    assertEquals(0L, result.randomVin().getLifetimeVinsProcessed());
    assertEquals(0, result.randomVin().getVinsProcessedToday());
    assertEquals("RandomVIN note", result.randomVin().getNotes());
  }

  private VehicleDataCollectionStateService service(VehicleProperties properties) {
    return new VehicleDataCollectionStateService(
        Clock.fixed(NOW, ZoneOffset.UTC),
        nhtsaVinImportStateRepository,
        randomVinImportStateRepository,
        properties);
  }

  private VehicleProperties properties() {
    var properties = new VehicleProperties();
    properties.getNhtsaVin().setStateId("nhtsa-state");
    properties.getNhtsaVin().setStateNote("NHTSA note");
    properties.getRandomVin().setStateId("randomvin-state");
    properties.getRandomVin().setStateNote("RandomVIN note");
    return properties;
  }
}
