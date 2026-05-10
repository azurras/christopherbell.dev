package dev.christopherbell.vehicle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

import dev.christopherbell.vehicle.model.RandomVinImportState;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;

/**
 * Unit tests for {@link RandomVinImportService}.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("RandomVinImportService unit tests")
public class RandomVinImportServiceTest {
  private static final Instant IMPORTED_ON = Instant.parse("2026-01-18T02:30:00.809Z");

  @Mock private RandomVinClient randomVinClient;
  @Mock private RandomVinImportStateRepository randomVinImportStateRepository;
  @Mock private VehicleRepository vehicleRepository;
  private RandomVinImportService randomVinImportService;

  @BeforeEach
  public void setUp() {
    randomVinImportService = new RandomVinImportService(
        randomVinClient,
        randomVinImportStateRepository,
        vehicleRepository,
        Clock.fixed(IMPORTED_ON, ZoneOffset.UTC),
        true
    );
  }

  @Test
  @DisplayName("Skips imports when RandomVIN collection is disabled")
  public void testImportRandomVin_whenDisabled_doesNotFetchOrSave() {
    randomVinImportService = new RandomVinImportService(
        randomVinClient,
        randomVinImportStateRepository,
        vehicleRepository,
        Clock.fixed(IMPORTED_ON, ZoneOffset.UTC),
        false
    );

    randomVinImportService.importRandomVin();

    verifyNoInteractions(randomVinClient, randomVinImportStateRepository, vehicleRepository);
  }

  @Test
  @DisplayName("Imports a normalized VIN")
  public void testImportRandomVin_whenVinIsValid_savesVehicle() throws Exception {
    givenNoImportState();
    when(randomVinClient.getVin()).thenReturn("  2fmdk4jcxebb62196\n");

    randomVinImportService.importRandomVin();

    verify(randomVinImportStateRepository).findById("randomvin");
    verify(randomVinImportStateRepository, times(2)).save(argThat(state -> {
      assertEquals("randomvin", state.getId());
      assertEquals(1, state.getCallsToday());
      assertEquals(1L, state.getLifetimeCalls());
      assertEquals(1, state.getVinsProcessedToday());
      assertEquals(1L, state.getLifetimeVinsProcessed());
      assertEquals(IMPORTED_ON, state.getLastAttemptOn());
      assertEquals("VIN data sourced from randomvin.com", state.getNotes());
      return true;
    }));
    verify(vehicleRepository).existsByVin("2FMDK4JCXEBB62196");
    verify(vehicleRepository).save(argThat(vehicle -> {
      assertEquals("2FMDK4JCXEBB62196", vehicle.getVin());
      assertEquals(IMPORTED_ON, vehicle.getCreatedOn());
      assertEquals(IMPORTED_ON, vehicle.getLastUpdatedOn());
      assertNull(vehicle.getNotes());
      return vehicle.getId() != null;
    }));
    verifyNoMoreInteractions(vehicleRepository);
  }

  @Test
  @DisplayName("Skips RandomVIN calls when the daily cap has been reached")
  public void testImportRandomVin_whenDailyCapReached_doesNotFetchOrSave() {
    when(randomVinImportStateRepository.findById("randomvin")).thenReturn(Optional.of(
        RandomVinImportState.builder()
            .id("randomvin")
            .callsOnDate(IMPORTED_ON.atZone(ZoneOffset.UTC).toLocalDate())
            .callsToday(50)
            .build()
    ));

    randomVinImportService.importRandomVin();

    verify(randomVinImportStateRepository).findById("randomvin");
    verifyNoMoreInteractions(randomVinImportStateRepository);
    verifyNoInteractions(randomVinClient, vehicleRepository);
  }

  @Test
  @DisplayName("Skips RandomVIN calls during a cooldown")
  public void testImportRandomVin_whenCoolingDown_doesNotFetchOrSave() {
    when(randomVinImportStateRepository.findById("randomvin")).thenReturn(Optional.of(
        RandomVinImportState.builder()
            .id("randomvin")
            .callsOnDate(IMPORTED_ON.atZone(ZoneOffset.UTC).toLocalDate())
            .callsToday(10)
            .disabledUntil(IMPORTED_ON.plusSeconds(3600))
            .build()
    ));

    randomVinImportService.importRandomVin();

    verify(randomVinImportStateRepository).findById("randomvin");
    verifyNoMoreInteractions(randomVinImportStateRepository);
    verifyNoInteractions(randomVinClient, vehicleRepository);
  }

  @Test
  @DisplayName("Permanently disables RandomVIN collection for 403 responses")
  public void testImportRandomVin_whenForbidden_permanentlyDisablesCollection() throws Exception {
    var state = emptyImportState();
    when(randomVinImportStateRepository.findById("randomvin")).thenReturn(Optional.of(state));
    when(randomVinClient.getVin()).thenThrow(new RandomVinClientException(403));

    randomVinImportService.importRandomVin();

    verify(randomVinImportStateRepository).findById("randomvin");
    verify(randomVinImportStateRepository, times(2)).save(state);
    assertEquals(1, state.getCallsToday());
    assertEquals(1L, state.getLifetimeCalls());
    assertEquals(0, state.getVinsProcessedToday());
    assertEquals(0L, state.getLifetimeVinsProcessed());
    assertEquals(403, state.getLastFailureStatus());
    assertEquals(IMPORTED_ON, state.getLastFailureOn());
    assertEquals(IMPORTED_ON, state.getForbiddenOn());
    assertEquals(true, state.getPermanentlyDisabled());
    assertNull(state.getDisabledUntil());
    assertEquals("VIN data sourced from randomvin.com", state.getNotes());
    verifyNoInteractions(vehicleRepository);
  }

  @Test
  @DisplayName("Sets a 24 hour cooldown for 429 responses")
  public void testImportRandomVin_whenRateLimited_setsCooldown() throws Exception {
    var state = emptyImportState();
    when(randomVinImportStateRepository.findById("randomvin")).thenReturn(Optional.of(state));
    when(randomVinClient.getVin()).thenThrow(new RandomVinClientException(429));

    randomVinImportService.importRandomVin();

    verify(randomVinImportStateRepository, times(2)).save(state);
    assertEquals(1, state.getCallsToday());
    assertEquals(1L, state.getLifetimeCalls());
    assertEquals(0, state.getVinsProcessedToday());
    assertEquals(0L, state.getLifetimeVinsProcessed());
    assertEquals(429, state.getLastFailureStatus());
    assertEquals(IMPORTED_ON.plusSeconds(86400), state.getDisabledUntil());
    assertNull(state.getForbiddenOn());
    assertNull(state.getPermanentlyDisabled());
    verifyNoInteractions(vehicleRepository);
  }

  @Test
  @DisplayName("Skips RandomVIN calls after a permanent 403 disable")
  public void testImportRandomVin_whenPermanentlyDisabled_doesNotFetchOrSave() {
    when(randomVinImportStateRepository.findById("randomvin")).thenReturn(Optional.of(
        RandomVinImportState.builder()
            .id("randomvin")
            .callsOnDate(IMPORTED_ON.atZone(ZoneOffset.UTC).toLocalDate())
            .callsToday(10)
            .lifetimeCalls(100L)
            .forbiddenOn(IMPORTED_ON.minusSeconds(3600))
            .permanentlyDisabled(true)
            .build()
    ));

    randomVinImportService.importRandomVin();

    verify(randomVinImportStateRepository).findById("randomvin");
    verifyNoMoreInteractions(randomVinImportStateRepository);
    verifyNoInteractions(randomVinClient, vehicleRepository);
  }

  @Test
  @DisplayName("Removes legacy RandomVIN import notes from existing vehicles")
  public void testRemoveLegacyRandomVinNotes_whenLegacyNotesExist_clearsNotes() {
    var vehicle = VehicleStub.getVehicleStub(VehicleStub.ID);
    vehicle.setNotes("Imported from randomvin.com");
    when(vehicleRepository.findByNotes("Imported from randomvin.com")).thenReturn(List.of(vehicle));

    randomVinImportService.removeLegacyRandomVinNotes();

    assertNull(vehicle.getNotes());
    verify(vehicleRepository).findByNotes("Imported from randomvin.com");
    verify(vehicleRepository).save(vehicle);
    verifyNoMoreInteractions(vehicleRepository);
  }

  @Test
  @DisplayName("Skips invalid VIN responses")
  public void testImportRandomVin_whenVinIsInvalid_doesNotSave() throws Exception {
    givenNoImportState();
    when(randomVinClient.getVin()).thenReturn("not-a-vin");

    randomVinImportService.importRandomVin();

    verifyNoInteractions(vehicleRepository);
  }

  @Test
  @DisplayName("Restores interrupt flag when interrupted")
  public void testImportRandomVin_whenInterrupted_restoresInterruptFlag() throws Exception {
    givenNoImportState();
    when(randomVinClient.getVin()).thenThrow(new InterruptedException("interrupted"));

    randomVinImportService.importRandomVin();

    assertEquals(true, Thread.currentThread().isInterrupted());
    Thread.interrupted();
    verifyNoInteractions(vehicleRepository);
  }

  @Test
  @DisplayName("Skips VINs already present in the repository")
  public void testImportRandomVin_whenVinAlreadyExists_doesNotSave() throws Exception {
    givenNoImportState();
    when(randomVinClient.getVin()).thenReturn(VehicleStub.VIN);
    when(vehicleRepository.existsByVin(VehicleStub.VIN)).thenReturn(true);

    randomVinImportService.importRandomVin();

    verify(vehicleRepository).existsByVin(VehicleStub.VIN);
    verifyNoMoreInteractions(vehicleRepository);
  }

  @Test
  @DisplayName("Ignores duplicate VIN saves")
  public void testImportRandomVin_whenDuplicateVin_doesNotThrow() throws Exception {
    givenNoImportState();
    when(randomVinClient.getVin()).thenReturn(VehicleStub.VIN);
    when(vehicleRepository.save(org.mockito.ArgumentMatchers.any()))
        .thenThrow(DuplicateKeyException.class);

    randomVinImportService.importRandomVin();

    verify(vehicleRepository).save(org.mockito.ArgumentMatchers.any());
  }

  @Test
  @DisplayName("Skips fetch IO failures")
  public void testImportRandomVin_whenFetchFails_doesNotSave() throws Exception {
    givenNoImportState();
    when(randomVinClient.getVin()).thenThrow(new IOException("network"));

    randomVinImportService.importRandomVin();

    verifyNoInteractions(vehicleRepository);
  }

  private void givenNoImportState() {
    when(randomVinImportStateRepository.findById(eq("randomvin"))).thenReturn(Optional.empty());
  }

  private RandomVinImportState emptyImportState() {
    return RandomVinImportState.builder()
        .id("randomvin")
        .callsOnDate(IMPORTED_ON.atZone(ZoneOffset.UTC).toLocalDate())
        .callsToday(0)
        .lifetimeCalls(0L)
        .lifetimeVinsProcessed(0L)
        .vinsProcessedToday(0)
        .build();
  }
}
