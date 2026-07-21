package dev.christopherbell.sharedfolder;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.christopherbell.libs.api.controller.ControllerExceptionHandler;
import dev.christopherbell.sharedfolder.model.SharedDirectoryEntry;
import dev.christopherbell.sharedfolder.model.SharedDirectoryEntryType;
import dev.christopherbell.sharedfolder.model.SharedFolderPreviewKind;
import dev.christopherbell.sharedfolder.service.SharedFolderMutationService;
import dev.christopherbell.sharedfolder.recycle.SharedFolderRecycleService;
import dev.christopherbell.sharedfolder.audit.SharedFolderAuditEvent;
import dev.christopherbell.sharedfolder.audit.SharedFolderAuditQueryService;
import dev.christopherbell.sharedfolder.audit.SharedFolderAuditRecorder;
import dev.christopherbell.sharedfolder.web.SharedFolderAdminController;
import dev.christopherbell.sharedfolder.upload.SharedFolderUploadService;
import dev.christopherbell.sharedfolder.upload.SharedFolderUploadState;
import dev.christopherbell.sharedfolder.upload.SharedFolderUploadStatus;
import dev.christopherbell.sharedfolder.web.SharedFolderNoStoreFilter;
import dev.christopherbell.sharedfolder.web.SharedFolderWriteController;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.test.context.TestSecurityContextHolder;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest({SharedFolderWriteController.class, SharedFolderAdminController.class})
@Import({ControllerExceptionHandler.class, SharedFolderNoStoreFilter.class,
    SharedFolderWriteControllerTest.SecurityConfiguration.class})
class SharedFolderWriteControllerTest {
  private static final String BASE = "/api/shared-folder/2026-07-17";

  @Autowired private MockMvc mockMvc;
  @MockitoBean private SharedFolderMutationService mutations;
  @MockitoBean private SharedFolderUploadService uploads;
  @MockitoBean private SharedFolderRecycleService recycle;
  @MockitoBean private SharedFolderAuditQueryService auditQueries;
  @MockitoBean private SharedFolderAuditRecorder auditRecorder;

