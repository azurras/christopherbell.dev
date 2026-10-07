package dev.christopherbell.report.submission;

import dev.christopherbell.account.AccountRepository;
import dev.christopherbell.account.model.Account;
import dev.christopherbell.libs.api.exception.InvalidRequestException;
import dev.christopherbell.libs.api.exception.ResourceNotFoundException;
import dev.christopherbell.permission.PermissionService;
import dev.christopherbell.post.PostRepository;
import dev.christopherbell.post.model.Post;
import dev.christopherbell.report.ReportRepository;
import dev.christopherbell.report.ReportOpenDedupeKey;
import dev.christopherbell.report.model.PostReport;
import dev.christopherbell.report.model.ReportCreateRequest;
import dev.christopherbell.report.model.ReportStatus;
import dev.christopherbell.report.model.ReportTargetType;
import dev.christopherbell.report.model.ReportType;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.dao.DuplicateKeyException;

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
   * Stores the signed-in user's report on a post, capturing the post text and both usernames at
   * submission time.
   *
   * <p>A reporter has at most one open report per post: an existing one is returned instead of
   * creating another, including when a concurrent submission wins the unique index.</p>
   *
   * @param createRequest the reported post id, reason code and optional details
   * @return the new or existing open report
   * @throws InvalidRequestException if the post id or reason is missing
   * @throws ResourceNotFoundException if the reporter, the post or its author does not exist
   */
  public PostReport submitReport(ReportCreateRequest createRequest)
      throws InvalidRequestException, ResourceNotFoundException {
    validateRequest(createRequest);

    String reporterAccountId = permissionService.getSelfId();
    Account reporter = accountRepository.findById(reporterAccountId)
        .orElseThrow(() -> new ResourceNotFoundException("Reporter not found."));
    Post reportedPost = postRepository.findById(createRequest.postId())
        .orElseThrow(() -> new ResourceNotFoundException("Reported post not found."));
    Account reportedAccount = accountRepository.findById(reportedPost.getAccountId())
        .orElseThrow(() -> new ResourceNotFoundException("Reported user not found."));

    String openDedupeKey =
        ReportOpenDedupeKey.forTarget(reporter.getId(), ReportTargetType.POST, reportedPost.getId());
    Optional<PostReport> existingOpenReport = reportRepository.findByOpenDedupeKey(openDedupeKey)
        .or(() -> reportRepository.findFirstByReporterAccountIdAndPostIdAndStatus(
            reporter.getId(), reportedPost.getId(), ReportStatus.OPEN));
    if (existingOpenReport.isPresent()) {
      return existingOpenReport.get();
    }

    PostReport newReport = PostReport.builder()
        .postId(reportedPost.getId())
        .postText(reportedPost.getText())
        .reportedAccountId(reportedAccount.getId())
        .reportedUsername(reportedAccount.getUsername())
        .reporterAccountId(reporter.getId())
        .reporterUsername(reporter.getUsername())
        .openDedupeKey(openDedupeKey)
        .reportType(ReportType.fromReason(createRequest.reason()))
        .targetType(ReportTargetType.POST)
        .reason(createRequest.reason())
        .details(createRequest.details())
        .status(ReportStatus.OPEN)
        .build();

    try {
      return reportRepository.save(newReport);
    } catch (DuplicateKeyException concurrentSubmission) {
      return reportRepository.findByOpenDedupeKey(openDedupeKey)
          .orElseThrow(() -> concurrentSubmission);
    }
  }

  private static void validateRequest(ReportCreateRequest createRequest)
      throws InvalidRequestException {
    if (createRequest == null || createRequest.postId() == null || createRequest.postId().isBlank()) {
      throw new InvalidRequestException("Post id is required.");
    }
    if (createRequest.reason() == null || createRequest.reason().isBlank()) {
      throw new InvalidRequestException("Report reason is required.");
    }
  }
}
