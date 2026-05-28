package dev.christopherbell.vehicle.nhtsa;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

import dev.christopherbell.libs.api.exception.InvalidRequestException;
import dev.christopherbell.vehicle.VehicleStub;
import dev.christopherbell.vehicle.core.VehicleRepository;
import dev.christopherbell.vehicle.model.VehicleProperties;
import dev.christopherbell.vehicle.nhtsa.decode.NhtsaVinClient;
import dev.christopherbell.vehicle.nhtsa.decode.NhtsaVinClient.NhtsaVinDecodeRequest;
import dev.christopherbell.vehicle.nhtsa.decode.NhtsaVinClientException;
import dev.christopherbell.vehicle.nhtsa.enrichment.NhtsaVinEnrichmentService;
import dev.christopherbell.vehicle.nhtsa.enrichment.NhtsaVinImportStateRepository;
import dev.christopherbell.vehicle.nhtsa.model.NhtsaVinImportState;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for {@link NhtsaVinEnrichmentService}.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("NhtsaVinEnrichmentService unit tests")
public class NhtsaVinEnrichmentServiceTest {
  private static final Instant DECODED_ON = Instant.parse("2026-01-18T02:30:00.809Z");

  @Mock private NhtsaVinClient nhtsaVinClient;
  @Mock private NhtsaVinImportStateRepository nhtsaVinImportStateRepository;
  @Mock private VehicleRepository vehicleRepository;
  private NhtsaVinEnrichmentService nhtsaVinEnrichmentService;

  @BeforeEach
  public void setUp() {
    nhtsaVinEnrichmentService = new NhtsaVinEnrichmentService(
        Clock.fixed(DECODED_ON, ZoneOffset.UTC),
        nhtsaVinClient,
        nhtsaVinImportStateRepository,
        vehicleProperties(),
        vehicleRepository
    );
  }

  @Test
  @DisplayName("Enriches every vehicle with a stored VIN")
  public void testEnrichStoredVins_whenVehicleHasVin_savesDecodedDetails() throws Exception {
    var vehicle = VehicleStub.getVehicleStub(VehicleStub.ID);
    vehicle.setBodyStyle(null);
    vehicle.setDrivetrain(null);
    vehicle.setEngine(null);
    vehicle.setFuelType(null);
    vehicle.setMake(null);
    vehicle.setModel(null);
    vehicle.setTransmission(null);
    vehicle.setYear(null);
    vehicle.setTrim(null);

    var decodedValues = decodedValues(VehicleStub.VIN);
    givenNoNhtsaState();
    when(vehicleRepository.findByVinIsNotNull()).thenReturn(List.of(vehicle));
    when(nhtsaVinClient.decodeVins(eq(List.of(new NhtsaVinDecodeRequest(VehicleStub.VIN, null)))))
        .thenReturn(List.of(decodedValues));

    nhtsaVinEnrichmentService.enrichStoredVins();

    assertEquals("HONDA", vehicle.getMake());
    assertEquals("Accord", vehicle.getModel());
    assertEquals(2003, vehicle.getYear());
    assertEquals("EX-V6", vehicle.getTrim());
    assertEquals("Coupe", vehicle.getBodyStyle());
    assertEquals("Coupe", vehicle.getBodyClass());
    assertEquals(2, vehicle.getDoors());
    assertEquals("Gasoline", vehicle.getFuelType());
    assertEquals("5-speed Automatic", vehicle.getTransmission());
    assertEquals("2.998832712L 6 cylinder J30A4", vehicle.getEngine());
    assertEquals("AMERICAN HONDA MOTOR CO., INC.", vehicle.getManufacturer());
    assertEquals("988", vehicle.getManufacturerId());
    assertEquals("MARYSVILLE", vehicle.getPlantCity());
    assertEquals("UNITED STATES (USA)", vehicle.getPlantCountry());
    assertEquals("OHIO", vehicle.getPlantState());
    assertEquals("PASSENGER CAR", vehicle.getVehicleType());
    assertEquals("0", vehicle.getNhtsaErrorCode());
    assertEquals("0 - VIN decoded clean.", vehicle.getNhtsaErrorText());
    assertEquals(DECODED_ON, vehicle.getNhtsaLastDecodedOn());
    assertEquals(DECODED_ON, vehicle.getLastUpdatedOn());
    assertEquals(decodedValues, vehicle.getNhtsaDecodedValues());

    verify(vehicleRepository).findByVinIsNotNull();
    verify(nhtsaVinImportStateRepository).findById("nhtsa");
    verify(nhtsaVinImportStateRepository).save(argThat(state -> {
      assertEquals(1, state.getCallsToday());
      assertEquals(1L, state.getLifetimeCalls());
      assertEquals(1, state.getVinsProcessedToday());
      assertEquals(1L, state.getLifetimeVinsProcessed());
      assertEquals("VIN enrichment data sourced from NHTSA vPIC", state.getNotes());
      return true;
    }));
    verify(nhtsaVinClient).decodeVins(eq(List.of(new NhtsaVinDecodeRequest(VehicleStub.VIN, null))));
    verify(vehicleRepository).save(eq(vehicle));
    verifyNoMoreInteractions(nhtsaVinClient, vehicleRepository);
  }