  @Test
  @WithMockUser(authorities = "ADMIN")
  void adminCanFilterAuditAndListRestoreOrPurgeRecycleItems() throws Exception {
    when(auditQueries.search(any())).thenReturn(java.util.List.of(new SharedFolderAuditEvent(
        "audit-1", "account-1", "RECYCLE", "docs/report.pdf", 42L, "accepted", null,
        "203.0.113.8", Instant.parse("2026-07-18T12:00:00Z"),
        Instant.parse("2027-01-14T12:00:00Z"))));
    when(recycle.list()).thenReturn(java.util.List.of());

    mockMvc.perform(get(BASE + "/admin/audit")
            .queryParam("accountId", "account-1")
            .queryParam("action", "RECYCLE")
            .queryParam("outcome", "accepted")
            .queryParam("path", "docs/report.pdf"))
        .andExpect(status().isOk())
        .andExpect(header().string("Cache-Control", "private, no-store"))
        .andExpect(jsonPath("$[0].relativePath").value("docs/report.pdf"));
    mockMvc.perform(get(BASE + "/admin/recycle"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$").isArray());
    mockMvc.perform(post(BASE + "/admin/recycle/item-1/restore")
            .contentType("application/json").content("{\"replace\":true}"))
        .andExpect(status().isOk());
    mockMvc.perform(delete(BASE + "/admin/recycle/item-1")
            .contentType("application/json").content("{\"confirmation\":\"PURGE item-1\"}"))
        .andExpect(status().isNoContent());

    org.mockito.Mockito.verify(recycle).restore("item-1", true);
    org.mockito.Mockito.verify(recycle).purge("item-1", "PURGE item-1");
    org.mockito.Mockito.verify(auditRecorder).recordCurrent(
        "AUDIT_BROWSE", "docs/report.pdf", null, "accepted", null);
    org.mockito.Mockito.verify(auditRecorder).recordCurrent(
        "RECYCLE_BROWSE", "recycle", null, "accepted", null);
  }

  @Test
  @WithMockUser(authorities = "USER")
  void nonAdminCannotUseAuditOrRecycleAdministration() throws Exception {
    mockMvc.perform(get(BASE + "/admin/audit")).andExpect(status().isForbidden());
    mockMvc.perform(get(BASE + "/admin/recycle")).andExpect(status().isForbidden());
    org.mockito.Mockito.verifyNoInteractions(auditQueries);
    org.mockito.Mockito.verify(auditRecorder).recordRejected(
        "AUDIT_BROWSE", "audit", "access_denied");
    org.mockito.Mockito.verify(auditRecorder).recordRejected(
        "RECYCLE_BROWSE", "recycle", "access_denied");
  }

  @Test
  @WithMockUser(authorities = "USER")
  void malformedMutationBodyIsAuditedAtTheHttpBoundary() throws Exception {
    mockMvc.perform(post(BASE + "/folders")
            .contentType("application/json")
            .content("{\"parentPath\":\"\",\"name\":\"\"}"))
        .andExpect(status().isBadRequest());

    org.mockito.Mockito.verify(auditRecorder).recordRejected(
        "CREATE_FOLDER", "request", "invalid_request");
  }

  @Test
  @WithMockUser(authorities = "USER")
  void deleteRouteMovesTheObservedItemToRecycleInsteadOfPhysicallyDeletingIt() throws Exception {
    when(recycle.recycle(any())).thenReturn(
        new dev.christopherbell.sharedfolder.recycle.SharedFolderRecycleItem(
            "item-1", "docs/report.pdf", "account-1",
            Instant.parse("2026-07-18T12:00:00Z"),
            Instant.parse("2026-08-17T12:00:00Z"), "payload-1", 42L, false,
            "fingerprint", dev.christopherbell.sharedfolder.recycle.SharedFolderRecycleState.RECYCLED));
    mockMvc.perform(delete(BASE + "/entries").contentType("application/json")
            .content("{\"path\":\"docs/report.pdf\",\"observedToken\":\"opaque\"}"))
        .andExpect(status().isNoContent())
        .andExpect(header().string("Cache-Control", "private, no-store"));

    org.mockito.Mockito.verify(recycle).recycle(any());
    org.mockito.Mockito.verifyNoInteractions(mutations);
  }

  @Test
  void anonymousWriteAndUploadRoutesAreUnauthorizedAndNeverStored() throws Exception {
    mockMvc.perform(post(BASE + "/folders").contentType("application/json")
            .content("{\"parentPath\":\"\",\"name\":\"docs\"}"))
        .andExpect(status().isUnauthorized())
        .andExpect(header().string("Cache-Control", "private, no-store"));
    mockMvc.perform(get(BASE + "/uploads/session-1"))
        .andExpect(status().isUnauthorized())
        .andExpect(header().string("Cache-Control", "private, no-store"));
  }

  @Test
  @WithMockUser(authorities = "USER")
  void authenticatedWriteAndUploadDtosReturnOnlySafeRelativeFieldsAndNoStore() throws Exception {
    when(mutations.createFolder(any())).thenReturn(new SharedDirectoryEntry(
        "docs", "docs", SharedDirectoryEntryType.DIRECTORY, 0,
        Instant.parse("2026-07-17T00:00:00Z"), SharedFolderPreviewKind.NONE, "opaque-token"));
    when(uploads.create(any())).thenReturn(new SharedFolderUploadStatus(
        "session-1", "docs", "video.mkv", 5, 0, SharedFolderUploadState.ACTIVE,
        Instant.parse("2026-07-18T00:00:00Z")));

    mockMvc.perform(post(BASE + "/folders").contentType("application/json")
            .content("{\"parentPath\":\"\",\"name\":\"docs\"}"))
        .andExpect(status().isCreated())
        .andExpect(header().string("Cache-Control", "private, no-store"))
        .andExpect(jsonPath("$.path").value("docs"))
        .andExpect(jsonPath("$.observedToken").value("opaque-token"));

    mockMvc.perform(post(BASE + "/uploads").contentType("application/json")
            .content("{\"parentPath\":\"docs\",\"name\":\"video.mkv\",\"expectedBytes\":5}"))
        .andExpect(status().isCreated())
        .andExpect(header().string("Cache-Control", "private, no-store"))
        .andExpect(jsonPath("$.id").value("session-1"))
        .andExpect(jsonPath("$.parentPath").value("docs"));
  }

  @Test
  @WithMockUser(authorities = "USER")
  void putChunkRouteBindsTheRawServletStreamAndReturnsProgress() throws Exception {
    when(uploads.append(eq("session-1"), eq(0L), any(java.io.InputStream.class), eq("digest")))
        .thenReturn(new SharedFolderUploadStatus(
            "session-1", "docs", "video.mkv", 3, 3, SharedFolderUploadState.ACTIVE,
            Instant.parse("2026-07-18T00:00:00Z")));

    mockMvc.perform(put(BASE + "/uploads/session-1/chunks/0")
            .contentType("application/octet-stream")
            .header("X-Chunk-SHA-256", "digest")
            .content(new byte[] {1, 2, 3}))
        .andExpect(status().isOk())
        .andExpect(header().string("Cache-Control", "private, no-store"))
        .andExpect(jsonPath("$.nextOffset").value(3));

    org.mockito.Mockito.verify(auditRecorder).recordCurrent(
        "UPLOAD_APPEND", "session-1", 3L, "accepted", null);
  }

  @ParameterizedTest
  @ValueSource(ints = {400, 404, 409, 503})
  @WithMockUser(authorities = "USER")
  void mutationStatusesKeepExactCodesAndSafeBodies(int code) throws Exception {
    when(uploads.status("session-1")).thenThrow(new org.springframework.web.server.ResponseStatusException(
        org.springframework.http.HttpStatus.valueOf(code), "Shared-folder request failed"));

    String body = mockMvc.perform(get(BASE + "/uploads/session-1"))
        .andExpect(status().is(code))
        .andReturn().getResponse().getContentAsString();

    org.assertj.core.api.Assertions.assertThat(body)
        .doesNotContain("NativeBoundaryException", "java.", "C:\\", "shared-folder-upload-staging");
  }

  @TestConfiguration
  @EnableMethodSecurity
  @EnableWebSecurity
  static class SecurityConfiguration {
    @Bean
    SecurityFilterChain securityFilterChain(
        HttpSecurity http, SharedFolderNoStoreFilter noStoreFilter) throws Exception {
      return http
          .csrf(AbstractHttpConfigurer::disable)
          .exceptionHandling(exceptions -> exceptions
              .authenticationEntryPoint((request, response, exception) -> response.sendError(401))
              .accessDeniedHandler((request, response, exception) -> response.sendError(403)))
          .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
          .addFilterBefore(noStoreFilter, UsernamePasswordAuthenticationFilter.class)
          .addFilterBefore(new TestSecurityContextBridgeFilter(), UsernamePasswordAuthenticationFilter.class)
          .build();
    }

    private static class TestSecurityContextBridgeFilter
        extends org.springframework.web.filter.OncePerRequestFilter {
      @Override
      protected void doFilterInternal(
          HttpServletRequest request, HttpServletResponse response, FilterChain chain)
          throws ServletException, IOException {
        var authentication = TestSecurityContextHolder.getContext().getAuthentication();
        if (authentication != null) {
          SecurityContextHolder.getContext().setAuthentication(authentication);
        }
        chain.doFilter(request, response);
      }
    }
  }
}
