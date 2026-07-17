package dev.christopherbell.sharedfolder;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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

@WebMvcTest(SharedFolderWriteController.class)
@Import({ControllerExceptionHandler.class, SharedFolderNoStoreFilter.class,
    SharedFolderWriteControllerTest.SecurityConfiguration.class})
class SharedFolderWriteControllerTest {
  private static final String BASE = "/api/shared-folder/2026-07-17";

  @Autowired private MockMvc mockMvc;
  @MockitoBean private SharedFolderMutationService mutations;
  @MockitoBean private SharedFolderUploadService uploads;

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
