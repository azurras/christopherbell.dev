package dev.christopherbell.location.zip;

import dev.christopherbell.libs.api.exception.InvalidRequestException;
import dev.christopherbell.libs.api.exception.ResourceNotFoundException;
import dev.christopherbell.libs.api.model.Response;
import dev.christopherbell.location.model.ZipCoordinateDetail;
import dev.christopherbell.location.model.ZipCoordinateImportResult;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * General location reference-data APIs.
 */
@RequiredArgsConstructor
@RequestMapping("/api/location")
@RestController
public class LocationController {
  private final ZipCoordinateService zipCoordinateService;

  /**
   * Finds the imported coordinate origin for a ZIP code.
   *
   * @param requestedZipCode five-digit ZIP or ZIP+4 input from the path
   * @return HTTP 200 with coordinate detail
   * @throws InvalidRequestException if the input is not a ZIP or ZIP+4 code
   * @throws ResourceNotFoundException if no imported coordinate exists for the ZIP code
   */
  @GetMapping(value = "/zip/{zipCode}", produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<Response<ZipCoordinateDetail>> findZipCoordinate(
      @PathVariable("zipCode") String requestedZipCode)
      throws InvalidRequestException, ResourceNotFoundException {
    ZipCoordinateDetail coordinate = zipCoordinateService.findCoordinateForZip(requestedZipCode);
    return ResponseEntity.ok(
        Response.<ZipCoordinateDetail>builder()
            .payload(coordinate)
            .success(true)
            .build());
  }

  /**
   * Imports or refreshes the bundled Census ZIP coordinates.
   *
   * @return HTTP 200 with import counts
   */
  @PostMapping(value = "/zip/import/census", produces = MediaType.APPLICATION_JSON_VALUE)
  @PreAuthorize("@permissionService.hasAuthority('ADMIN')")
  public ResponseEntity<Response<ZipCoordinateImportResult>> importCensusZipCoordinates() {
    ZipCoordinateImportResult importResult = zipCoordinateService.importCensusZipCoordinates();
    return ResponseEntity.ok(
        Response.<ZipCoordinateImportResult>builder()
            .payload(importResult)
            .success(true)
            .build());
  }
}