  @Test
  @DisplayName("Does not overwrite existing user-entered fields")
  public void testEnrichStoredVins_whenVehicleHasExistingFields_preservesExistingValues() throws Exception {
    var vehicle = VehicleStub.getVehicleStub(VehicleStub.ID);
    givenNoNhtsaState();
    when(vehicleRepository.findByVinIsNotNull()).thenReturn(List.of(vehicle));
    when(nhtsaVinClient.decodeVins(eq(List.of(new NhtsaVinDecodeRequest(VehicleStub.VIN, VehicleStub.YEAR)))))
        .thenReturn(List.of(decodedValues(VehicleStub.VIN)));

    nhtsaVinEnrichmentService.enrichStoredVins();

    assertEquals(VehicleStub.MAKE, vehicle.getMake());
    assertEquals(VehicleStub.MODEL, vehicle.getModel());
    assertEquals(VehicleStub.YEAR, vehicle.getYear());
    assertEquals(VehicleStub.TRIM, vehicle.getTrim());

    verify(vehicleRepository).save(eq(vehicle));
  }

  @Test
  @DisplayName("Skips blank VINs")
  public void testEnrichStoredVins_whenVinIsBlank_skipsVehicle() {
    var vehicle = VehicleStub.getVehicleStub(VehicleStub.ID);
    vehicle.setVin(" ");
    givenNoNhtsaState();
    when(vehicleRepository.findByVinIsNotNull()).thenReturn(List.of(vehicle));

    nhtsaVinEnrichmentService.enrichStoredVins();

    verify(vehicleRepository).findByVinIsNotNull();
    verifyNoMoreInteractions(nhtsaVinClient, vehicleRepository);
  }

  @Test
  @DisplayName("Skips invalid NHTSA responses without failing the whole job")
  public void testEnrichStoredVins_whenNhtsaRejectsVin_doesNotSave() throws Exception {
    var vehicle = VehicleStub.getVehicleStub(VehicleStub.ID);
    givenNoNhtsaState();
    when(vehicleRepository.findByVinIsNotNull()).thenReturn(List.of(vehicle));
    when(nhtsaVinClient.decodeVins(eq(List.of(new NhtsaVinDecodeRequest(VehicleStub.VIN, VehicleStub.YEAR)))))
        .thenThrow(new InvalidRequestException("bad vin"));

    nhtsaVinEnrichmentService.enrichStoredVins();

    verify(vehicleRepository).findByVinIsNotNull();
    verify(nhtsaVinClient).decodeVins(eq(List.of(new NhtsaVinDecodeRequest(VehicleStub.VIN, VehicleStub.YEAR))));
    verifyNoMoreInteractions(nhtsaVinClient, vehicleRepository);
  }

