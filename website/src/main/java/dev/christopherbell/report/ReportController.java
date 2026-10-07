package dev.christopherbell.report;

import dev.christopherbell.libs.api.exception.InvalidRequestException;
import dev.christopherbell.libs.api.exception.ResourceNotFoundException;
import dev.christopherbell.libs.api.model.Response;
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
import java.time.Instant;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * API controller for post reports.
 */
@RestController
@RequestMapping("/api/reports")
public class ReportController {
  private static final String V20250903 = "/2025-09-03";
  private static final String V20260726 = "/2026-07-26";
  private final ReportService reportService;
  private final ReportQueryPort reportQueries;

  public ReportController(ReportService reportService, ReportQueryPort reportQueries) {
    this.reportService = reportService;
    this.reportQueries = reportQueries;
  }

  /** Accepts a report through the original API, which returns no payload. */
  @PostMapping(value = V20250903, produces = MediaType.APPLICATION_JSON_VALUE)
  @PreAuthorize("@permissionService.hasAuthority('USER')")
  public ResponseEntity<Response<Void>> submitReport(
      @Valid @RequestBody ReportCreateRequest createRequest
  ) throws InvalidRequestException, ResourceNotFoundException {
    reportService.submitReport(createRequest);
    return ResponseEntity.ok(Response.<Void>builder()
        .success(true)
        .build());
  }

  /** Accepts a report and returns the canonical persisted report resource. */
  @PostMapping(value = V20260726, produces = MediaType.APPLICATION_JSON_VALUE)
  @PreAuthorize("@permissionService.hasAuthority('USER')")
  public ResponseEntity<Response<PostReport>> submitReportReturningIt(
      @Valid @RequestBody ReportCreateRequest createRequest
  ) throws InvalidRequestException, ResourceNotFoundException {
    PostReport submittedReport = reportService.submitReport(createRequest);
    return ResponseEntity.ok(Response.<PostReport>builder()
        .payload(submittedReport)
        .success(true)
        .build());
  }

  /** Returns one filtered page of the report queue for admins. */
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
    ReportPage reportPage = reportQueries.query(new ReportQuery(
        status, reportType, targetType, reporter, from, to, page, size));
    return ResponseEntity.ok(Response.<ReportPage>builder()
        .payload(reportPage)
        .success(true)
        .build());
  }

  /** Lists the newest reports for admin review. */
  @GetMapping(value = V20250903, produces = MediaType.APPLICATION_JSON_VALUE)
  @PreAuthorize("@permissionService.hasAuthority('ADMIN')")
  public ResponseEntity<Response<List<PostReport>>> listReportsForReview() {
    List<PostReport> reportsForReview = reportService.listReportsForReview();
    return ResponseEntity.ok(Response.<List<PostReport>>builder()
        .payload(reportsForReview)
        .success(true)
        .build());
  }

  /** Applies an admin resolution or reopen to a report. */
  @PostMapping(value = V20250903 + "/{reportId}/resolve", produces = MediaType.APPLICATION_JSON_VALUE)
  @PreAuthorize("@permissionService.hasAuthority('ADMIN')")
  public ResponseEntity<Response<PostReport>> resolveReport(
      @PathVariable String reportId,
      @Valid @RequestBody ReportResolveRequest resolveRequest
  ) throws InvalidRequestException, ResourceNotFoundException {
    PostReport resolvedReport = reportService.resolveReport(reportId, resolveRequest);
    return ResponseEntity.ok(Response.<PostReport>builder()
        .payload(resolvedReport)
        .success(true)
        .build());
  }
}
