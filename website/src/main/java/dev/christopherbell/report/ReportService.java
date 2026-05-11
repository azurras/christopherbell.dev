package dev.christopherbell.report;

import dev.christopherbell.admin.AdminActivityService;
import dev.christopherbell.account.AccountRepository;
import dev.christopherbell.account.model.Account;
import dev.christopherbell.account.model.AccountStatus;
import dev.christopherbell.libs.api.exception.InvalidRequestException;
import dev.christopherbell.libs.api.exception.ResourceNotFoundException;
import dev.christopherbell.permission.PermissionService;
import dev.christopherbell.post.PostRepository;
import dev.christopherbell.post.model.Post;
import dev.christopherbell.report.model.PostReport;
import dev.christopherbell.report.model.ReportCreateRequest;
import dev.christopherbell.report.model.ReportResolution;
import dev.christopherbell.report.model.ReportResolveRequest;
import dev.christopherbell.report.model.ReportStatus;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Service for handling post reports.
 */
@RequiredArgsConstructor
@Service
public class ReportService {
  private final PostRepository postRepository;
  private final AccountRepository accountRepository;
  private final AdminActivityService adminActivityService;
  private final PermissionService permissionService;
  private final ReportRepository reportRepository;

  public PostReport submitReport(ReportCreateRequest request)
      throws InvalidRequestException, ResourceNotFoundException {
    if (request == null || request.postId() == null || request.postId().isBlank()) {
      throw new InvalidRequestException("Post id is required.");
    }
    if (request.reason() == null || request.reason().isBlank()) {
      throw new InvalidRequestException("Report reason is required.");
    }

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

  public List<PostReport> getReports() {
    return reportRepository.findAllByOrderByCreatedOnDesc();
  }

  public PostReport resolveReport(String reportId, ReportResolveRequest request)
      throws InvalidRequestException, ResourceNotFoundException {
    if (reportId == null || reportId.isBlank()) {
      throw new InvalidRequestException("Report id is required.");
    }
    if (request == null || request.resolution() == null) {
      throw new InvalidRequestException("Resolution is required.");
    }

    PostReport report = reportRepository.findById(reportId)
        .orElseThrow(() -> new ResourceNotFoundException("Report not found."));

    if (request.resolution() == ReportResolution.REOPEN) {
      report.setStatus(ReportStatus.OPEN);
      report.setResolution(null);
      report.setResolvedBy(null);
      report.setResolvedOn(null);
      var saved = reportRepository.save(report);
      recordReportReopened(saved);
      return saved;
    }

    if (report.getStatus() == ReportStatus.RESOLVED) {
      return report;
    }

    var deletedPost = false;
    if (request.resolution() == ReportResolution.DELETE_POST
        || request.resolution() == ReportResolution.DELETE_POST_AND_SUSPEND_USER) {
      deletedPost = postRepository.findById(report.getPostId())
          .map(post -> {
            postRepository.delete(post);
            return true;
          })
          .orElse(false);
    }

    String suspendedUsername = null;
    if (request.resolution() == ReportResolution.DELETE_POST_AND_SUSPEND_USER) {
      var suspendedAccount = accountRepository.findById(report.getReportedAccountId());
      suspendedUsername = suspendedAccount
          .map(Account::getUsername)
          .orElse(report.getReportedUsername());
      suspendedAccount.ifPresent(account -> {
        account.setStatus(AccountStatus.SUSPENDED);
        accountRepository.save(account);
      });
    }

    report.setStatus(ReportStatus.RESOLVED);
    report.setResolution(request.resolution());
    report.setResolvedBy(permissionService.getSelfId());
    report.setResolvedOn(Instant.now());
    var saved = reportRepository.save(report);
    recordReportResolved(saved);
    if (deletedPost) {
      recordPostDeleted(saved);
    }
    if (suspendedUsername != null) {
      recordUserSuspended(saved, suspendedUsername);
    }
    return saved;
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
