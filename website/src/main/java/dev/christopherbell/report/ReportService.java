package dev.christopherbell.report;

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
      return reportRepository.save(report);
    }

    if (report.getStatus() == ReportStatus.RESOLVED) {
      return report;
    }

    if (request.resolution() == ReportResolution.DELETE_POST
        || request.resolution() == ReportResolution.DELETE_POST_AND_SUSPEND_USER) {
      postRepository.findById(report.getPostId()).ifPresent(postRepository::delete);
    }

    if (request.resolution() == ReportResolution.DELETE_POST_AND_SUSPEND_USER) {
      accountRepository.findById(report.getReportedAccountId()).ifPresent(account -> {
        account.setStatus(AccountStatus.SUSPENDED);
        accountRepository.save(account);
      });
    }

    report.setStatus(ReportStatus.RESOLVED);
    report.setResolution(request.resolution());
    report.setResolvedBy(permissionService.getSelfId());
    report.setResolvedOn(Instant.now());
    return reportRepository.save(report);
  }
}
