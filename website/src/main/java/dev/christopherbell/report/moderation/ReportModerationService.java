package dev.christopherbell.report.moderation;

import dev.christopherbell.account.AccountRepository;
import dev.christopherbell.account.model.Account;
import dev.christopherbell.account.model.AccountStatus;
import dev.christopherbell.admin.activity.AdminActivityService;
import dev.christopherbell.libs.api.exception.InvalidRequestException;
import dev.christopherbell.libs.api.exception.ResourceNotFoundException;
import dev.christopherbell.permission.PermissionService;
import dev.christopherbell.post.PostRepository;
import dev.christopherbell.report.ReportRepository;
import dev.christopherbell.report.model.PostReport;
import dev.christopherbell.report.model.ReportResolution;
import dev.christopherbell.report.model.ReportResolveRequest;
import dev.christopherbell.report.model.ReportStatus;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Owns admin report queues and resolution actions so moderation side effects do
 * not live in the user-submission flow.
 */
@RequiredArgsConstructor
@Service
public class ReportModerationService {
  private final PostRepository postRepository;
  private final AccountRepository accountRepository;
  private final AdminActivityService adminActivityService;
  private final PermissionService permissionService;
  private final ReportRepository reportRepository;

  /**
   * Returns reports in the order admins need to review them.
   */
  public List<PostReport> getReports() {
    return reportRepository.findAllByOrderByCreatedOnDesc();
  }

  /**
   * Resolves or reopens a report and applies any requested moderation side
   * effects, including post deletion and account suspension.
   */
  public PostReport resolveReport(String reportId, ReportResolveRequest request)
      throws InvalidRequestException, ResourceNotFoundException {
    validateResolveRequest(reportId, request);

    PostReport report = reportRepository.findById(reportId)
        .orElseThrow(() -> new ResourceNotFoundException("Report not found."));

    if (request.resolution() == ReportResolution.REOPEN) {
      return reopenReport(report);
    }

    if (report.getStatus() == ReportStatus.RESOLVED) {
      return report;
    }

    boolean deletedPost = deletePostIfRequested(report, request.resolution());
    String suspendedUsername = suspendUserIfRequested(report, request.resolution());

    report.setStatus(ReportStatus.RESOLVED);
    report.setResolution(request.resolution());
    report.setResolvedBy(permissionService.getSelfId());
    report.setResolvedOn(Instant.now());
    PostReport saved = reportRepository.save(report);
    recordReportResolved(saved);
    if (deletedPost) {
      recordPostDeleted(saved);
    }
    if (suspendedUsername != null) {
      recordUserSuspended(saved, suspendedUsername);
    }
    return saved;
  }

  private void validateResolveRequest(String reportId, ReportResolveRequest request)
      throws InvalidRequestException {
    if (reportId == null || reportId.isBlank()) {
      throw new InvalidRequestException("Report id is required.");
    }
    if (request == null || request.resolution() == null) {
      throw new InvalidRequestException("Resolution is required.");
    }
  }

  private PostReport reopenReport(PostReport report) {
    report.setStatus(ReportStatus.OPEN);
    report.setResolution(null);
    report.setResolvedBy(null);
    report.setResolvedOn(null);
    PostReport saved = reportRepository.save(report);
    recordReportReopened(saved);
    return saved;
  }

  private boolean deletePostIfRequested(PostReport report, ReportResolution resolution) {
    if (resolution != ReportResolution.DELETE_POST
        && resolution != ReportResolution.DELETE_POST_AND_SUSPEND_USER) {
      return false;
    }

    return postRepository.findById(report.getPostId())
        .map(post -> {
          postRepository.delete(post);
          return true;
        })
        .orElse(false);
  }

  private String suspendUserIfRequested(PostReport report, ReportResolution resolution) {
    if (resolution != ReportResolution.DELETE_POST_AND_SUSPEND_USER) {
      return null;
    }

    var suspendedAccount = accountRepository.findById(report.getReportedAccountId());
    String suspendedUsername = suspendedAccount
        .map(Account::getUsername)
        .orElse(report.getReportedUsername());
    suspendedAccount.ifPresent(account -> {
      account.setStatus(AccountStatus.SUSPENDED);
      accountRepository.save(account);
    });
    return suspendedUsername;
  }

  private void recordReportResolved(PostReport report) {
    adminActivityService.record(
        "REPORT_RESOLVED",
        "REPORT",
        report.getId(),
        report.getReason(),
        "%s resolved report " + report.getId(),
        Map.of(
            "reportId", nullSafe(report.getId()),
            "resolution", report.getResolution() == null ? "" : report.getResolution().name()
        ));
  }

  private void recordReportReopened(PostReport report) {
    adminActivityService.record(
        "REPORT_REOPENED",
        "REPORT",
        report.getId(),
        report.getReason(),
        "%s reopened report " + report.getId(),
        Map.of("reportId", nullSafe(report.getId())));
  }

  private void recordPostDeleted(PostReport report) {
    adminActivityService.record(
        "POST_DELETED",
        "POST",
        report.getPostId(),
        report.getPostText(),
        "%s deleted post " + report.getPostId(),
        Map.of(
            "reportId", nullSafe(report.getId()),
            "postId", nullSafe(report.getPostId())
        ));
  }

  private void recordUserSuspended(PostReport report, String username) {
    adminActivityService.record(
        "USER_SUSPENDED",
        "ACCOUNT",
        report.getReportedAccountId(),
        username,
        "%s suspended user " + username,
        Map.of(
            "reportId", nullSafe(report.getId()),
            "accountId", nullSafe(report.getReportedAccountId()),
            "username", nullSafe(username)
        ));
  }

  private String nullSafe(String value) {
    return value == null ? "" : value;
  }
}