  @Test
  @DisplayName("Deletes vehicle when NHTSA response does not include its VIN")
  public void testEnrichStoredVins_whenNhtsaDoesNotReturnVin_deletesVehicle() throws Exception {
    var vehicle = VehicleStub.getVehicleStub(VehicleStub.ID);
    givenNoNhtsaState();
    when(vehicleRepository.findByVinIsNotNull()).thenReturn(List.of(vehicle));
    when(nhtsaVinClient.decodeVins(eq(List.of(new NhtsaVinDecodeRequest(VehicleStub.VIN, VehicleStub.YEAR)))))
        .thenReturn(List.of(decodedValues("2FMDK4JCXEBB62196")));

    nhtsaVinEnrichmentService.enrichStoredVins();

    verify(vehicleRepository).findByVinIsNotNull();
    verify(vehicleRepository).delete(eq(vehicle));
    verify(nhtsaVinClient).decodeVins(eq(List.of(new NhtsaVinDecodeRequest(VehicleStub.VIN, VehicleStub.YEAR))));
    verifyNoMoreInteractions(nhtsaVinClient, vehicleRepository);
  }

  @Test
  @DisplayName("Deletes vehicle when NHTSA returns no usable data")
  public void testEnrichStoredVins_whenNhtsaReturnsNoUsableData_deletesVehicle() throws Exception {
    var vehicle = VehicleStub.getVehicleStub(VehicleStub.ID);
    givenNoNhtsaState();
    when(vehicleRepository.findByVinIsNotNull()).thenReturn(List.of(vehicle));
    when(nhtsaVinClient.decodeVins(eq(List.of(new NhtsaVinDecodeRequest(VehicleStub.VIN, VehicleStub.YEAR)))))
        .thenReturn(List.of(unusableDecodedValues(VehicleStub.VIN)));

    nhtsaVinEnrichmentService.enrichStoredVins();

    verify(vehicleRepository).findByVinIsNotNull();
    verify(vehicleRepository).delete(eq(vehicle));
    verify(nhtsaVinClient).decodeVins(eq(List.of(new NhtsaVinDecodeRequest(VehicleStub.VIN, VehicleStub.YEAR))));
    verifyNoMoreInteractions(nhtsaVinClient, vehicleRepository);
  }

  @Test
  @DisplayName("Enriches all due VINs per scheduler interval")
  public void testEnrichStoredVins_whenMultipleVinsAreDue_enrichesAllDueVins() throws Exception {
    var vehicle1 = VehicleStub.getVehicleStub(VehicleStub.ID);
    var vehicle2 = VehicleStub.getVehicleStub(VehicleStub.ID_2);
    vehicle1.setYear(null);
    vehicle2.setVin("2FMDK4JCXEBB62196");

    givenNoNhtsaState();
    when(vehicleRepository.findByVinIsNotNull()).thenReturn(List.of(vehicle1, vehicle2));
    when(nhtsaVinClient.decodeVins(eq(List.of(
        new NhtsaVinDecodeRequest(VehicleStub.VIN, null),
        new NhtsaVinDecodeRequest("2FMDK4JCXEBB62196", VehicleStub.YEAR)
    )))).thenReturn(List.of(decodedValues(VehicleStub.VIN), decodedValues("2FMDK4JCXEBB62196")));

    nhtsaVinEnrichmentService.enrichStoredVins();

    verify(nhtsaVinClient).decodeVins(eq(List.of(
        new NhtsaVinDecodeRequest(VehicleStub.VIN, null),
        new NhtsaVinDecodeRequest("2FMDK4JCXEBB62196", VehicleStub.YEAR)
    )));
    verify(nhtsaVinImportStateRepository).save(argThat(state -> {
      assertEquals(1, state.getCallsToday());
      assertEquals(1L, state.getLifetimeCalls());
      assertEquals(2, state.getVinsProcessedToday());
      assertEquals(2L, state.getLifetimeVinsProcessed());
      return true;
    }));
    verify(vehicleRepository).save(eq(vehicle1));
    verify(vehicleRepository).save(eq(vehicle2));
    verifyNoMoreInteractions(nhtsaVinClient, vehicleRepository);
  }

