package dev.christopherbell.report;

import dev.christopherbell.libs.api.model.Response;
import dev.christopherbell.libs.api.exception.InvalidRequestException;
import dev.christopherbell.libs.api.exception.ResourceNotFoundException;
import dev.christopherbell.report.model.PostReport;
import dev.christopherbell.report.model.ReportCreateRequest;
import dev.christopherbell.report.model.ReportResolveRequest;
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

/**
 * API controller for post reports.
 */
@RestController
@RequestMapping("/api/reports")
public class ReportController {
  private static final String V20250903 = "/2025-09-03";
  private final ReportService reportService;

  public ReportController(ReportService reportService) {
    this.reportService = reportService;
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
      @RequestBody ReportResolveRequest request
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
