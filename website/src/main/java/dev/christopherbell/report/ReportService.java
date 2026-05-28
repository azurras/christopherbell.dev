package dev.christopherbell.report;

import dev.christopherbell.libs.api.exception.InvalidRequestException;
import dev.christopherbell.libs.api.exception.ResourceNotFoundException;
import dev.christopherbell.report.moderation.ReportModerationService;
import dev.christopherbell.report.model.PostReport;
import dev.christopherbell.report.model.ReportCreateRequest;
import dev.christopherbell.report.model.ReportResolveRequest;
import dev.christopherbell.report.submission.ReportSubmissionService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Facade that keeps the report controller stable while subfeature services own
 * the separate user-submission and admin-moderation workflows.
 */
@RequiredArgsConstructor
@Service
public class ReportService {
  private final ReportSubmissionService reportSubmissionService;
  private final ReportModerationService reportModerationService;

  /**
   * Creates a user report for a post.
   */
  public PostReport submitReport(ReportCreateRequest request)
      throws InvalidRequestException, ResourceNotFoundException {
    return reportSubmissionService.submitReport(request);
  }

  /**
   * Returns reports for admin review, newest first.
   */
  public List<PostReport> getReports() {
    return reportModerationService.getReports();
  }

  /**
   * Applies an admin moderation decision to a report.
   */
  public PostReport resolveReport(String reportId, ReportResolveRequest request)
      throws InvalidRequestException, ResourceNotFoundException {
    return reportModerationService.resolveReport(reportId, request);
  }
}
