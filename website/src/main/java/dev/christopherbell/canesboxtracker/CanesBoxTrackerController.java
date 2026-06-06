package dev.christopherbell.canesboxtracker;

import dev.christopherbell.canesboxtracker.model.CanesBoxTrackerHistory;
import dev.christopherbell.canesboxtracker.model.CanesBoxManualPriceRequest;
import dev.christopherbell.canesboxtracker.model.CanesBoxPriceReviewRequest;
import dev.christopherbell.canesboxtracker.model.CanesBoxWeeklyPriceDetail;
import dev.christopherbell.libs.api.APIVersion;
import dev.christopherbell.libs.api.model.Response;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * API for Raising Canes Box Index price history and admin collection.
 */
@RequiredArgsConstructor
@RequestMapping("/api/canes-box-tracker" + APIVersion.V20260604)
@RestController
public class CanesBoxTrackerController {
  private final CanesBoxTrackerService service;

  /**
   * Gets stored weekly Box Combo price history.
   */
  @GetMapping(value = "/history", produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<Response<CanesBoxTrackerHistory>> getHistory() {
    return new ResponseEntity<>(
        Response.<CanesBoxTrackerHistory>builder()
            .payload(service.getHistory())
            .success(true)
            .build(),
        HttpStatus.OK);
  }

  /**
   * Forces a current-week index collection for Back Office admins.
   */
  @PostMapping(value = "/collect", produces = MediaType.APPLICATION_JSON_VALUE)
  @PreAuthorize("@permissionService.hasAuthority('ADMIN')")
  public ResponseEntity<Response<CanesBoxWeeklyPriceDetail>> collectCurrentWeek() {
    return new ResponseEntity<>(
        Response.<CanesBoxWeeklyPriceDetail>builder()
            .payload(service.collectCurrentWeekForAdmin())
            .success(true)
            .build(),
        HttpStatus.OK);
  }

  /**
   * Approves a provisional metro datapoint so it can count toward the index.
   */
  @PostMapping(value = "/{weekStartDate}/metros/{metroName}/approve", produces = MediaType.APPLICATION_JSON_VALUE)
  @PreAuthorize("@permissionService.hasAuthority('ADMIN')")
  public ResponseEntity<Response<CanesBoxWeeklyPriceDetail>> approveMetroPrice(
      @PathVariable String weekStartDate,
      @PathVariable String metroName,
      @RequestBody CanesBoxPriceReviewRequest request
  ) {
    return new ResponseEntity<>(
        Response.<CanesBoxWeeklyPriceDetail>builder()
            .payload(service.approveMetroPrice(weekStartDate, metroName, request.note()))
            .success(true)
            .build(),
        HttpStatus.OK);
  }

  /**
   * Rejects a metro datapoint so it is visible as excluded but not averaged.
   */
  @PostMapping(value = "/{weekStartDate}/metros/{metroName}/reject", produces = MediaType.APPLICATION_JSON_VALUE)
  @PreAuthorize("@permissionService.hasAuthority('ADMIN')")
  public ResponseEntity<Response<CanesBoxWeeklyPriceDetail>> rejectMetroPrice(
      @PathVariable String weekStartDate,
      @PathVariable String metroName,
      @RequestBody CanesBoxPriceReviewRequest request
  ) {
    return new ResponseEntity<>(
        Response.<CanesBoxWeeklyPriceDetail>builder()
            .payload(service.rejectMetroPrice(weekStartDate, metroName, request.note()))
            .success(true)
            .build(),
        HttpStatus.OK);
  }

  /**
   * Stores an admin-entered manually verified current-week price.
   */
  @PostMapping(value = "/manual-prices", produces = MediaType.APPLICATION_JSON_VALUE)
  @PreAuthorize("@permissionService.hasAuthority('ADMIN')")
  public ResponseEntity<Response<CanesBoxWeeklyPriceDetail>> recordManualVerifiedPrice(
      @RequestBody CanesBoxManualPriceRequest request
  ) {
    return new ResponseEntity<>(
        Response.<CanesBoxWeeklyPriceDetail>builder()
            .payload(service.recordManualVerifiedPrice(
                request.metroName(),
                request.price(),
                request.sourceUrl(),
                request.note()))
            .success(true)
            .build(),
        HttpStatus.OK);
  }
}
