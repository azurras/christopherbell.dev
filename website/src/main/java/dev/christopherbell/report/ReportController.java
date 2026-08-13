package dev.christopherbell.report;

import dev.christopherbell.libs.api.model.Response;
import dev.christopherbell.libs.api.exception.InvalidRequestException;
import dev.christopherbell.libs.api.exception.ResourceNotFoundException;
import dev.christopherbell.report.model.PostReport;
import dev.christopherbell.report.model.ReportCreateRequest;
import dev.christopherbell.report.model.ReportResolveRequest;
import dev.christopherbell.report.model.ReportStatus;
import dev.christopherbell.report.model.ReportTargetType;
import dev.christopherbell.report.model.ReportType;
import dev.christopherbell.report.query.ReportPage;
import dev.christopherbell.report.query.ReportQuery;
import dev.christopherbell.report.query.ReportQueryPort;
import jakarta.validation.Valid;
import java.util.List;
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
import org.springframework.web.bind.annotation.RequestParam;
import java.time.Instant;

/**
 * API controller for post reports.
 */
@RestController
@RequestMapping("/api/reports")
public class ReportController {
  private static final String V20250903 = "/2025-09-03";
  private static final String V20260726 = "/2026-07-26";
  private final ReportService reportService;
  private final ReportQueryPort reportQueryService;

  public ReportController(ReportService reportService, ReportQueryPort reportQueryService) {
    this.reportService = reportService;
    this.reportQueryService = reportQueryService;
  }

  @PostMapping(value = V20250903, produces = MediaType.APPLICATION_JSON_VALUE)
  @PreAuthorize("@permissionService.hasAuthority('USER')")
  public ResponseEntity<Response<Void>> createReport(
      @Valid @RequestBody ReportCreateRequest request
  ) throws InvalidRequestException, ResourceNotFoundException {
    reportService.submitReport(request);
    return new ResponseEntity<>(
        Response.<Void>builder()
            .success(true)
            .build(),
        HttpStatus.OK
    );
  }

  /** Accepts a report and returns the canonical persisted report resource. */
  @PostMapping(value = V20260726, produces = MediaType.APPLICATION_JSON_VALUE)
  @PreAuthorize("@permissionService.hasAuthority('USER')")
  public ResponseEntity<Response<PostReport>> createReportVersioned(
      @Valid @RequestBody ReportCreateRequest request
  ) throws InvalidRequestException, ResourceNotFoundException {
    return ResponseEntity.ok(Response.<PostReport>builder()
        .payload(reportService.submitReport(request))
        .success(true)
        .build());
  }

  @GetMapping(value = V20260726, produces = MediaType.APPLICATION_JSON_VALUE)
  @PreAuthorize("@permissionService.hasAuthority('ADMIN')")
  public ResponseEntity<Response<ReportPage>> queryReports(
      @RequestParam(required = false) ReportStatus status,
      @RequestParam(required = false) ReportType reportType,
      @RequestParam(required = false) ReportTargetType targetType,
      @RequestParam(required = false) String reporter,
      @RequestParam(required = false) Instant from,
      @RequestParam(required = false) Instant to,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "25") int size
  ) throws InvalidRequestException {
    var result = reportQueryService.query(new ReportQuery(
        status, reportType, targetType, reporter, from, to, page, size));
    return ResponseEntity.ok(Response.<ReportPage>builder()
        .payload(result)
        .success(true)
        .build());
  }

  @GetMapping(value = V20250903, produces = MediaType.APPLICATION_JSON_VALUE)
  @PreAuthorize("@permissionService.hasAuthority('ADMIN')")
  public ResponseEntity<Response<List<PostReport>>> getReports() {
    return new ResponseEntity<>(
        Response.<List<PostReport>>builder()
            .payload(reportService.getReports())
            .success(true)
            .build(),
        HttpStatus.OK
    );
  }

  @PostMapping(value = V20250903 + "/{reportId}/resolve", produces = MediaType.APPLICATION_JSON_VALUE)
  @PreAuthorize("@permissionService.hasAuthority('ADMIN')")
  public ResponseEntity<Response<PostReport>> resolveReport(
      @PathVariable String reportId,
      @Valid @RequestBody ReportResolveRequest request
  ) throws InvalidRequestException, ResourceNotFoundException {
    return new ResponseEntity<>(
        Response.<PostReport>builder()
            .payload(reportService.resolveReport(reportId, request))
            .success(true)
            .build(),
        HttpStatus.OK
    );
  }
}
