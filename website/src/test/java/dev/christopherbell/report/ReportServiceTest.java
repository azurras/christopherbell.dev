package dev.christopherbell.report;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.christopherbell.account.AccountRepository;
import dev.christopherbell.account.model.Account;
import dev.christopherbell.account.model.AccountStatus;
import dev.christopherbell.account.model.Role;
import dev.christopherbell.admin.activity.AdminActivityService;
import dev.christopherbell.libs.api.exception.InvalidRequestException;
import dev.christopherbell.permission.PermissionService;
import dev.christopherbell.post.PostRepository;
import dev.christopherbell.post.model.Post;
import dev.christopherbell.report.moderation.ReportModerationService;
import dev.christopherbell.report.model.ReportCreateRequest;
import dev.christopherbell.report.model.ReportResolution;
import dev.christopherbell.report.model.ReportResolveRequest;
import dev.christopherbell.report.model.ReportStatus;
import dev.christopherbell.report.submission.ReportSubmissionService;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.mockito.ArgumentCaptor;

class ReportServiceTest {

  @Test
  @DisplayName("Submit report sends email with details")
  void submitReport_sendsEmail() throws Exception {
    PostRepository postRepository = Mockito.mock(PostRepository.class);
    AccountRepository accountRepository = Mockito.mock(AccountRepository.class);
    AdminActivityService adminActivityService = Mockito.mock(AdminActivityService.class);
    PermissionService permissionService = Mockito.mock(PermissionService.class);
    ReportRepository reportRepository = Mockito.mock(ReportRepository.class);

    ReportService service = new ReportService(
        new ReportSubmissionService(
            postRepository,
            accountRepository,
            permissionService,
            reportRepository),
        new ReportModerationService(
            postRepository,
            accountRepository,
            adminActivityService,
            permissionService,
            reportRepository));

    ReportCreateRequest request = new ReportCreateRequest("post-1", "spam", "details");
    Post post = Post.builder()
        .id("post-1")
        .accountId("reported-1")
        .text("Reported text")
        .createdOn(Instant.parse("2026-01-01T00:00:00Z"))
        .build();
    Account reporter = Account.builder()
        .id("reporter-1")
        .username("reporter")
        .email("reporter@example.com")
        .firstName("Report")
        .lastName("User")
        .role(Role.USER)
        .status(AccountStatus.ACTIVE)
        .build();
    Account reported = Account.builder()
        .id("reported-1")
        .username("reported")
        .email("reported@example.com")
        .firstName("Reported")
        .lastName("User")
        .role(Role.USER)
        .status(AccountStatus.ACTIVE)
        .build();

    when(permissionService.getSelfId()).thenReturn("reporter-1");
    when(postRepository.findById("post-1")).thenReturn(Optional.of(post));
    when(accountRepository.findById("reporter-1")).thenReturn(Optional.of(reporter));
    when(accountRepository.findById("reported-1")).thenReturn(Optional.of(reported));
    when(reportRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

    service.submitReport(request);

    ArgumentCaptor<dev.christopherbell.report.model.PostReport> reportCaptor =
        ArgumentCaptor.forClass(dev.christopherbell.report.model.PostReport.class);
    verify(reportRepository).save(reportCaptor.capture());
  }

  @Test
  @DisplayName("Submit report rejects missing post id")
  void submitReport_missingPostId() {
    ReportService service = new ReportService(
        new ReportSubmissionService(
            Mockito.mock(PostRepository.class),
            Mockito.mock(AccountRepository.class),
            Mockito.mock(PermissionService.class),
            Mockito.mock(ReportRepository.class)),
        Mockito.mock(ReportModerationService.class)
    );

    assertThrows(InvalidRequestException.class,
        () -> service.submitReport(new ReportCreateRequest("", "spam", null)));
  }

  @Test
  @DisplayName("Report queue includes repeat report context for the reported account")
  void getReports_includesRepeatReportContext() {
    ReportRepository reportRepository = Mockito.mock(ReportRepository.class);
    var service = new ReportService(
        Mockito.mock(ReportSubmissionService.class),
        new ReportModerationService(
            Mockito.mock(PostRepository.class),
            Mockito.mock(AccountRepository.class),
            Mockito.mock(AdminActivityService.class),
            Mockito.mock(PermissionService.class),
            reportRepository));
    var report = dev.christopherbell.report.model.PostReport.builder()
        .id("r1")
        .reportedAccountId("reported-1")
        .status(ReportStatus.OPEN)
        .build();

    when(reportRepository.findAllByOrderByCreatedOnDesc()).thenReturn(List.of(report));
    when(reportRepository.countByReportedAccountIdAndStatus("reported-1", ReportStatus.OPEN)).thenReturn(2L);
    when(reportRepository.countByReportedAccountIdAndStatus("reported-1", ReportStatus.RESOLVED)).thenReturn(3L);

    var reports = service.getReports();

    assertEquals(2L, reports.get(0).getOpenReportsForAccount());
    assertEquals(3L, reports.get(0).getResolvedReportsForAccount());
  }

  @Test
  @DisplayName("Resolve report deletes post and suspends user")
  void resolveReport_deletesPostAndSuspendsUser() throws Exception {
    PostRepository postRepository = Mockito.mock(PostRepository.class);
    AccountRepository accountRepository = Mockito.mock(AccountRepository.class);
    AdminActivityService adminActivityService = Mockito.mock(AdminActivityService.class);
    PermissionService permissionService = Mockito.mock(PermissionService.class);
    ReportRepository reportRepository = Mockito.mock(ReportRepository.class);

    ReportService service = new ReportService(
        Mockito.mock(ReportSubmissionService.class),
        new ReportModerationService(
            postRepository,
            accountRepository,
            adminActivityService,
            permissionService,
            reportRepository));

    var report = dev.christopherbell.report.model.PostReport.builder()
        .id("r1")
        .postId("p1")
        .postText("bad post")
        .reportedAccountId("u1")
        .reportedUsername("reported")
        .status(ReportStatus.OPEN)
        .build();
    var account = Account.builder().id("u1").status(AccountStatus.ACTIVE).build();
    var post = Post.builder().id("p1").text("bad post").accountId("u1").build();

    when(reportRepository.findById("r1")).thenReturn(Optional.of(report));
    when(permissionService.getSelfId()).thenReturn("admin-1");
    when(postRepository.findById("p1")).thenReturn(Optional.of(post));
    when(accountRepository.findById("u1")).thenReturn(Optional.of(account));
    when(reportRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

    service.resolveReport("r1", new ReportResolveRequest(ReportResolution.DELETE_POST_AND_SUSPEND_USER));

    verify(postRepository).findById("p1");
    verify(accountRepository).save(account);
    verify(adminActivityService).record(
        eq("REPORT_RESOLVED"),
        eq("REPORT"),
        eq("r1"),
        any(),
        any(),
        any());
    verify(adminActivityService).record(
        eq("POST_DELETED"),
        eq("POST"),
        eq("p1"),
        any(),
        any(),
        any());
    verify(adminActivityService).record(
        eq("USER_SUSPENDED"),
        eq("ACCOUNT"),
        eq("u1"),
        any(),
        any(),
        any());
  }
}
