package dev.christopherbell.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.christopherbell.admin.activity.AdminActivityPage;
import dev.christopherbell.admin.activity.AdminActivityQuery;
import dev.christopherbell.admin.activity.AdminActivityQueryService;
import dev.christopherbell.admin.activity.MongoAdminActivityQueryRepository;
import dev.christopherbell.admin.model.AdminActivity;
import dev.christopherbell.libs.api.exception.InvalidRequestException;
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
class AdminActivityQueryServiceTest {
  @Mock private MongoTemplate mongo;
  private AdminActivityQueryService service;

  @BeforeEach
  void setUp() {
    service = new AdminActivityQueryService(new MongoAdminActivityQueryRepository(
        dev.christopherbell.configuration.mongo.domain.DomainMongoOperationsTestFactory.create(mongo)));
  }

  @Test
  @DisplayName("Audit pages filter action target actor and inclusive dates with stable order")
  void query_appliesAllFilters() throws Exception {
    var activity = AdminActivity.builder().id("a1").build();
    var activityEnvelope =
        dev.christopherbell.configuration.mongo.domain.DomainMongoOperationsTestFactory
            .envelope(mongo, activity);
    when(mongo.count(any(Query.class), eq(Document.class), eq("admin_activity"))).thenReturn(1L);
    when(mongo.find(any(Query.class), eq(Document.class), eq("admin_activity")))
        .thenReturn(List.of(activityEnvelope));
    var request = new AdminActivityQuery(
        "REPORT_RESOLVED", "REPORT", "admin.*",
        Instant.parse("2026-07-01T00:00:00Z"),
        Instant.parse("2026-07-26T23:59:59Z"), 1, 25);

    AdminActivityPage result = service.query(request);

    var query = ArgumentCaptor.forClass(Query.class);
    verify(mongo).find(query.capture(), eq(Document.class), eq("admin_activity"));
    assertThat(query.getValue().getQueryObject().toString())
        .contains("_kind=admin_activity", "payload.action=REPORT_RESOLVED",
            "payload.targetType=REPORT", "\\Qadmin.*\\E")
        .contains("$gte", "$lte");
    assertThat(query.getValue().getSortObject().toString())
        .contains("payload.createdOn=-1", "_id.legacyId=-1");
    assertThat(result.totalElements()).isEqualTo(1);
  }

  @Test
  @DisplayName("Audit pages reject unsafe filters and unpaired dates")
  void query_rejectsUnsafePartitions() {
    assertThrows(InvalidRequestException.class, () -> service.query(new AdminActivityQuery(
        "x".repeat(65), null, null, null, null, 0, 25)));
    assertThrows(InvalidRequestException.class, () -> service.query(new AdminActivityQuery(
        null, null, null, Instant.now(), null, 0, 25)));
    assertThrows(InvalidRequestException.class, () -> service.query(new AdminActivityQuery(
        null, null, null, null, null, -1, 25)));
  }
}
