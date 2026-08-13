package dev.christopherbell.report;

import static org.assertj.core.api.Assertions.assertThat;

import dev.christopherbell.account.model.Account;
import dev.christopherbell.post.model.Post;
import dev.christopherbell.report.model.PostReport;
import dev.christopherbell.report.model.ReportStatus;
import dev.christopherbell.report.model.ReportTargetType;
import dev.christopherbell.report.model.ReportType;
import dev.christopherbell.report.query.ReportQuery;
import dev.christopherbell.report.query.ReportQueryPort;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Shared report repository/query behavior executed against real MongoDB and PostgreSQL. */
interface ReportParityContract {
  String REPORTED = "report-reported";
  String REPORTER = "report-reporter";
  String POST = "report-post";
  Instant NOW = Instant.parse("2026-08-13T23:00:00Z");

  ReportRepository reports();

  ReportQueryPort queries();

  void ensureAccountAndPost(Account reported, Account reporter, Post post);

  @BeforeEach
  default void seedReportDependencies() {
    ensureAccountAndPost(account(REPORTED), account(REPORTER), post());
  }

  @Test
  default void repositoryRoundTripAndFilteredQueuePreserveCounts() throws Exception {
    reports().save(report("report-contract-a", NOW));
    reports().save(report("report-contract-b", NOW.plusSeconds(1)));

    assertThat(reports().findByOpenDedupeKey("report-dedupe-a"))
        .get().extracting(PostReport::getId).isEqualTo("report-contract-a");
    assertThat(reports().countByReportedAccountIdAndStatus(REPORTED, ReportStatus.OPEN))
        .isEqualTo(2);
    var page = queries().query(new ReportQuery(ReportStatus.OPEN, ReportType.SPAM,
        ReportTargetType.POST, "REPORT-REPORTER", NOW.minusSeconds(1),
        NOW.plusSeconds(2), 0, 10));
    assertThat(page.items()).extracting(PostReport::getId)
        .containsExactly("report-contract-b", "report-contract-a");
    assertThat(page.items()).allSatisfy(report ->
        assertThat(report.getOpenReportsForAccount()).isEqualTo(2));
  }

  private static Account account(String id) {
    return Account.builder().id(id).createdOn(NOW).email(id + "@example.test")
        .passwordHash("hash").role(dev.christopherbell.account.model.Role.USER)
        .status(dev.christopherbell.account.model.AccountStatus.ACTIVE).username(id).build();
  }

  private static Post post() {
    return Post.builder().id(POST).accountId(REPORTED).text(POST).rootId(POST).level(0)
        .createdOn(NOW).expiresOn(NOW.plus(Duration.ofDays(2))).likesCount(0)
        .threadReplyLikesCount(0).threadReplyCount(0).build();
  }

  private static PostReport report(String id, Instant createdOn) {
    return PostReport.builder().id(id).postId(POST).postText("text")
        .reportedAccountId(REPORTED).reportedUsername(REPORTED).reporterAccountId(REPORTER)
        .reporterUsername(REPORTER).openDedupeKey(id.replace("contract", "dedupe"))
        .reportType(ReportType.SPAM).targetType(ReportTargetType.POST).reason("spam")
        .status(ReportStatus.OPEN).createdOn(createdOn).build();
  }
}
