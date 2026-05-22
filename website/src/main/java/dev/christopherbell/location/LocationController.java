package dev.christopherbell.location;

import dev.christopherbell.libs.api.exception.InvalidRequestException;
import dev.christopherbell.libs.api.exception.ResourceNotFoundException;
import dev.christopherbell.libs.api.model.Response;
import dev.christopherbell.location.model.ZipCoordinateDetail;
import dev.christopherbell.location.model.ZipCoordinateImportResult;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
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
   * Gets a ZIP coordinate origin from imported Location data.
   *
   * @param zipCode five-digit ZIP or ZIP+4 input
   * @return HTTP 200 with coordinate detail
   */
  @GetMapping(value = "/zip/{zipCode}", produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<Response<ZipCoordinateDetail>> getZipCoordinate(@PathVariable String zipCode)
      throws InvalidRequestException, ResourceNotFoundException {
    return new ResponseEntity<>(
        Response.<ZipCoordinateDetail>builder()
            .payload(zipCoordinateService.getZipCoordinate(zipCode))
            .success(true)
            .build(),
        HttpStatus.OK);
  }

  /**
   * Imports or refreshes bundled Census ZIP coordinates.
   *
   * @return HTTP 200 with import counts
   */
  @PostMapping(value = "/zip/import/census", produces = MediaType.APPLICATION_JSON_VALUE)
  @PreAuthorize("@permissionService.hasAuthority('ADMIN')")
  public ResponseEntity<Response<ZipCoordinateImportResult>> importCensusZipCoordinates() {
    return new ResponseEntity<>(
        Response.<ZipCoordinateImportResult>builder()
            .payload(zipCoordinateService.importCensusZipCoordinates())
            .success(true)
            .build(),
        HttpStatus.OK);
  }
}
