package dev.christopherbell.report.submission;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.christopherbell.account.AccountRepository;
import dev.christopherbell.account.model.Account;
import dev.christopherbell.permission.PermissionService;
import dev.christopherbell.post.PostRepository;
import dev.christopherbell.post.model.Post;
import dev.christopherbell.report.ReportRepository;
import dev.christopherbell.report.model.PostReport;
import dev.christopherbell.report.model.ReportCreateRequest;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;

@ExtendWith(MockitoExtension.class)
class ReportSubmissionServiceTest {
  @Mock private PostRepository posts;
  @Mock private AccountRepository accounts;
  @Mock private PermissionService permissions;
  @Mock private ReportRepository reports;
  private ReportSubmissionService service;

  @BeforeEach
  void setUp() {
    service = new ReportSubmissionService(posts, accounts, permissions, reports);
    when(permissions.getSelfId()).thenReturn("reporter");
    when(accounts.findById("reporter"))
        .thenReturn(Optional.of(Account.builder().id("reporter").username("reader").build()));
    when(posts.findById("post-1"))
        .thenReturn(Optional.of(Post.builder().id("post-1").accountId("author").text("text").build()));
    when(accounts.findById("author"))
        .thenReturn(Optional.of(Account.builder().id("author").username("writer").build()));
  }

  @Test
  @DisplayName("Sequential duplicate submission returns the existing open report")
  void submitReport_whenOpenReportExists_returnsExisting() throws Exception {
    var existing = PostReport.builder().id("report-1").openDedupeKey("key").build();
    when(reports.findByOpenDedupeKey(any())).thenReturn(Optional.of(existing));

    var result = service.submitReport(new ReportCreateRequest("post-1", "spam", null));

    assertThat(result).isSameAs(existing);
    verify(reports, never()).save(any());
  }

  @Test
  @DisplayName("Concurrent duplicate-key races resolve to the winning open report")
  void submitReport_whenSaveLosesRace_returnsWinner() throws Exception {
    var winner = PostReport.builder().id("winner").openDedupeKey("key").build();
    when(reports.findByOpenDedupeKey(any()))
        .thenReturn(Optional.empty(), Optional.of(winner));
    when(reports.save(any())).thenThrow(new DuplicateKeyException("duplicate"));

    var result = service.submitReport(new ReportCreateRequest("post-1", "spam", "details"));

    assertThat(result).isSameAs(winner);
  }

  @Test
  @DisplayName("A resolved report releases its key so a new open report can be created")
  void submitReport_whenNoOpenReport_savesTypedTarget() throws Exception {
    when(reports.findByOpenDedupeKey(any())).thenReturn(Optional.empty());
    when(reports.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

    var result = service.submitReport(new ReportCreateRequest("post-1", "harassment", null));

    assertThat(result.getOpenDedupeKey()).isNotBlank();
    assertThat(result.getReportType().name()).isEqualTo("HARASSMENT");
    assertThat(result.getTargetType().name()).isEqualTo("POST");
  }
}
