package dev.christopherbell.vehicle;

import static dev.christopherbell.libs.api.APIVersion.V20260509;

import dev.christopherbell.libs.api.model.Response;
import dev.christopherbell.vehicle.model.VehicleCreateRequest;
import dev.christopherbell.vehicle.model.VehicleDataCollectionState;
import dev.christopherbell.vehicle.model.VehicleDetail;
import dev.christopherbell.vehicle.model.VehicleUpdateRequest;
import dev.christopherbell.vehicle.model.VehicleVinBatchRequest;
import dev.christopherbell.vehicle.model.VehicleVinDecodeRequest;
import dev.christopherbell.vehicle.model.VehicleVinDecodeResponse;
import dev.christopherbell.vehicle.model.VehicleVinRequest;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
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
  private final VehicleDataCollectionStateService vehicleDataCollectionStateService;
  private final VehicleVinDecodeService vehicleVinDecodeService;
  private final VehicleService vehicleService;

  /**
   * Creates a vehicle.
   *
   * @param request the vehicle creation request body
   * @return the created vehicle response
   * @throws Exception when creation fails
   */
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

  /**
   * Creates a vehicle from a VIN only.
   *
   * @param request the VIN request body
   * @return the created vehicle response
   * @throws Exception when creation fails
   */
  @PostMapping(
      value = V20260509 + "/vin",
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE
  )
  @PreAuthorize("@permissionService.hasAuthority('ADMIN')")
  public ResponseEntity<Response<VehicleDetail>> createVehicleFromVin(
      @RequestBody VehicleVinRequest request
  ) throws Exception {
    return new ResponseEntity<>(
        Response.<VehicleDetail>builder()
            .payload(vehicleService.createVehicleFromVin(request))
            .success(true)
            .build(),
        HttpStatus.CREATED);
  }

  /**
   * Decodes a VIN through NHTSA without creating or updating a stored vehicle.
   *
   * @param request the VIN decode request body
   * @return the decoded VIN response
   * @throws Exception when decoding fails
   */
  @PostMapping(
      value = V20260509 + "/vin/decode",
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE
  )
  public ResponseEntity<Response<VehicleVinDecodeResponse>> decodeVin(
      @RequestBody VehicleVinDecodeRequest request,
      HttpServletRequest servletRequest
  ) throws Exception {
    return new ResponseEntity<>(
        Response.<VehicleVinDecodeResponse>builder()
            .payload(vehicleVinDecodeService.decode(request, clientKey(servletRequest)))
            .success(true)
            .build(),
        HttpStatus.OK);
  }

  private String clientKey(HttpServletRequest request) {
    var authentication = SecurityContextHolder.getContext().getAuthentication();
    if (authentication != null
        && authentication.isAuthenticated()
        && authentication.getName() != null
        && !"anonymousUser".equals(authentication.getName())) {
      return "account:" + authentication.getName();
    }

    var forwardedFor = request.getHeader("X-Forwarded-For");
    if (forwardedFor != null && !forwardedFor.isBlank()) {
      return "ip:" + forwardedFor.split(",")[0].trim();
    }
    return "ip:" + request.getRemoteAddr();
  }

  /**
   * Creates vehicles from multiple VINs.
   *
   * @param request the VIN batch request body
   * @return the created vehicles response
   * @throws Exception when creation fails
   */
  @PostMapping(
      value = V20260509 + "/vins",
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE
  )
  @PreAuthorize("@permissionService.hasAuthority('ADMIN')")
  public ResponseEntity<Response<List<VehicleDetail>>> createVehiclesFromVins(
      @RequestBody VehicleVinBatchRequest request
  ) throws Exception {
    return new ResponseEntity<>(
        Response.<List<VehicleDetail>>builder()
            .payload(vehicleService.createVehiclesFromVins(request))
            .success(true)
            .build(),
        HttpStatus.CREATED);
  }

  /**
   * Deletes a vehicle by id.
   *
   * @param id the vehicle id to delete
   * @return the deleted vehicle response
   * @throws Exception when deletion fails
   */
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

  /**
   * Gets all vehicles.
   *
   * @return all stored vehicles
   */
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

  /**
   * Gets vehicle data collection state for RandomVIN and NHTSA.
   *
   * @return the vehicle data collection state response
   */
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

  /**
   * Gets vehicles by make.
   *
   * @param make the make to search for
   * @return matching vehicles
   * @throws Exception when lookup fails
   */
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

  /**
   * Gets one vehicle by id.
   *
   * @param id the vehicle id to fetch
   * @return the matching vehicle response
   * @throws Exception when lookup fails
   */
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

  /**
   * Updates a vehicle by id.
   *
   * @param id the vehicle id to update
   * @param request the vehicle update request body
   * @return the updated vehicle response
   * @throws Exception when update fails
   */
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
