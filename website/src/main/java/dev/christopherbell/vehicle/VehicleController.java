package dev.christopherbell.vehicle;

import static dev.christopherbell.libs.api.APIVersion.V20260509;
import static dev.christopherbell.libs.api.APIVersion.V20260726;

import dev.christopherbell.configuration.ClientIpResolver;
import dev.christopherbell.libs.api.exception.InvalidRequestException;
import dev.christopherbell.libs.api.exception.ResourceExistsException;
import dev.christopherbell.libs.api.exception.ResourceNotFoundException;
import dev.christopherbell.libs.api.model.Response;
import dev.christopherbell.vehicle.core.VehicleDataCollectionStateService;
import dev.christopherbell.vehicle.model.VehicleCreateRequest;
import dev.christopherbell.vehicle.model.VehicleDataCollectionState;
import dev.christopherbell.vehicle.model.VehicleDetail;
import dev.christopherbell.vehicle.model.VehicleUpdateRequest;
import dev.christopherbell.vehicle.model.VehicleVinBatchRequest;
import dev.christopherbell.vehicle.model.VehicleVinDecodeBatchRequest;
import dev.christopherbell.vehicle.model.VehicleVinDecodeBatchResponse;
import dev.christopherbell.vehicle.model.VehicleVinDecodeRequest;
import dev.christopherbell.vehicle.model.VehicleVinDecodeResponse;
import dev.christopherbell.vehicle.model.VehicleVinRequest;
import dev.christopherbell.vehicle.nhtsa.decode.VehicleVinDecodeService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
@Slf4j
public class VehicleController {
  private final VehicleDataCollectionStateService vehicleDataCollectionStateService;
  private final VehicleVinDecodeService vehicleVinDecodeService;
  private final VehicleService vehicleService;
  private final ClientIpResolver clientIpResolver;

