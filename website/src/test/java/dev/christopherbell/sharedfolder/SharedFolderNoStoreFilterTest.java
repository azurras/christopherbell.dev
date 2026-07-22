package dev.christopherbell.sharedfolder;

import static org.assertj.core.api.Assertions.assertThat;

import dev.christopherbell.sharedfolder.web.SharedFolderNoStoreFilter;
import dev.christopherbell.sharedfolder.audit.SharedFolderAuditRecorder;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import dev.christopherbell.configuration.ClientIpResolver;
import dev.christopherbell.permission.PermissionService;
import dev.christopherbell.sharedfolder.audit.SharedFolderAuditSink;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

class SharedFolderNoStoreFilterTest {
  @Test
  void appliesNoStoreOnlyToTheExactVersionedSharedFolderApiPrefix() throws Exception {
    var filter = new SharedFolderNoStoreFilter();
    var protectedRequest = new MockHttpServletRequest(
        "GET", "/api/shared-folder/2026-07-17/entries");
    var protectedResponse = new MockHttpServletResponse();

    filter.doFilter(protectedRequest, protectedResponse, (request, response) -> {});

    assertThat(protectedResponse.getHeader("Cache-Control")).isEqualTo("private, no-store");

    var nearMissRequest = new MockHttpServletRequest(
        "GET", "/api/shared-folder/2026-07-17x/entries");
    var nearMissResponse = new MockHttpServletResponse();

    filter.doFilter(nearMissRequest, nearMissResponse, (request, response) -> {});

    assertThat(nearMissResponse.getHeader("Cache-Control")).isNull();
  }

  @Test
  void auditsValidationAndAuthorizationRejectionsAtTheSharedFolderHttpBoundary()
      throws Exception {
    SharedFolderAuditRecorder audit = org.mockito.Mockito.mock(SharedFolderAuditRecorder.class);
    var filter = new SharedFolderNoStoreFilter(audit);
    var invalid = new org.springframework.mock.web.MockHttpServletRequest(
        "POST", "/api/shared-folder/2026-07-17/folders");
    var invalidResponse = new org.springframework.mock.web.MockHttpServletResponse();
    filter.doFilter(invalid, invalidResponse, (request, response) ->
        ((jakarta.servlet.http.HttpServletResponse) response).setStatus(400));
    var denied = new org.springframework.mock.web.MockHttpServletRequest(
        "GET", "/api/shared-folder/2026-07-17/admin/audit");
    var deniedResponse = new org.springframework.mock.web.MockHttpServletResponse();
    filter.doFilter(denied, deniedResponse, (request, response) ->
        ((jakarta.servlet.http.HttpServletResponse) response).setStatus(403));

    org.mockito.Mockito.verify(audit).recordRejected(
        "CREATE_FOLDER", "request", "invalid_request");
    org.mockito.Mockito.verify(audit).recordRejectedOnce(
        "AUDIT_BROWSE", "audit", "access_denied");
  }

  @Test
  void mapsRejectedDownloadsChunksAndPermissionUpdatesToTheirRealAuditFamilies()
      throws Exception {
    SharedFolderAuditRecorder audit = org.mockito.Mockito.mock(SharedFolderAuditRecorder.class);
    var filter = new SharedFolderNoStoreFilter(audit);

    reject(filter, "GET", "/api/shared-folder/2026-07-17/content", 403);
    reject(filter, "PUT", "/api/shared-folder/2026-07-17/uploads/u-1/chunks/0", 409);
    reject(filter, "PATCH",
        "/api/accounts/2026-07-17/bad%3Aid/shared-folder-permissions", 400);

    org.mockito.Mockito.verify(audit).recordRejectedOnce(
        "DOWNLOAD_STARTED", null, "access_denied");
    org.mockito.Mockito.verify(audit).recordRejected(
        "UPLOAD_APPEND", "upload", "conflict");
    org.mockito.Mockito.verify(audit).recordRejected(
        "PERMISSION_CHANGE", "account-permissions", "invalid_request");
  }

  private void reject(
      SharedFolderNoStoreFilter filter, String method, String uri, int status) throws Exception {
    var request = new MockHttpServletRequest(method, uri);
    var response = new MockHttpServletResponse();
    filter.doFilter(request, response, (ignoredRequest, ignoredResponse) ->
        ((jakarta.servlet.http.HttpServletResponse) ignoredResponse).setStatus(status));
    assertThat(response.getHeader("Cache-Control")).isEqualTo("private, no-store");
  }

