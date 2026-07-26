package dev.christopherbell.report.moderation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.christopherbell.account.AccountRepository;
import dev.christopherbell.admin.activity.AdminActivityService;
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
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ReportModerationLifecycleTest {
  @Mock private PostRepository posts;
  @Mock private AccountRepository accounts;
  @Mock private AdminActivityService activity;
  @Mock private PermissionService permissions;
  @Mock private ReportRepository reports;
  private ReportModerationService service;

  @BeforeEach
  void setUp() {
    service = new ReportModerationService(posts, accounts, activity, permissions, reports);
  }

  @Test
  @DisplayName("Resolving an open report releases its sparse dedupe key")
  void resolveReport_clearsOpenDedupeKey() throws Exception {
    var report = report("resolved-later", ReportStatus.OPEN);
    report.setOpenDedupeKey("open-key");
    when(reports.findById("resolved-later")).thenReturn(Optional.of(report));
    when(permissions.getSelfId()).thenReturn("admin");
    when(reports.save(report)).thenReturn(report);

    service.resolveReport("resolved-later", new ReportResolveRequest(ReportResolution.CLOSE_NO_ACTION));

    assertThat(report.getOpenDedupeKey()).isNull();
    assertThat(report.getStatus()).isEqualTo(ReportStatus.RESOLVED);
  }

  @Test
  @DisplayName("Reopen returns the existing open report when its dedupe key is occupied")
  void reopen_whenAnotherOpenReportExists_returnsExistingWithoutSaving() throws Exception {
    var resolved = report("resolved", ReportStatus.RESOLVED);
    var existing = report("existing", ReportStatus.OPEN);
    when(reports.findById("resolved")).thenReturn(Optional.of(resolved));
    when(reports.findByOpenDedupeKey(any())).thenReturn(Optional.of(existing));

    var result = service.resolveReport("resolved", new ReportResolveRequest(ReportResolution.REOPEN));

    assertThat(result).isSameAs(existing);
    verify(reports, never()).save(resolved);
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
