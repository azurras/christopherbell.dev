package dev.christopherbell.vehicle;

import static dev.christopherbell.libs.api.APIVersion.V20260509;

import dev.christopherbell.libs.api.model.Response;
import dev.christopherbell.permission.PermissionService;
import dev.christopherbell.vehicle.model.VehicleCreateRequest;
import dev.christopherbell.vehicle.model.VehicleDataCollectionState;
import dev.christopherbell.vehicle.model.VehicleDetail;
import dev.christopherbell.vehicle.model.VehicleUpdateRequest;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller for vehicle management.
 */
@RequiredArgsConstructor
@RequestMapping("/api/vehicles")
@RestController
public class VehicleController {
  private final PermissionService permissionService;
  private final VehicleDataCollectionStateService vehicleDataCollectionStateService;
  private final VehicleService vehicleService;

  @PostMapping(
      value = V20260509,
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE
  )
  @PreAuthorize("@permissionService.hasAuthority('ADMIN')")
  public ResponseEntity<Response<VehicleDetail>> createVehicle(
      @RequestBody VehicleCreateRequest request
  ) throws Exception {
    return new ResponseEntity<>(
        Response.<VehicleDetail>builder()
            .payload(vehicleService.createVehicle(request))
            .success(true)
            .build(),
        HttpStatus.CREATED);
  }

  @DeleteMapping(value = V20260509 + "/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
  @PreAuthorize("@permissionService.hasAuthority('ADMIN')")
  public ResponseEntity<Response<VehicleDetail>> deleteVehicleById(
      @PathVariable String id
  ) throws Exception {
    return new ResponseEntity<>(
        Response.<VehicleDetail>builder()
            .payload(vehicleService.deleteVehicleById(id))
            .success(true)
            .build(),
        HttpStatus.OK);
  }

  @GetMapping(value = V20260509, produces = MediaType.APPLICATION_JSON_VALUE)
  @PreAuthorize("@permissionService.hasAuthority('ADMIN')")
  public ResponseEntity<Response<List<VehicleDetail>>> getVehicles() {
    return new ResponseEntity<>(
        Response.<List<VehicleDetail>>builder()
            .payload(vehicleService.getVehicles())
            .success(true)
            .build(),
        HttpStatus.OK);
  }

  @GetMapping(value = V20260509 + "/data-collection-state", produces = MediaType.APPLICATION_JSON_VALUE)
  @PreAuthorize("@permissionService.hasAuthority('ADMIN')")
  public ResponseEntity<Response<VehicleDataCollectionState>> getDataCollectionState() {
    return new ResponseEntity<>(
        Response.<VehicleDataCollectionState>builder()
            .payload(vehicleDataCollectionStateService.getState())
            .success(true)
            .build(),
        HttpStatus.OK);
  }

  @GetMapping(value = V20260509 + "/make/{make}", produces = MediaType.APPLICATION_JSON_VALUE)
  @PreAuthorize("@permissionService.hasAuthority('ADMIN')")
  public ResponseEntity<Response<List<VehicleDetail>>> getVehiclesByMake(
      @PathVariable String make
  ) throws Exception {
    return new ResponseEntity<>(
        Response.<List<VehicleDetail>>builder()
            .payload(vehicleService.getVehiclesByMake(make))
            .success(true)
            .build(),
        HttpStatus.OK);
  }

  @GetMapping(value = V20260509 + "/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
  @PreAuthorize("@permissionService.hasAuthority('ADMIN')")
  public ResponseEntity<Response<VehicleDetail>> getVehicleById(
      @PathVariable String id
  ) throws Exception {
    return new ResponseEntity<>(
        Response.<VehicleDetail>builder()
            .payload(vehicleService.getVehicleById(id))
            .success(true)
            .build(),
        HttpStatus.OK);
  }

  @PutMapping(
      value = V20260509 + "/{id}",
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE
  )
  @PreAuthorize("@permissionService.hasAuthority('ADMIN')")
  public ResponseEntity<Response<VehicleDetail>> updateVehicle(
      @PathVariable String id,
      @RequestBody VehicleUpdateRequest request
  ) throws Exception {
    return new ResponseEntity<>(
        Response.<VehicleDetail>builder()
            .payload(vehicleService.updateVehicle(id, request))
            .success(true)
            .build(),
        HttpStatus.ACCEPTED);
  }
}
