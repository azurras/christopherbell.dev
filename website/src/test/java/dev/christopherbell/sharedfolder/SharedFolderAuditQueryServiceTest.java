package dev.christopherbell.sharedfolder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.christopherbell.sharedfolder.audit.SharedFolderAuditEvent;
import dev.christopherbell.sharedfolder.audit.SharedFolderAuditFilter;
import dev.christopherbell.sharedfolder.audit.SharedFolderAuditQueryService;
import dev.christopherbell.sharedfolder.audit.SharedFolderAuditRepository;
import dev.christopherbell.sharedfolder.security.SharedFolderAccessService;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class SharedFolderAuditQueryServiceTest {
  @Test
  void adminFiltersBoundedAuditHistoryByAccountActionOutcomePathAndDate() {
    SharedFolderAccessService access = mock(SharedFolderAccessService.class);
    SharedFolderAuditRepository repository = mock(SharedFolderAuditRepository.class);
    var service = new SharedFolderAuditQueryService(access, repository);
    Instant from = Instant.parse("2026-07-01T00:00:00Z");
    Instant to = Instant.parse("2026-07-18T23:59:59Z");
    var filter = new SharedFolderAuditFilter(
        "account-1", "RECYCLE", "accepted", "docs/report.pdf", from, to, 500);
    when(repository.search(
        "account-1", "RECYCLE", "accepted", "docs/report.pdf", from, to, 100))
        .thenReturn(List.of());

    assertThat(service.search(filter)).isEmpty();

    verify(access).requireAdmin();
    verify(repository).search(
        "account-1", "RECYCLE", "accepted", "docs/report.pdf", from, to, 100);
  }

  @Test
  void unsafeOrInvertedFiltersAreRejectedBeforeMongoIsCalled() {
    SharedFolderAccessService access = mock(SharedFolderAccessService.class);
    SharedFolderAuditRepository repository = mock(SharedFolderAuditRepository.class);
    var service = new SharedFolderAuditQueryService(access, repository);

    assertBadRequest(() -> service.search(new SharedFolderAuditFilter(
        null, null, null, "../secret", null, null, 25)));
    assertBadRequest(() -> service.search(new SharedFolderAuditFilter(
        null, null, null, null, Instant.parse("2026-07-18T00:00:00Z"),
        Instant.parse("2026-07-17T00:00:00Z"), 25)));

    org.mockito.Mockito.verifyNoInteractions(repository);
  }

  @Test
  void overlongValidPathFilterUsesTheSameBoundedIdentifierAsPersistence() {
    SharedFolderAccessService access = mock(SharedFolderAccessService.class);
    SharedFolderAuditRepository repository = mock(SharedFolderAuditRepository.class);
    var service = new SharedFolderAuditQueryService(access, repository);
    String longPath = String.join("/", java.util.Collections.nCopies(
        150, "valid-segment"));
    when(repository.search(
        null, null, null, SharedFolderAuditEvent.class.getName(), null, null, 25))
        .thenReturn(List.of());

    service.search(new SharedFolderAuditFilter(
        null, null, null, longPath, null, null, 25));

    var path = org.mockito.ArgumentCaptor.forClass(String.class);
    verify(repository).search(
        org.mockito.ArgumentMatchers.isNull(), org.mockito.ArgumentMatchers.isNull(),
        org.mockito.ArgumentMatchers.isNull(), path.capture(),
        org.mockito.ArgumentMatchers.isNull(), org.mockito.ArgumentMatchers.isNull(),
        org.mockito.ArgumentMatchers.eq(25));
    assertThat(path.getValue()).containsPattern("resource-sha256-[0-9a-f]{64}")
        .doesNotContain(longPath);
  }

  private void assertBadRequest(org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
    assertThatThrownBy(action).isInstanceOfSatisfying(ResponseStatusException.class,
        exception -> assertThat(exception.getStatusCode().value()).isEqualTo(400));
  }
}
