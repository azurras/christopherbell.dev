package dev.christopherbell.sharedfolder;

import static org.assertj.core.api.Assertions.assertThat;

import dev.christopherbell.sharedfolder.web.SharedFolderNoStoreFilter;
import dev.christopherbell.sharedfolder.audit.SharedFolderAuditRecorder;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

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
    org.mockito.Mockito.verify(audit).recordRejected(
        "AUDIT_BROWSE", "audit", "access_denied");
  }
}
