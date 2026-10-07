package dev.christopherbell.report.moderation;

import dev.christopherbell.account.AccountRepository;
import dev.christopherbell.account.auth.AccountSessionRevoker;
import dev.christopherbell.account.model.Account;
import dev.christopherbell.account.model.AccountStatus;
import dev.christopherbell.admin.activity.AdminActivityService;
import dev.christopherbell.libs.api.exception.InvalidRequestException;
import dev.christopherbell.libs.api.exception.ResourceNotFoundException;
import dev.christopherbell.libs.moderation.ModerationAuditCommand;
import dev.christopherbell.permission.PermissionService;
import dev.christopherbell.post.PostRepository;
import dev.christopherbell.report.ReportOpenDedupeKey;
import dev.christopherbell.report.ReportRepository;
import dev.christopherbell.report.model.PostReport;
import dev.christopherbell.report.model.ReportResolution;
import dev.christopherbell.report.model.ReportResolveRequest;
import dev.christopherbell.report.model.ReportStatus;
import dev.christopherbell.report.model.ReportTargetType;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

/**
 * Owns admin report queues and resolution actions so moderation side effects do
 * not live in the user-submission flow.
 */
@RequiredArgsConstructor
@Service
public class ReportModerationService {
  private static final int REVIEW_QUEUE_SIZE = 100;
  private static final int MAX_REASON_LENGTH = 500;
  private static final Set<ReportResolution> POST_DELETING_RESOLUTIONS =
      Set.of(ReportResolution.DELETE_POST, ReportResolution.DELETE_POST_AND_SUSPEND_USER);

  private final PostRepository postRepository;
  private final AccountRepository accountRepository;
  private final AdminActivityService adminActivityService;
  private final PermissionService permissionService;
  private final ReportRepository reportRepository;
  private final AccountSessionRevoker sessionRevoker;
  private final Clock clock;

  /**
   * Lists the newest reports for admin review, each with its reported account's open and
   * resolved report counts.
   *
   * @return up to 100 reports, newest first
   */
  public List<PostReport> listReportsForReview() {
    Pageable newestFirst =
        PageRequest.of(0, REVIEW_QUEUE_SIZE, Sort.by(Sort.Direction.DESC, "createdOn", "id"));
    List<PostReport> reportsForReview = reportRepository.findAllByOrderByCreatedOnDesc(newestFirst);
    for (PostReport report : reportsForReview) {
      includeRepeatReportCounts(report);
    }
    return List.copyOf(reportsForReview);
  }

  /**
   * Resolves or reopens a report and applies any requested moderation side
   * effects, including post deletion and account suspension.
   *
   * <p>Resolving an already resolved report returns it unchanged. Reopening returns an existing
   * open report from the same reporter for the same post instead of opening a duplicate.</p>
   *
   * @param reportId the report to act on
   * @param resolveRequest the resolution and the moderator's reason
   * @return the saved report with repeat-report counts
   * @throws InvalidRequestException if the id, resolution or reason is missing or the reason is
   *     longer than 500 characters
   * @throws ResourceNotFoundException if the report does not exist
   */
  public PostReport resolveReport(String reportId, ReportResolveRequest resolveRequest)
      throws InvalidRequestException, ResourceNotFoundException {
    validateResolveRequest(reportId, resolveRequest);
    PostReport storedReport = reportRepository.findById(reportId)
        .orElseThrow(() -> new ResourceNotFoundException("Report not found."));
    PostReport report = completePendingAudit(storedReport);
    if (resolveRequest.resolution() == ReportResolution.REOPEN) {
      return reopenReport(report, resolveRequest.reason());
    }
    if (report.getStatus() == ReportStatus.RESOLVED) {
      includeRepeatReportCounts(report);
      return report;
    }

    ModerationAuditCommand resolvedAudit = reportAuditCommand(
        "REPORT_RESOLVED",
        "%s resolved report " + report.getId() + ".",
        report,
        resolveRequest.reason(),
        Map.of(
            "status", nameOrEmpty(report.getStatus()),
            "resolution", nameOrEmpty(report.getResolution())),
        Map.of(
            "status", ReportStatus.RESOLVED.name(),
            "resolution", resolveRequest.resolution().name()));
    boolean deletedPost = deletePostIfRequested(report, resolveRequest.resolution());
    Optional<String> suspendedUsername = suspendUserIfRequested(report, resolveRequest.resolution());
    report.setStatus(ReportStatus.RESOLVED);
    report.setOpenDedupeKey(null);
    report.setResolution(resolveRequest.resolution());
    report.setResolvedBy(permissionService.getSelfId());
    report.setResolvedOn(Instant.now(clock));
    report.setPendingModerationAudit(resolvedAudit);
    PostReport resolvedReport = completePendingAudit(reportRepository.save(report));
    includeRepeatReportCounts(resolvedReport);
    if (deletedPost) {
      recordPostDeleted(resolvedReport);
    }
    suspendedUsername.ifPresent(username -> recordUserSuspended(resolvedReport, username));
    return resolvedReport;
  }

