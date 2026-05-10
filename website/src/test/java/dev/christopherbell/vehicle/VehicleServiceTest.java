package dev.christopherbell.vehicle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import dev.christopherbell.libs.api.exception.InvalidRequestException;
import dev.christopherbell.libs.api.exception.ResourceExistsException;
import dev.christopherbell.libs.api.exception.ResourceNotFoundException;
import dev.christopherbell.vehicle.model.VehicleVinBatchRequest;
import dev.christopherbell.vehicle.model.VehicleVinRequest;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;

/**
 * Unit tests for {@link VehicleService}.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("VehicleService unit tests")
public class VehicleServiceTest {
  @Mock private Clock clock;
  @Mock private VehicleMapper vehicleMapper;
  @Mock private VehicleRepository vehicleRepository;
  @InjectMocks private VehicleService vehicleService;

  @Test
  @DisplayName("Creates vehicle with generated UUID id")
  public void testCreateVehicle_whenValidRequest_ReturnsVehicleDetail() throws Exception {
    var request = VehicleStub.getVehicleCreateRequestStub();
    var vehicle = VehicleStub.getVehicleStub(null);
    var saved = VehicleStub.getVehicleStub(VehicleStub.ID);
    var detail = VehicleStub.getVehicleDetailStub(VehicleStub.ID);

    when(vehicleMapper.toVehicle(eq(request))).thenReturn(vehicle);
    when(vehicleRepository.save(eq(vehicle))).thenReturn(saved);
    when(vehicleMapper.toVehicleDetail(eq(saved))).thenReturn(detail);

    var result = vehicleService.createVehicle(request);

    assertSame(detail, result);
    assertNotNull(vehicle.getId());
    verify(vehicleMapper).toVehicle(eq(request));
    verify(vehicleRepository).save(eq(vehicle));
    verify(vehicleMapper).toVehicleDetail(eq(saved));
    verifyNoMoreInteractions(vehicleMapper, vehicleRepository);
  }

  @Test
  @DisplayName("Rejects missing VIN")
  public void testCreateVehicle_whenVinMissing_ThrowsInvalidRequestException() {
    var request = VehicleStub.getVehicleCreateRequestStub();
    request.setVin(" ");

    assertThrows(InvalidRequestException.class, () -> vehicleService.createVehicle(request));

    verifyNoInteractions(vehicleMapper, vehicleRepository);
  }

  @Test
  @DisplayName("Translates duplicate VIN into ResourceExistsException")
  public void testCreateVehicle_whenDuplicateVin_ThrowsResourceExistsException() {
    var request = VehicleStub.getVehicleCreateRequestStub();
    var vehicle = VehicleStub.getVehicleStub(null);

    when(vehicleMapper.toVehicle(eq(request))).thenReturn(vehicle);
    when(vehicleRepository.save(eq(vehicle))).thenThrow(DuplicateKeyException.class);

    var ex = assertThrows(ResourceExistsException.class, () -> vehicleService.createVehicle(request));
    assertTrue(ex.getMessage().contains(VehicleStub.VIN));

    verify(vehicleMapper).toVehicle(eq(request));
    verify(vehicleRepository).save(eq(vehicle));
    verifyNoMoreInteractions(vehicleMapper, vehicleRepository);
  }

  @Test
  @DisplayName("Creates vehicle from VIN only")
  public void testCreateVehicleFromVin_whenValidRequest_ReturnsVehicleDetail() throws Exception {
    var request = new VehicleVinRequest("  " + VehicleStub.VIN.toLowerCase() + " ");
    var saved = VehicleStub.getVehicleStub(VehicleStub.ID);
    var detail = VehicleStub.getVehicleDetailStub(VehicleStub.ID);
    saved.setMake(null);
    saved.setModel(null);
    saved.setYear(null);

    when(clock.instant()).thenReturn(VehicleStub.CREATED_ON);
    when(vehicleRepository.save(any())).thenReturn(saved);
    when(vehicleMapper.toVehicleDetail(eq(saved))).thenReturn(detail);

    var result = vehicleService.createVehicleFromVin(request);

    assertSame(detail, result);
    verify(vehicleRepository).save(argThat(vehicle -> {
      assertNotNull(vehicle.getId());
      assertEquals(VehicleStub.VIN, vehicle.getVin());
      assertEquals(VehicleStub.CREATED_ON, vehicle.getCreatedOn());
      assertEquals(VehicleStub.CREATED_ON, vehicle.getLastUpdatedOn());
      return true;
    }));
    verify(vehicleMapper).toVehicleDetail(eq(saved));
    verifyNoMoreInteractions(vehicleMapper, vehicleRepository);
  }

  @Test
  @DisplayName("Rejects invalid VIN-only requests")
  public void testCreateVehicleFromVin_whenInvalidVin_ThrowsInvalidRequestException() {
    assertThrows(InvalidRequestException.class, () -> vehicleService.createVehicleFromVin(new VehicleVinRequest("bad")));

    verifyNoInteractions(vehicleMapper, vehicleRepository);
  }

  @Test
  @DisplayName("Creates vehicles from VIN batch")
  public void testCreateVehiclesFromVins_whenValidRequest_ReturnsVehicleDetails() throws Exception {
    var vin2 = "2FMDK4JCXEBB62196";
    var request = new VehicleVinBatchRequest(List.of("  " + VehicleStub.VIN.toLowerCase() + " ", vin2));
    var saved1 = VehicleStub.getVehicleStub(VehicleStub.ID);
    var saved2 = VehicleStub.getVehicleStub(VehicleStub.ID_2);
    var detail1 = VehicleStub.getVehicleDetailStub(VehicleStub.ID);
    var detail2 = VehicleStub.getVehicleDetailStub(VehicleStub.ID_2);
    saved2.setVin(vin2);
    detail2.setVin(vin2);

    when(vehicleRepository.existsByVin(eq(VehicleStub.VIN))).thenReturn(false);
    when(vehicleRepository.existsByVin(eq(vin2))).thenReturn(false);
    when(clock.instant()).thenReturn(VehicleStub.CREATED_ON);
    when(vehicleRepository.saveAll(any())).thenReturn(List.of(saved1, saved2));
    when(vehicleMapper.toVehicleDetail(eq(saved1))).thenReturn(detail1);
    when(vehicleMapper.toVehicleDetail(eq(saved2))).thenReturn(detail2);

    var result = vehicleService.createVehiclesFromVins(request);

    assertEquals(List.of(detail1, detail2), result);
    verify(vehicleRepository).existsByVin(eq(VehicleStub.VIN));
    verify(vehicleRepository).existsByVin(eq(vin2));
    verify(vehicleRepository).saveAll(argThat(vehicles -> {
      var vehicleList = (List<?>) vehicles;
      assertEquals(2, vehicleList.size());
      assertTrue(vehicleList.stream().allMatch(vehicle -> ((dev.christopherbell.vehicle.model.Vehicle) vehicle).getId() != null));
      assertTrue(vehicleList.stream()
          .anyMatch(vehicle -> VehicleStub.VIN.equals(((dev.christopherbell.vehicle.model.Vehicle) vehicle).getVin())));
      assertTrue(vehicleList.stream()
          .anyMatch(vehicle -> vin2.equals(((dev.christopherbell.vehicle.model.Vehicle) vehicle).getVin())));
      return true;
    }));
    verify(vehicleMapper).toVehicleDetail(eq(saved1));
    verify(vehicleMapper).toVehicleDetail(eq(saved2));
    verifyNoMoreInteractions(vehicleMapper, vehicleRepository);
  }

  @Test
  @DisplayName("Rejects duplicate VINs in batch request")
  public void testCreateVehiclesFromVins_whenRequestHasDuplicates_ThrowsInvalidRequestException() {
    var request = new VehicleVinBatchRequest(List.of(VehicleStub.VIN, VehicleStub.VIN.toLowerCase()));

    assertThrows(InvalidRequestException.class, () -> vehicleService.createVehiclesFromVins(request));

    verifyNoInteractions(vehicleMapper, vehicleRepository);
  }

  @Test
  @DisplayName("Rejects batch when VIN already exists")
  public void testCreateVehiclesFromVins_whenVinExists_ThrowsResourceExistsException() {
    var request = new VehicleVinBatchRequest(List.of(VehicleStub.VIN));
    when(vehicleRepository.existsByVin(eq(VehicleStub.VIN))).thenReturn(true);

    assertThrows(ResourceExistsException.class, () -> vehicleService.createVehiclesFromVins(request));

    verify(vehicleRepository).existsByVin(eq(VehicleStub.VIN));
    verifyNoMoreInteractions(vehicleMapper, vehicleRepository);
  }

  @Test
  @DisplayName("Returns all vehicles sorted by repository")
  public void testGetVehicles_whenSomeExist_ReturnsMappedList() {
    var vehicle1 = VehicleStub.getVehicleStub(VehicleStub.ID);
    var vehicle2 = VehicleStub.getVehicleStub(VehicleStub.ID_2);
    var detail1 = VehicleStub.getVehicleDetailStub(VehicleStub.ID);
    var detail2 = VehicleStub.getVehicleDetailStub(VehicleStub.ID_2);

    when(vehicleRepository.findAllByOrderByMakeAscModelAscYearDesc()).thenReturn(List.of(vehicle1, vehicle2));
    when(vehicleMapper.toVehicleDetail(eq(vehicle1))).thenReturn(detail1);
    when(vehicleMapper.toVehicleDetail(eq(vehicle2))).thenReturn(detail2);

    var result = vehicleService.getVehicles();

    assertEquals(2, result.size());
    assertTrue(result.containsAll(List.of(detail1, detail2)));

    verify(vehicleRepository).findAllByOrderByMakeAscModelAscYearDesc();
    verify(vehicleMapper).toVehicleDetail(eq(vehicle1));
    verify(vehicleMapper).toVehicleDetail(eq(vehicle2));
    verifyNoMoreInteractions(vehicleMapper, vehicleRepository);
  }

  @Test
  @DisplayName("Returns vehicles by make")
  public void testGetVehiclesByMake_whenValidMake_ReturnsMappedList() throws Exception {
    var vehicle = VehicleStub.getVehicleStub(VehicleStub.ID);
    var detail = VehicleStub.getVehicleDetailStub(VehicleStub.ID);

    when(vehicleRepository.findByMakeIgnoreCase(eq(VehicleStub.MAKE))).thenReturn(List.of(vehicle));
    when(vehicleMapper.toVehicleDetail(eq(vehicle))).thenReturn(detail);

    var result = vehicleService.getVehiclesByMake(VehicleStub.MAKE);

    assertEquals(List.of(detail), result);
    verify(vehicleRepository).findByMakeIgnoreCase(eq(VehicleStub.MAKE));
    verify(vehicleMapper).toVehicleDetail(eq(vehicle));
    verifyNoMoreInteractions(vehicleMapper, vehicleRepository);
  }

  @Test
  @DisplayName("Throws InvalidRequestException when make is blank")
  public void testGetVehiclesByMake_whenBlankMake_ThrowsInvalidRequestException() {
    assertThrows(InvalidRequestException.class, () -> vehicleService.getVehiclesByMake(" "));
    verifyNoInteractions(vehicleMapper, vehicleRepository);
  }

  @Test
  @DisplayName("Returns vehicle by id")
  public void testGetVehicleById_whenFound_ReturnsVehicleDetail() throws Exception {
    var vehicle = VehicleStub.getVehicleStub(VehicleStub.ID);
    var detail = VehicleStub.getVehicleDetailStub(VehicleStub.ID);

    when(vehicleRepository.findById(eq(VehicleStub.ID))).thenReturn(Optional.of(vehicle));
    when(vehicleMapper.toVehicleDetail(eq(vehicle))).thenReturn(detail);

    var result = vehicleService.getVehicleById(VehicleStub.ID);

    assertSame(detail, result);
    verify(vehicleRepository).findById(eq(VehicleStub.ID));
    verify(vehicleMapper).toVehicleDetail(eq(vehicle));
    verifyNoMoreInteractions(vehicleMapper, vehicleRepository);
  }

  @Test
  @DisplayName("Throws ResourceNotFoundException when id does not exist")
  public void testGetVehicleById_whenNotFound_ThrowsResourceNotFoundException() {
    when(vehicleRepository.findById(eq(VehicleStub.ID))).thenReturn(Optional.empty());

    assertThrows(ResourceNotFoundException.class, () -> vehicleService.getVehicleById(VehicleStub.ID));

    verify(vehicleRepository).findById(eq(VehicleStub.ID));
    verifyNoMoreInteractions(vehicleMapper, vehicleRepository);
  }

  @Test
  @DisplayName("Updates vehicle and preserves audit fields")
  public void testUpdateVehicle_whenValidRequest_ReturnsUpdatedVehicleDetail() throws Exception {
    var request = VehicleStub.getVehicleUpdateRequestStub();
    var existing = VehicleStub.getVehicleStub(VehicleStub.ID);
    var update = VehicleStub.getVehicleStub(null);
    var saved = VehicleStub.getVehicleStub(VehicleStub.ID);
    var detail = VehicleStub.getVehicleDetailStub(VehicleStub.ID);

    when(vehicleRepository.findById(eq(VehicleStub.ID))).thenReturn(Optional.of(existing));
    when(vehicleMapper.toVehicle(eq(request))).thenReturn(update);
    when(vehicleRepository.save(eq(update))).thenReturn(saved);
    when(vehicleMapper.toVehicleDetail(eq(saved))).thenReturn(detail);

    var result = vehicleService.updateVehicle(VehicleStub.ID, request);

    assertSame(detail, result);
    assertEquals(VehicleStub.ID, update.getId());
    verify(vehicleRepository).findById(eq(VehicleStub.ID));
    verify(vehicleMapper).toVehicle(eq(request));
    verify(vehicleRepository).save(eq(update));
    verify(vehicleMapper).toVehicleDetail(eq(saved));
    verifyNoMoreInteractions(vehicleMapper, vehicleRepository);
  }

  @Test
  @DisplayName("Deletes vehicle by id")
  public void testDeleteVehicleById_whenFound_ReturnsDeletedVehicleDetail() throws Exception {
    var vehicle = VehicleStub.getVehicleStub(VehicleStub.ID);
    var detail = VehicleStub.getVehicleDetailStub(VehicleStub.ID);

    when(vehicleRepository.findById(eq(VehicleStub.ID))).thenReturn(Optional.of(vehicle));
    when(vehicleMapper.toVehicleDetail(eq(vehicle))).thenReturn(detail);

    var result = vehicleService.deleteVehicleById(VehicleStub.ID);

    assertSame(detail, result);
    verify(vehicleRepository).findById(eq(VehicleStub.ID));
    verify(vehicleRepository).delete(eq(vehicle));
    verify(vehicleMapper).toVehicleDetail(eq(vehicle));
    verifyNoMoreInteractions(vehicleMapper, vehicleRepository);
  }
}