  @Test
  @DisplayName("Skips vehicles that have already been enriched")
  public void testEnrichStoredVins_whenVinWasAlreadyDecoded_skipsVehicle() {
    var vehicle = VehicleStub.getVehicleStub(VehicleStub.ID);
    vehicle.setNhtsaLastDecodedOn(DECODED_ON.minusSeconds(7200));
    givenNoNhtsaState();
    when(vehicleRepository.findByVinIsNotNull()).thenReturn(List.of(vehicle));

    nhtsaVinEnrichmentService.enrichStoredVins();

    verify(vehicleRepository).findByVinIsNotNull();
    verifyNoMoreInteractions(nhtsaVinClient, vehicleRepository);
  }

  @Test
  @DisplayName("Permanently disables NHTSA enrichment for 403 responses")
  public void testEnrichStoredVins_whenForbidden_permanentlyDisablesCollection() throws Exception {
    var state = emptyNhtsaState();
    var vehicle = VehicleStub.getVehicleStub(VehicleStub.ID);
    when(nhtsaVinImportStateRepository.findById("nhtsa")).thenReturn(Optional.of(state));
    when(vehicleRepository.findByVinIsNotNull()).thenReturn(List.of(vehicle));
    when(nhtsaVinClient.decodeVins(eq(List.of(new NhtsaVinDecodeRequest(VehicleStub.VIN, VehicleStub.YEAR)))))
        .thenThrow(new NhtsaVinClientException(403));

    nhtsaVinEnrichmentService.enrichStoredVins();

    verify(nhtsaVinImportStateRepository, times(2)).save(state);
    assertEquals(1, state.getCallsToday());
    assertEquals(1L, state.getLifetimeCalls());
    assertEquals(1, state.getVinsProcessedToday());
    assertEquals(1L, state.getLifetimeVinsProcessed());
    assertEquals(403, state.getLastFailureStatus());
    assertEquals(DECODED_ON, state.getForbiddenOn());
    assertEquals(true, state.getPermanentlyDisabled());
    assertNull(state.getDisabledUntil());
    verify(vehicleRepository).findByVinIsNotNull();
    verifyNoMoreInteractions(vehicleRepository);
  }

  @Test
  @DisplayName("Sets a 24 hour cooldown for NHTSA 429 responses")
  public void testEnrichStoredVins_whenRateLimited_setsCooldown() throws Exception {
    var state = emptyNhtsaState();
    var vehicle = VehicleStub.getVehicleStub(VehicleStub.ID);
    when(nhtsaVinImportStateRepository.findById("nhtsa")).thenReturn(Optional.of(state));
    when(vehicleRepository.findByVinIsNotNull()).thenReturn(List.of(vehicle));
    when(nhtsaVinClient.decodeVins(eq(List.of(new NhtsaVinDecodeRequest(VehicleStub.VIN, VehicleStub.YEAR)))))
        .thenThrow(new NhtsaVinClientException(429));

    nhtsaVinEnrichmentService.enrichStoredVins();

    verify(nhtsaVinImportStateRepository, times(2)).save(state);
    assertEquals(429, state.getLastFailureStatus());
    assertEquals(DECODED_ON.plusSeconds(86400), state.getDisabledUntil());
    assertNull(state.getForbiddenOn());
    assertNull(state.getPermanentlyDisabled());
    verify(vehicleRepository).findByVinIsNotNull();
    verifyNoMoreInteractions(vehicleRepository);
  }