  private void validateResolveRequest(String reportId, ReportResolveRequest resolveRequest)
      throws InvalidRequestException {
    if (reportId == null || reportId.isBlank()) {
      throw new InvalidRequestException("Report id is required.");
    }
    if (resolveRequest == null || resolveRequest.resolution() == null) {
      throw new InvalidRequestException("Resolution is required.");
    }
    String reason = resolveRequest.reason();
    if (reason == null || reason.isBlank() || reason.strip().length() > MAX_REASON_LENGTH) {
      throw new InvalidRequestException(
          "Moderation reason is required and must be 500 characters or fewer.");
    }
  }

  private PostReport reopenReport(PostReport report, String reason) throws InvalidRequestException {
    String openDedupeKey = openDedupeKeyOf(report);
    if (openDedupeKey != null) {
      Optional<PostReport> otherOpenReport = reportRepository.findByOpenDedupeKey(openDedupeKey)
          .or(() -> reportRepository.findFirstByReporterAccountIdAndPostIdAndStatus(
              report.getReporterAccountId(), report.getPostId(), ReportStatus.OPEN))
          .filter(openReport -> !openReport.getId().equals(report.getId()));
      if (otherOpenReport.isPresent()) {
        includeRepeatReportCounts(otherOpenReport.get());
        return otherOpenReport.get();
      }
    }

    ModerationAuditCommand reopenedAudit = reportAuditCommand(
        "REPORT_REOPENED",
        "%s reopened report " + report.getId() + ".",
        report,
        reason,
        Map.of(
            "status", nameOrEmpty(report.getStatus()),
            "resolution", nameOrEmpty(report.getResolution())),
        Map.of("status", ReportStatus.OPEN.name(), "resolution", ""));
    report.setStatus(ReportStatus.OPEN);
    report.setOpenDedupeKey(openDedupeKey);
    report.setResolution(null);
    report.setResolvedBy(null);
    report.setResolvedOn(null);
    report.setPendingModerationAudit(reopenedAudit);
    PostReport savedReport;
    try {
      savedReport = reportRepository.save(report);
    } catch (DuplicateKeyException concurrentReopen) {
      if (openDedupeKey != null) {
        return reportRepository.findByOpenDedupeKey(openDedupeKey)
            .orElseThrow(() -> concurrentReopen);
      }
      throw concurrentReopen;
    }
    PostReport reopenedReport = completePendingAudit(savedReport);
    includeRepeatReportCounts(reopenedReport);
    return reopenedReport;
  }

  /** Records a moderation audit left pending on the report, then clears and saves it. */
  private PostReport completePendingAudit(PostReport report) {
    ModerationAuditCommand pendingAudit = report.getPendingModerationAudit();
    if (pendingAudit == null) {
      return report;
    }
    adminActivityService.recordModeration(pendingAudit);
    report.setPendingModerationAudit(null);
    return reportRepository.save(report);
  }