  /**
   * Creates a vehicle.
   *
   * @param request the vehicle creation request body
   * @return the created vehicle response
   * @throws InvalidRequestException when the request is invalid
   * @throws ResourceExistsException when a vehicle already has the VIN
   */
  @PostMapping(
      value = V20260509,
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE
  )
  @PreAuthorize("@permissionService.hasAuthority('ADMIN')")
  public ResponseEntity<Response<VehicleDetail>> createVehicle(
      @RequestBody VehicleCreateRequest request
  ) throws InvalidRequestException, ResourceExistsException {
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
   * @throws InvalidRequestException when the request is invalid
   * @throws ResourceExistsException when a vehicle already has the VIN
   */
  @PostMapping(
      value = V20260509 + "/vin",
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE
  )
  @PreAuthorize("@permissionService.hasAuthority('ADMIN')")
  public ResponseEntity<Response<VehicleDetail>> createVehicleFromVin(
      @RequestBody VehicleVinRequest request
  ) throws InvalidRequestException, ResourceExistsException {
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
   * @param decodeRequest the VIN decode request body
   * @param servletRequest the current HTTP request used to derive the rate-limit key
   * @return the decoded VIN response
   * @throws InvalidRequestException when the VIN is invalid
   */
  @PostMapping(
      value = V20260509 + "/vin/decode",
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE
  )
  public ResponseEntity<Response<VehicleVinDecodeResponse>> decodeVin(
      @Valid @RequestBody VehicleVinDecodeRequest decodeRequest,
      HttpServletRequest servletRequest
  ) throws InvalidRequestException {
    var clientIp = clientIpResolver.resolveClientIp(servletRequest);
    var clientKey = clientKey(clientIp);
    log.info("VIN decoder used from ip={} clientKey={}.", clientIp, clientKey);
    return new ResponseEntity<>(
        Response.<VehicleVinDecodeResponse>builder()
            .payload(vehicleVinDecodeService.decode(decodeRequest, clientKey))
            .success(true)
            .build(),
        HttpStatus.OK);
  }

  /**
   * Decodes an ordered VIN batch with one success or safe error per submitted position.
   *
   * @param batchRequest the ordered VIN batch
   * @param servletRequest the current HTTP request used to derive the rate-limit key
   * @return ordered partial-success decode results
   * @throws InvalidRequestException when the batch envelope is invalid
   */
  @PostMapping(
      value = V20260726 + "/vin/decode/batch",
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE
  )
  public ResponseEntity<Response<VehicleVinDecodeBatchResponse>> decodeVinBatch(
      @Valid @RequestBody VehicleVinDecodeBatchRequest batchRequest,
      HttpServletRequest servletRequest
  ) throws InvalidRequestException {
    var clientIp = clientIpResolver.resolveClientIp(servletRequest);
    var clientKey = clientKey(clientIp);
    log.info("VIN batch decoder used from ip={} clientKey={} count={}.",
        clientIp, clientKey, batchRequest.vins().size());
    return ResponseEntity.ok(Response.<VehicleVinDecodeBatchResponse>builder()
        .payload(vehicleVinDecodeService.decodeBatch(batchRequest, clientKey))
        .success(true)
        .build());
  }

  private String clientKey(String clientIp) {
    var authentication = SecurityContextHolder.getContext().getAuthentication();
    if (authentication != null
        && authentication.isAuthenticated()
        && authentication.getName() != null
        && !"anonymousUser".equals(authentication.getName())) {
      return "account:" + authentication.getName();
    }

    return "ip:" + clientIp;
  }

  /**
   * Creates vehicles from multiple VINs.
   *
   * @param request the VIN batch request body
   * @return the created vehicles response
   * @throws InvalidRequestException when the request is invalid
   * @throws ResourceExistsException when a vehicle already has the VIN
   */
  @PostMapping(
      value = V20260509 + "/vins",
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE
  )
  @PreAuthorize("@permissionService.hasAuthority('ADMIN')")
  public ResponseEntity<Response<List<VehicleDetail>>> createVehiclesFromVins(
      @RequestBody VehicleVinBatchRequest request
  ) throws InvalidRequestException, ResourceExistsException {
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
   * @throws InvalidRequestException when the request is invalid
   * @throws ResourceNotFoundException when no vehicle has the id
   */
  @DeleteMapping(value = V20260509 + "/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
  @PreAuthorize("@permissionService.hasAuthority('ADMIN')")
  public ResponseEntity<Response<VehicleDetail>> deleteVehicleById(
      @PathVariable String id
  ) throws InvalidRequestException, ResourceNotFoundException {
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
   * @throws InvalidRequestException when the make is blank
   */
  @GetMapping(value = V20260509 + "/make/{make}", produces = MediaType.APPLICATION_JSON_VALUE)
  @PreAuthorize("@permissionService.hasAuthority('ADMIN')")
  public ResponseEntity<Response<List<VehicleDetail>>> getVehiclesByMake(
      @PathVariable String make
  ) throws InvalidRequestException {
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
   * @throws InvalidRequestException when the request is invalid
   * @throws ResourceNotFoundException when no vehicle has the id
   */
  @GetMapping(value = V20260509 + "/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
  @PreAuthorize("@permissionService.hasAuthority('ADMIN')")
  public ResponseEntity<Response<VehicleDetail>> getVehicleById(
      @PathVariable String id
  ) throws InvalidRequestException, ResourceNotFoundException {
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
   * @throws InvalidRequestException when the request is invalid
   * @throws ResourceExistsException when a vehicle already has the VIN
   * @throws ResourceNotFoundException when no vehicle has the id
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
  ) throws InvalidRequestException, ResourceExistsException, ResourceNotFoundException {
    return new ResponseEntity<>(
        Response.<VehicleDetail>builder()
            .payload(vehicleService.updateVehicle(id, request))
            .success(true)
            .build(),
        HttpStatus.ACCEPTED);
  }
}