  @Test
  @DisplayName("Skips NHTSA calls during a cooldown")
  public void testEnrichStoredVins_whenCoolingDown_doesNotFetchOrSave() {
    when(nhtsaVinImportStateRepository.findById("nhtsa")).thenReturn(Optional.of(
        NhtsaVinImportState.builder()
            .id("nhtsa")
            .callsOnDate(DECODED_ON.atZone(ZoneOffset.UTC).toLocalDate())
            .callsToday(1)
            .disabledUntil(DECODED_ON.plusSeconds(3600))
            .build()
    ));

    nhtsaVinEnrichmentService.enrichStoredVins();

    verify(nhtsaVinImportStateRepository).findById("nhtsa");
    verifyNoMoreInteractions(nhtsaVinImportStateRepository);
    verifyNoInteractions(nhtsaVinClient, vehicleRepository);
  }

  @Test
  @DisplayName("Skips NHTSA calls after a permanent 403 disable")
  public void testEnrichStoredVins_whenPermanentlyDisabled_doesNotFetchOrSave() {
    when(nhtsaVinImportStateRepository.findById("nhtsa")).thenReturn(Optional.of(
        NhtsaVinImportState.builder()
            .id("nhtsa")
            .callsOnDate(DECODED_ON.atZone(ZoneOffset.UTC).toLocalDate())
            .callsToday(1)
            .forbiddenOn(DECODED_ON.minusSeconds(3600))
            .permanentlyDisabled(true)
            .build()
    ));

    nhtsaVinEnrichmentService.enrichStoredVins();

    verify(nhtsaVinImportStateRepository).findById("nhtsa");
    verifyNoMoreInteractions(nhtsaVinImportStateRepository);
    verifyNoInteractions(nhtsaVinClient, vehicleRepository);
  }

  private Map<String, String> decodedValues(String vin) {
    var values = new HashMap<String, String>();
    values.put("BodyClass", "Coupe");
    values.put("DisplacementL", "2.998832712");
    values.put("Doors", "2");
    values.put("EngineCylinders", "6");
    values.put("EngineModel", "J30A4");
    values.put("ErrorCode", "0");
    values.put("ErrorText", "0 - VIN decoded clean.");
    values.put("FuelTypePrimary", "Gasoline");
    values.put("GVWR", "Class 1C");
    values.put("Make", "HONDA");
    values.put("Manufacturer", "AMERICAN HONDA MOTOR CO., INC.");
    values.put("ManufacturerId", "988");
    values.put("Model", "Accord");
    values.put("ModelYear", "2003");
    values.put("PlantCity", "MARYSVILLE");
    values.put("PlantCountry", "UNITED STATES (USA)");
    values.put("PlantState", "OHIO");
    values.put("TransmissionSpeeds", "5");
    values.put("TransmissionStyle", "Automatic");
    values.put("Trim", "EX-V6");
    values.put("VehicleType", "PASSENGER CAR");
    values.put("VIN", vin);
    return values;
  }

  private Map<String, String> unusableDecodedValues(String vin) {
    var values = new HashMap<String, String>();
    values.put("ErrorCode", "1");
    values.put("ErrorText", "1 - Check Digit does not calculate properly.");
    values.put("VIN", vin);
    return values;
  }

  private void givenNoNhtsaState() {
    when(nhtsaVinImportStateRepository.findById(eq("nhtsa"))).thenReturn(Optional.empty());
  }

  private VehicleProperties vehicleProperties() {
    var properties = new VehicleProperties();
    properties.getNhtsaVin().setEnabled(true);
    properties.getNhtsaVin().setBatchSize(50);
    properties.getNhtsaVin().setCooldown(Duration.ofHours(24));
    properties.getNhtsaVin().setStateId("nhtsa");
    properties.getNhtsaVin().setStateNote("VIN enrichment data sourced from NHTSA vPIC");
    return properties;
  }

  private NhtsaVinImportState emptyNhtsaState() {
    return NhtsaVinImportState.builder()
        .id("nhtsa")
        .callsOnDate(DECODED_ON.atZone(ZoneOffset.UTC).toLocalDate())
        .callsToday(0)
        .lifetimeCalls(0L)
        .lifetimeVinsProcessed(0L)
        .vinsProcessedToday(0)
        .build();
  }
}