  private static String openDedupeKeyOf(PostReport report) {
    if (report.getReporterAccountId() == null || report.getReporterAccountId().isBlank()
        || report.getPostId() == null || report.getPostId().isBlank()) {
      return null;
    }
    return ReportOpenDedupeKey.forTarget(
        report.getReporterAccountId(), ReportTargetType.POST, report.getPostId());
  }

  private void includeRepeatReportCounts(PostReport report) {
    if (report == null || report.getReportedAccountId() == null
        || report.getReportedAccountId().isBlank()) {
      return;
    }
    report.setOpenReportsForAccount(reportRepository.countByReportedAccountIdAndStatus(
        report.getReportedAccountId(), ReportStatus.OPEN));
    report.setResolvedReportsForAccount(reportRepository.countByReportedAccountIdAndStatus(
        report.getReportedAccountId(), ReportStatus.RESOLVED));
  }

  private boolean deletePostIfRequested(PostReport report, ReportResolution resolution) {
    if (!POST_DELETING_RESOLUTIONS.contains(resolution)) {
      return false;
    }
    return postRepository.findById(report.getPostId())
        .map(reportedPost -> {
          postRepository.delete(reportedPost);
          return true;
        })
        .orElse(false);
  }

  /**
   * Suspends the reported account and revokes its sessions when the resolution asks for it.
   *
   * @return the suspended username for the audit trail, falling back to the username captured on
   *     the report when the account no longer exists; empty when no suspension was requested
   */
  private Optional<String> suspendUserIfRequested(PostReport report, ReportResolution resolution) {
    if (resolution != ReportResolution.DELETE_POST_AND_SUSPEND_USER) {
      return Optional.empty();
    }
    Optional<Account> reportedAccount = accountRepository.findById(report.getReportedAccountId());
    String suspendedUsername = reportedAccount
        .map(Account::getUsername)
        .orElse(report.getReportedUsername());
    if (reportedAccount.isPresent()) {
      Account accountToSuspend = reportedAccount.get();
      accountToSuspend.setStatus(AccountStatus.SUSPENDED);
      accountRepository.save(accountToSuspend);
      sessionRevoker.revokeAll(accountToSuspend.getId());
    }
    return Optional.ofNullable(suspendedUsername);
  }

  private ModerationAuditCommand reportAuditCommand(
      String action,
      String summaryTemplate,
      PostReport report,
      String reason,
      Map<String, String> stateBefore,
      Map<String, String> stateAfter
  ) throws InvalidRequestException {
    String actorId = permissionService.getSelfId();
    String actorUsername = accountRepository.findById(actorId)
        .map(actor -> actor.getUsername() == null ? actorId : actor.getUsername())
        .orElse(actorId);
    return ModerationAuditCommand.create(
        actorId,
        actorUsername,
        action,
        "REPORT",
        report.getId(),
        "Report " + report.getId(),
        reason,
        summaryTemplate,
        stateBefore,
        stateAfter,
        Map.of(
            "source", "back-office",
            "reportId", textOrEmpty(report.getId()),
            "resolution", stateAfter.getOrDefault("resolution", "")));
  }

  private void recordPostDeleted(PostReport report) {
    adminActivityService.record(
        "POST_DELETED",
        "POST",
        report.getPostId(),
        "Post " + report.getPostId(),
        "%s deleted post " + report.getPostId(),
        Map.of(
            "reportId", textOrEmpty(report.getId()),
            "postId", textOrEmpty(report.getPostId())));
  }

  private void recordUserSuspended(PostReport report, String suspendedUsername) {
    adminActivityService.record(
        "USER_SUSPENDED",
        "ACCOUNT",
        report.getReportedAccountId(),
        suspendedUsername,
        "%s suspended user " + suspendedUsername,
        Map.of(
            "reportId", textOrEmpty(report.getId()),
            "accountId", textOrEmpty(report.getReportedAccountId()),
            "username", textOrEmpty(suspendedUsername)));
  }

  private static String textOrEmpty(String text) {
    return text == null ? "" : text;
  }

  private static String nameOrEmpty(Enum<?> enumValue) {
    return enumValue == null ? "" : enumValue.name();
  }
}
