package dev.christopherbell.report.submission;

import dev.christopherbell.account.AccountRepository;
import dev.christopherbell.account.model.Account;
import dev.christopherbell.libs.api.exception.InvalidRequestException;
import dev.christopherbell.libs.api.exception.ResourceNotFoundException;
import dev.christopherbell.permission.PermissionService;
import dev.christopherbell.post.PostRepository;
import dev.christopherbell.post.model.Post;
import dev.christopherbell.report.ReportRepository;
import dev.christopherbell.report.model.PostReport;
import dev.christopherbell.report.model.ReportCreateRequest;
import dev.christopherbell.report.model.ReportStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Owns report creation so user-facing report submission stays separate from
 * admin moderation actions.
 */
@RequiredArgsConstructor
@Service
public class ReportSubmissionService {
  private final PostRepository postRepository;
  private final AccountRepository accountRepository;
  private final PermissionService permissionService;
  private final ReportRepository reportRepository;

  /**
   * Validates a report request and stores an open report with post and account
   * metadata captured at submission time.
   */
  public PostReport submitReport(ReportCreateRequest request)
      throws InvalidRequestException, ResourceNotFoundException {
    validateRequest(request);

    String reporterId = permissionService.getSelfId();
    Account reporter = accountRepository.findById(reporterId)
        .orElseThrow(() -> new ResourceNotFoundException("Reporter not found."));
    Post post = postRepository.findById(request.postId())
        .orElseThrow(() -> new ResourceNotFoundException("Reported post not found."));
    Account reported = accountRepository.findById(post.getAccountId())
        .orElseThrow(() -> new ResourceNotFoundException("Reported user not found."));

    PostReport report = PostReport.builder()
        .postId(post.getId())
        .postText(post.getText())
        .reportedAccountId(reported.getId())
        .reportedUsername(reported.getUsername())
        .reporterAccountId(reporter.getId())
        .reporterUsername(reporter.getUsername())
        .reason(request.reason())
        .details(request.details())
        .status(ReportStatus.OPEN)
        .build();

    return reportRepository.save(report);
  }

  private void validateRequest(ReportCreateRequest request) throws InvalidRequestException {
    if (request == null || request.postId() == null || request.postId().isBlank()) {
      throw new InvalidRequestException("Post id is required.");
    }
    if (request.reason() == null || request.reason().isBlank()) {
      throw new InvalidRequestException("Report reason is required.");
    }
  }
}
