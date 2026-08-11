package dev.christopherbell.report.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.christopherbell.libs.api.exception.InvalidRequestException;
import dev.christopherbell.report.model.PostReport;
import dev.christopherbell.report.model.ReportStatus;
import dev.christopherbell.report.model.ReportTargetType;
import dev.christopherbell.report.model.ReportType;
import dev.christopherbell.report.ReportRepository;
import java.time.Instant;
import java.util.List;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;

@ExtendWith(MockitoExtension.class)
class ReportQueryServiceTest {
  @Mock private MongoTemplate mongo;
  @Mock private ReportRepository reports;
  private ReportQueryService service;

  @BeforeEach
  void setUp() {
    service = new ReportQueryService(
        dev.christopherbell.configuration.mongo.domain.DomainMongoOperationsTestFactory.create(mongo),
        reports);
  }

  @Test
  @DisplayName("Report queue applies every filter and stable page ordering")
  void query_appliesFiltersAndStableSort() throws Exception {
    var report = PostReport.builder().id("r1").status(ReportStatus.OPEN).build();
    var reportEnvelope =
        dev.christopherbell.configuration.mongo.domain.DomainMongoOperationsTestFactory
            .envelope(mongo, report);
    when(mongo.count(any(Query.class), eq(Document.class), eq("content"))).thenReturn(1L);
    when(mongo.find(any(Query.class), eq(Document.class), eq("content")))
        .thenReturn(List.of(reportEnvelope));
    var query = new ReportQuery(
        ReportStatus.OPEN,
        ReportType.SPAM,
        ReportTargetType.POST,
        "reader.*",
        Instant.parse("2026-07-01T00:00:00Z"),
        Instant.parse("2026-07-26T23:59:59Z"),
        1,
        25);

    var result = service.query(query);

    var captured = ArgumentCaptor.forClass(Query.class);
    verify(mongo).find(captured.capture(), eq(Document.class), eq("content"));
    assertThat(captured.getValue().getQueryObject().toString())
        .contains("_kind=post_report", "payload.status=OPEN", "payload.reportType=SPAM",
            "payload.targetType=POST")
        .contains("\\Qreader.*\\E", "payload.createdOn", "$gte", "$lte");
    assertThat(captured.getValue().getSortObject().toString())
        .contains("payload.createdOn=-1", "_id.legacyId=-1");
    assertThat(captured.getValue().getSkip()).isEqualTo(25);
    assertThat(result.totalElements()).isEqualTo(1);
  }

  @Test
  @DisplayName("Report date ranges require both inclusive bounds in chronological order")
  void query_rejectsPartialOrReversedDateRange() {
    assertThrows(InvalidRequestException.class, () -> service.query(new ReportQuery(
        null, null, null, null, Instant.now(), null, 0, 25)));
    assertThrows(InvalidRequestException.class, () -> service.query(new ReportQuery(
        null, null, null, null,
        Instant.parse("2026-07-27T00:00:00Z"),
        Instant.parse("2026-07-26T00:00:00Z"), 0, 25)));
  }

  @Test
  @DisplayName("Report queue rejects unbounded paging and reporter input")
  void query_rejectsUnsafeBounds() {
    assertThrows(InvalidRequestException.class, () -> service.query(new ReportQuery(
        null, null, null, "x".repeat(101), null, null, 0, 25)));
    assertThrows(InvalidRequestException.class, () -> service.query(new ReportQuery(
        null, null, null, null, null, null, -1, 25)));
    assertThrows(InvalidRequestException.class, () -> service.query(new ReportQuery(
        null, null, null, null, null, null, 0, 101)));
  }
}
