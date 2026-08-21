package dev.christopherbell.report.moderation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.christopherbell.account.AccountRepository;
import dev.christopherbell.account.auth.AccountSessionRevoker;
import dev.christopherbell.admin.activity.AdminActivityService;
import dev.christopherbell.libs.moderation.ModerationAuditCommand;
import dev.christopherbell.libs.api.exception.InvalidRequestException;
import dev.christopherbell.libs.api.exception.ServiceUnavailableException;
import dev.christopherbell.permission.PermissionService;
import dev.christopherbell.post.PostRepository;
import dev.christopherbell.report.ReportRepository;
import dev.christopherbell.report.model.PostReport;
import dev.christopherbell.report.model.ReportResolution;
import dev.christopherbell.report.model.ReportResolveRequest;
import dev.christopherbell.report.model.ReportStatus;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ReportModerationLifecycleTest {
  @Mock private PostRepository posts;
  @Mock private AccountRepository accounts;
  @Mock private AdminActivityService activity;
  @Mock private PermissionService permissions;
  @Mock private ReportRepository reports;
  @Mock private AccountSessionRevoker sessionRevoker;
  private ReportModerationService service;

  @BeforeEach
  void setUp() {
    service = new ReportModerationService(posts, accounts, activity, permissions, reports, sessionRevoker);
  }

  @Test
  @DisplayName("Resolving an open report releases its sparse dedupe key")
  void resolveReport_clearsOpenDedupeKey() throws Exception {
    var report = report("resolved-later", ReportStatus.OPEN);
    report.setOpenDedupeKey("open-key");
    when(reports.findById("resolved-later")).thenReturn(Optional.of(report));
    when(permissions.getSelfId()).thenReturn("admin");
    when(reports.save(report)).thenReturn(report);

    service.resolveReport(
        "resolved-later",
        new ReportResolveRequest(ReportResolution.CLOSE_NO_ACTION, "No policy violation."));

    assertThat(report.getOpenDedupeKey()).isNull();
    assertThat(report.getStatus()).isEqualTo(ReportStatus.RESOLVED);
    var audit = ArgumentCaptor.forClass(ModerationAuditCommand.class);
    verify(activity).recordModeration(audit.capture());
    assertThat(audit.getValue().reason()).isEqualTo("No policy violation.");
    assertThat(audit.getValue().beforeValues()).containsEntry("status", "OPEN");
    assertThat(audit.getValue().afterValues())
        .containsEntry("status", "RESOLVED")
        .containsEntry("resolution", "CLOSE_NO_ACTION");
    verify(sessionRevoker, never()).revokeAll(any());
  }

  @Test
  @DisplayName("Reopen returns the existing open report when its dedupe key is occupied")
  void reopen_whenAnotherOpenReportExists_returnsExistingWithoutSaving() throws Exception {
    var resolved = report("resolved", ReportStatus.RESOLVED);
    var existing = report("existing", ReportStatus.OPEN);
    when(reports.findById("resolved")).thenReturn(Optional.of(resolved));
    when(reports.findByOpenDedupeKey(any())).thenReturn(Optional.of(existing));

    var result = service.resolveReport(
        "resolved", new ReportResolveRequest(ReportResolution.REOPEN, "Needs another review."));

    assertThat(result).isSameAs(existing);
    verify(reports, never()).save(resolved);
    verify(activity, never()).recordModeration(any());
  }

  @Test
  @DisplayName("Resolution rejects missing and oversized reasons before loading or saving")
  void resolveReport_rejectsInvalidReasonBeforeMutation() {
    assertThrows(InvalidRequestException.class, () -> service.resolveReport(
        "report-1", new ReportResolveRequest(ReportResolution.CLOSE_NO_ACTION, " ")));
    assertThrows(InvalidRequestException.class, () -> service.resolveReport(
        "report-1", new ReportResolveRequest(ReportResolution.CLOSE_NO_ACTION, "x".repeat(501))));

    verify(reports, never()).findById(any());
    verify(reports, never()).save(any());
  }

  @Test
  @DisplayName("Resolved report retry completes its durable pending audit")
  void resolveReport_whenAuditFails_retryCompletesPendingAudit() throws Exception {
    var report = report("report-1", ReportStatus.OPEN);
    var request = new ReportResolveRequest(
        ReportResolution.CLOSE_NO_ACTION, "Confirmed review");
    when(reports.findById("report-1")).thenReturn(Optional.of(report));
    when(permissions.getSelfId()).thenReturn("admin");
    when(reports.save(report)).thenReturn(report);
    when(activity.recordModeration(any()))
        .thenThrow(new ServiceUnavailableException(
            "Moderation audit is temporarily unavailable.", new IllegalStateException("down")))
        .thenAnswer(invocation -> null);

    assertThrows(ServiceUnavailableException.class, () -> service.resolveReport("report-1", request));
    assertThat(report.getStatus()).isEqualTo(ReportStatus.RESOLVED);
    assertThat(report.getPendingModerationAudit()).isNotNull();

    service.resolveReport("report-1", request);

    assertThat(report.getPendingModerationAudit()).isNull();
    verify(activity, org.mockito.Mockito.times(2)).recordModeration(any());
  }

  private PostReport report(String id, ReportStatus status) {
    return PostReport.builder()
        .id(id)
        .postId("post-1")
        .reporterAccountId("reporter")
        .reportedAccountId("author")
        .status(status)
        .build();
  }
}
