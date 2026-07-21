package dev.christopherbell.sharedfolder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.christopherbell.sharedfolder.audit.SharedFolderAuditEvent;
import dev.christopherbell.sharedfolder.audit.SharedFolderAuditFilter;
import dev.christopherbell.sharedfolder.audit.SharedFolderAuditQueryService;
import dev.christopherbell.sharedfolder.security.SharedFolderAccessService;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.web.server.ResponseStatusException;

class SharedFolderAuditQueryServiceTest {
  @Test
  void adminFiltersBoundedAuditHistoryByAccountActionOutcomePathAndDate() {
    SharedFolderAccessService access = mock(SharedFolderAccessService.class);
    MongoTemplate mongo = mock(MongoTemplate.class);
    var service = new SharedFolderAuditQueryService(access, mongo);
    Instant from = Instant.parse("2026-07-01T00:00:00Z");
    Instant to = Instant.parse("2026-07-18T23:59:59Z");
    var filter = new SharedFolderAuditFilter(
        "account-1", "RECYCLE", "accepted", "docs/report.pdf", from, to, 500);
    when(mongo.find(org.mockito.ArgumentMatchers.any(Query.class),
        eq(SharedFolderAuditEvent.class))).thenReturn(List.of());

    assertThat(service.search(filter)).isEmpty();

    verify(access).requireAdmin();
    var captor = org.mockito.ArgumentCaptor.forClass(Query.class);
    verify(mongo).find(captor.capture(), eq(SharedFolderAuditEvent.class));
    Query query = captor.getValue();
    assertThat(query.getLimit()).isEqualTo(100);
    assertThat(query.getQueryObject().toString())
        .contains("account-1", "RECYCLE", "accepted", "docs/report.pdf", "$gte", "$lte")
        .doesNotContain("A:\\\\Shared", "Authorization", "Bearer");
    assertThat(query.getSortObject().toString()).contains("occurredAt", "-1");
  }

  @Test
  void unsafeOrInvertedFiltersAreRejectedBeforeMongoIsCalled() {
    SharedFolderAccessService access = mock(SharedFolderAccessService.class);
    MongoTemplate mongo = mock(MongoTemplate.class);
    var service = new SharedFolderAuditQueryService(access, mongo);

    assertBadRequest(() -> service.search(new SharedFolderAuditFilter(
        null, null, null, "../secret", null, null, 25)));
    assertBadRequest(() -> service.search(new SharedFolderAuditFilter(
        null, null, null, null, Instant.parse("2026-07-18T00:00:00Z"),
        Instant.parse("2026-07-17T00:00:00Z"), 25)));

    org.mockito.Mockito.verifyNoInteractions(mongo);
  }

  @Test
  void overlongValidPathFilterUsesTheSameBoundedIdentifierAsPersistence() {
    SharedFolderAccessService access = mock(SharedFolderAccessService.class);
    MongoTemplate mongo = mock(MongoTemplate.class);
    var service = new SharedFolderAuditQueryService(access, mongo);
    String longPath = String.join("/", java.util.Collections.nCopies(
        150, "valid-segment"));
    when(mongo.find(org.mockito.ArgumentMatchers.any(Query.class),
        eq(SharedFolderAuditEvent.class))).thenReturn(List.of());

    service.search(new SharedFolderAuditFilter(
        null, null, null, longPath, null, null, 25));

    var captor = org.mockito.ArgumentCaptor.forClass(Query.class);
    verify(mongo).find(captor.capture(), eq(SharedFolderAuditEvent.class));
    assertThat(captor.getValue().getQueryObject().toString())
        .containsPattern("resource-sha256-[0-9a-f]{64}")
        .doesNotContain(longPath);
  }

  private void assertBadRequest(org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
    assertThatThrownBy(action).isInstanceOfSatisfying(ResponseStatusException.class,
        exception -> assertThat(exception.getStatusCode().value()).isEqualTo(400));
  }
}