  @Test
  void doesNotDuplicateARejectedPermissionEventAlreadyRecordedByTheService() throws Exception {
    SharedFolderAuditRecorder audit = org.mockito.Mockito.mock(SharedFolderAuditRecorder.class);
    org.mockito.Mockito.when(audit.currentRequestAlreadyRecorded(
        "PERMISSION_CHANGE", "rejected")).thenReturn(true);
    var filter = new SharedFolderNoStoreFilter(audit);

    reject(filter, "PATCH",
        "/api/accounts/2026-07-17/account-1/shared-folder-permissions", 403);

    org.mockito.Mockito.verify(audit, org.mockito.Mockito.never()).recordRejected(
        org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any(),
        org.mockito.ArgumentMatchers.anyString());
  }

  @Test
  void bindsTheRealRequestForClientIpAndServiceAuditDeduplication() throws Exception {
    SharedFolderAuditSink sink = org.mockito.Mockito.mock(SharedFolderAuditSink.class);
    PermissionService permissions = org.mockito.Mockito.mock(PermissionService.class);
    ClientIpResolver clientIps = org.mockito.Mockito.mock(ClientIpResolver.class);
    org.mockito.Mockito.when(permissions.getSelfId()).thenReturn("admin-1");
    org.mockito.Mockito.when(clientIps.resolveClientIp(org.mockito.ArgumentMatchers.any()))
        .thenReturn("203.0.113.8");
    var recorder = new SharedFolderAuditRecorder(
        sink, permissions, clientIps,
        Clock.fixed(Instant.parse("2026-07-18T12:00:00Z"), ZoneOffset.UTC));
    var filter = new SharedFolderNoStoreFilter(recorder);
    var request = new MockHttpServletRequest(
        "PATCH", "/api/accounts/2026-07-17/account-1/shared-folder-permissions");
    var response = new MockHttpServletResponse();

    filter.doFilter(request, response, (ignoredRequest, ignoredResponse) -> {
      recorder.recordCurrent(
          "PERMISSION_CHANGE", "account-1", null, "rejected", "access_denied");
      ((jakarta.servlet.http.HttpServletResponse) ignoredResponse).setStatus(403);
    });

    var captor = org.mockito.ArgumentCaptor.forClass(
        dev.christopherbell.sharedfolder.audit.SharedFolderAuditCommand.class);
    org.mockito.Mockito.verify(sink).record(captor.capture());
    assertThat(captor.getValue().clientIp()).isEqualTo("203.0.113.8");
  }

  @Test
  void repeatedRateLimitResponsesEmitOneBoundedAuditFactPerClientWindow() throws Exception {
    SharedFolderAuditSink sink = org.mockito.Mockito.mock(SharedFolderAuditSink.class);
    PermissionService permissions = org.mockito.Mockito.mock(PermissionService.class);
    ClientIpResolver clientIps = org.mockito.Mockito.mock(ClientIpResolver.class);
    org.mockito.Mockito.when(clientIps.resolveClientIp(org.mockito.ArgumentMatchers.any()))
        .thenReturn("203.0.113.9");
    var recorder = new SharedFolderAuditRecorder(
        sink, permissions, clientIps,
        Clock.fixed(Instant.parse("2026-07-18T12:00:00Z"), ZoneOffset.UTC));
    var filter = new SharedFolderNoStoreFilter(recorder);

    reject(filter, "GET", "/api/shared-folder/2026-07-17/entries", 429);
    reject(filter, "GET", "/api/shared-folder/2026-07-17/entries", 429);

    org.mockito.Mockito.verify(sink, org.mockito.Mockito.times(1))
        .record(org.mockito.ArgumentMatchers.any());
  }

  @Test
  void repeatedAnonymousDenialsEmitOneBoundedAuditFactPerClientWindow() throws Exception {
    SharedFolderAuditSink sink = org.mockito.Mockito.mock(SharedFolderAuditSink.class);
    ClientIpResolver clientIps = org.mockito.Mockito.mock(ClientIpResolver.class);
    org.mockito.Mockito.when(clientIps.resolveClientIp(org.mockito.ArgumentMatchers.any()))
        .thenReturn("203.0.113.10");
    var recorder = new SharedFolderAuditRecorder(
        sink, org.mockito.Mockito.mock(PermissionService.class), clientIps,
        Clock.fixed(Instant.parse("2026-07-18T12:00:00Z"), ZoneOffset.UTC));
    var filter = new SharedFolderNoStoreFilter(recorder);

    reject(filter, "GET", "/api/shared-folder/2026-07-17/entries", 401);
    reject(filter, "GET", "/api/shared-folder/2026-07-17/entries", 401);

    org.mockito.Mockito.verify(sink, org.mockito.Mockito.times(1))
        .record(org.mockito.ArgumentMatchers.any());
  }
}
