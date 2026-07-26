package dev.christopherbell.report.moderation;

import dev.christopherbell.account.AccountRepository;
import dev.christopherbell.account.model.Account;
import dev.christopherbell.account.model.AccountStatus;
import dev.christopherbell.admin.activity.AdminActivityService;
import dev.christopherbell.admin.activity.ModerationAuditCommand;
import dev.christopherbell.libs.api.exception.InvalidRequestException;
import dev.christopherbell.libs.api.exception.ResourceNotFoundException;
import dev.christopherbell.permission.PermissionService;
import dev.christopherbell.post.PostRepository;
import dev.christopherbell.report.ReportRepository;
import dev.christopherbell.report.ReportOpenDedupeKey;
import dev.christopherbell.report.model.PostReport;
import dev.christopherbell.report.model.ReportResolution;
import dev.christopherbell.report.model.ReportResolveRequest;
import dev.christopherbell.report.model.ReportStatus;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.dao.DuplicateKeyException;
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
    var page = PageRequest.of(0, 100, Sort.by(Sort.Direction.DESC, "createdOn", "id"));
    return reportRepository.findAllByOrderByCreatedOnDesc(page).stream()
        .peek(this::includeRepeatReportContext)
        .toList();
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
    report = completePendingAudit(report);

    if (request.resolution() == ReportResolution.REOPEN) {
      return reopenReport(report, request.reason());
    }

    if (report.getStatus() == ReportStatus.RESOLVED) {
      includeRepeatReportContext(report);
      return report;
    }

    var auditCommand = reportAuditCommand(
        "REPORT_RESOLVED",
        report,
        request.reason(),
        Map.of(
            "status", stateValue(report.getStatus()),
            "resolution", stateValue(report.getResolution())),
        Map.of(
            "status", ReportStatus.RESOLVED.name(),
            "resolution", request.resolution().name()));
    boolean deletedPost = deletePostIfRequested(report, request.resolution());
    String suspendedUsername = suspendUserIfRequested(report, request.resolution());

    report.setStatus(ReportStatus.RESOLVED);
    report.setOpenDedupeKey(null);
    report.setResolution(request.resolution());
    report.setResolvedBy(permissionService.getSelfId());
    report.setResolvedOn(Instant.now());
    report.setPendingModerationAudit(auditCommand);
    PostReport saved = completePendingAudit(reportRepository.save(report));
    includeRepeatReportContext(saved);
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
    if (request.reason() == null || request.reason().isBlank()
        || request.reason().strip().length() > 500) {
      throw new InvalidRequestException("Moderation reason is required and must be 500 characters or fewer.");
    }
  }

  private PostReport reopenReport(PostReport report, String reason) throws InvalidRequestException {
    String openKey = openKey(report);
    if (openKey != null) {
      var existing = reportRepository.findByOpenDedupeKey(openKey)
          .or(() -> reportRepository.findFirstByReporterAccountIdAndPostIdAndStatus(
              report.getReporterAccountId(), report.getPostId(), ReportStatus.OPEN))
          .filter(open -> !open.getId().equals(report.getId()));
      if (existing.isPresent()) {
        includeRepeatReportContext(existing.get());
        return existing.get();
      }
    }
    var auditCommand = reportAuditCommand(
        "REPORT_REOPENED",
        report,
        reason,
        Map.of(
            "status", stateValue(report.getStatus()),
            "resolution", stateValue(report.getResolution())),
        Map.of("status", ReportStatus.OPEN.name(), "resolution", ""));
    report.setStatus(ReportStatus.OPEN);
    report.setOpenDedupeKey(openKey);
    report.setResolution(null);
    report.setResolvedBy(null);
    report.setResolvedOn(null);
    report.setPendingModerationAudit(auditCommand);
    PostReport saved;
    try {
      saved = reportRepository.save(report);
    } catch (DuplicateKeyException race) {
      if (openKey != null) {
        return reportRepository.findByOpenDedupeKey(openKey).orElseThrow(() -> race);
      }
      throw race;
    }
    saved = completePendingAudit(saved);
    includeRepeatReportContext(saved);
    return saved;
  }

  private PostReport completePendingAudit(PostReport report) {
    var pending = report.getPendingModerationAudit();
    if (pending == null) return report;
    adminActivityService.recordModeration(pending);
    report.setPendingModerationAudit(null);
    return reportRepository.save(report);
  }

  private String openKey(PostReport report) {
    if (report.getReporterAccountId() == null || report.getReporterAccountId().isBlank()
        || report.getPostId() == null || report.getPostId().isBlank()) {
      return null;
    }
    return ReportOpenDedupeKey.forTarget(
        report.getReporterAccountId(),
        dev.christopherbell.report.model.ReportTargetType.POST,
        report.getPostId());
  }

  private void includeRepeatReportContext(PostReport report) {
    if (report == null || report.getReportedAccountId() == null || report.getReportedAccountId().isBlank()) {
      return;
    }
    report.setOpenReportsForAccount(reportRepository.countByReportedAccountIdAndStatus(
        report.getReportedAccountId(),
        ReportStatus.OPEN));
    report.setResolvedReportsForAccount(reportRepository.countByReportedAccountIdAndStatus(
        report.getReportedAccountId(),
        ReportStatus.RESOLVED));
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

  private ModerationAuditCommand reportAuditCommand(
      String action,
      PostReport report,
      String reason,
      Map<String, String> before,
      Map<String, String> after
  ) throws InvalidRequestException {
    var actorId = permissionService.getSelfId();
    var actorUsername = accountRepository.findById(actorId)
        .map(account -> account.getUsername() == null ? actorId : account.getUsername())
        .orElse(actorId);
    return ModerationAuditCommand.create(
        actorId,
        actorUsername,
        action,
        "REPORT",
        report.getId(),
        "Report " + report.getId(),
        reason,
        action.equals("REPORT_REOPENED")
            ? "%s reopened report " + report.getId() + "."
            : "%s resolved report " + report.getId() + ".",
        before,
        after,
        Map.of(
            "source", "back-office",
            "reportId", nullSafe(report.getId()),
            "resolution", after.getOrDefault("resolution", "")));
  }

  private void recordPostDeleted(PostReport report) {
    adminActivityService.record(
        "POST_DELETED",
        "POST",
        report.getPostId(),
        "Post " + report.getPostId(),
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

  private String stateValue(Enum<?> value) {
    return value == null ? "" : value.name();
  }
}
