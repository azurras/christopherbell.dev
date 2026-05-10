package dev.christopherbell.vehicle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import dev.christopherbell.libs.api.exception.InvalidRequestException;
import dev.christopherbell.libs.api.exception.ResourceExistsException;
import dev.christopherbell.libs.api.exception.ResourceNotFoundException;
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
