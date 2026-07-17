package dev.christopherbell.sharedfolder;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.times;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.christopherbell.libs.api.controller.ControllerExceptionHandler;
import dev.christopherbell.sharedfolder.model.SharedDirectoryEntry;
import dev.christopherbell.sharedfolder.model.SharedDirectoryEntryType;
import dev.christopherbell.sharedfolder.model.SharedDirectoryResponse;
import dev.christopherbell.sharedfolder.model.SharedFolderPreviewKind;
import dev.christopherbell.sharedfolder.service.SharedFolderBrowserService;
import dev.christopherbell.sharedfolder.service.SharedFolderDownloadService;
import dev.christopherbell.sharedfolder.service.SharedFolderDownloadService.SharedFolderDownload;
import dev.christopherbell.sharedfolder.service.SharedFolderPreviewService;
import dev.christopherbell.sharedfolder.service.SharedFolderPreviewService.SharedFolderPreview;
import dev.christopherbell.sharedfolder.service.SharedFolderRangeNotSatisfiableException;
import dev.christopherbell.sharedfolder.security.SharedFolderAccessService;
import dev.christopherbell.sharedfolder.web.SharedFolderNoStoreFilter;
import dev.christopherbell.sharedfolder.web.SharedFolderReadController;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.test.context.TestSecurityContextHolder;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.server.ResponseStatusException;

@WebMvcTest(SharedFolderReadController.class)
@Import({ControllerExceptionHandler.class,
    SharedFolderNoStoreFilter.class,
    SharedFolderReadControllerTest.MethodSecurityTestConfiguration.class})
class SharedFolderReadControllerTest {
  private static final String BASE = "/api/shared-folder/2026-07-17";

  @Autowired private MockMvc mockMvc;
  @MockitoBean private SharedFolderAccessService access;
  @MockitoBean private SharedFolderBrowserService browser;
  @MockitoBean private SharedFolderDownloadService downloads;
  @MockitoBean private SharedFolderPreviewService previews;

  @Test
  void everyReadRoute_whenAnonymous_returnsUnauthorized() throws Exception {
    for (MockHttpServletRequestBuilder request : List.of(
        get(BASE + "/entries").queryParam("path", "music"),
        get(BASE + "/content").queryParam("path", "music/track.flac"),
        get(BASE + "/preview").queryParam("path", "music/notes.txt"))) {
      mockMvc.perform(request)
          .andExpect(status().isUnauthorized())
          .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "private, no-store"));
    }

    verifyNoInteractions(access, browser, downloads, previews);
  }

  @Test
  @WithMockUser(authorities = "USER")
  void readUser_canListAndRangeDownloadWithoutAnAbsolutePathLeak() throws Exception {
    when(browser.list("music")).thenReturn(new SharedDirectoryResponse("music", List.of(
        new SharedDirectoryEntry("track.flac", "music/track.flac", SharedDirectoryEntryType.FILE,
            10, Instant.parse("2026-07-17T00:00:00Z"), SharedFolderPreviewKind.AUDIO))));
    when(downloads.open("music/track.flac", "bytes=0-3"))
        .thenReturn(new SharedFolderDownload(
            new ByteArrayResource("0123456789".getBytes(StandardCharsets.UTF_8)),
            0, 4, 10, true, MediaType.APPLICATION_OCTET_STREAM,
            "attachment; filename=track.flac"));

    mockMvc.perform(get(BASE + "/entries").queryParam("path", "music"))
        .andExpect(status().isOk())
        .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "private, no-store"))
        .andExpect(jsonPath("$.path").value("music"))
        .andExpect(jsonPath("$.entries[0].name").value("track.flac"))
        .andExpect(content().string(not(containsString("A:\\Shared"))));

    mockMvc.perform(get(BASE + "/content").queryParam("path", "music/track.flac")
            .header(HttpHeaders.RANGE, "bytes=0-3"))
        .andExpect(status().isPartialContent())
        .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "private, no-store"))
        .andExpect(header().string(HttpHeaders.ACCEPT_RANGES, "bytes"))
        .andExpect(header().string(HttpHeaders.CONTENT_RANGE, "bytes 0-3/10"))
        .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION,
            "attachment; filename=track.flac"));

    verify(access, times(2)).requireRead();
    verify(browser).list("music");
    verify(downloads).open("music/track.flac", "bytes=0-3");
  }

  @Test
  @WithMockUser(authorities = "USER")
  void staleReadUser_isDeniedBeforeTheFilesystemIsReached() throws Exception {
    org.mockito.Mockito.doThrow(new AccessDeniedException("revoked")).when(access).requireRead();

    for (MockHttpServletRequestBuilder request : List.of(
        get(BASE + "/entries").queryParam("path", "music"),
        get(BASE + "/content").queryParam("path", "music/track.flac"),
        get(BASE + "/preview").queryParam("path", "music/notes.txt"))) {
      mockMvc.perform(request)
          .andExpect(status().isForbidden())
          .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "private, no-store"));
    }

    verifyNoInteractions(browser, downloads, previews);
  }

  @Test
  @WithMockUser(authorities = "USER")
  void content_rejectsMultipleRangesAndSupportsHeadWithoutInvokingTheService() throws Exception {
    when(downloads.open("music/track.flac", "bytes=0-1,4-5"))
        .thenThrow(new SharedFolderRangeNotSatisfiableException(10));

    mockMvc.perform(get(BASE + "/content").queryParam("path", "music/track.flac")
            .header(HttpHeaders.RANGE, "bytes=0-1,4-5"))
        .andExpect(status().isRequestedRangeNotSatisfiable())
        .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "private, no-store"))
        .andExpect(header().string(HttpHeaders.CONTENT_RANGE, "bytes */10"));

    when(downloads.open("music/track.flac", null))
        .thenReturn(new SharedFolderDownload(
            new ByteArrayResource("0123456789".getBytes(StandardCharsets.UTF_8)),
            0, 10, 10, false, MediaType.APPLICATION_OCTET_STREAM,
            "attachment; filename=track.flac"));

    mockMvc.perform(head(BASE + "/content").queryParam("path", "music/track.flac"))
        .andExpect(status().isOk())
        .andExpect(header().string(HttpHeaders.ACCEPT_RANGES, "bytes"))
        .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "private, no-store"))
        .andExpect(header().string(HttpHeaders.CONTENT_LENGTH, "10"));

    verify(downloads).open("music/track.flac", "bytes=0-1,4-5");
    verify(downloads).open("music/track.flac", null);
  }

  @Test
  @WithMockUser(authorities = "USER")
  void fullContentAndProtectedNotFoundResponsesAreNeverStored() throws Exception {
    when(downloads.open("music/full.flac", null))
        .thenReturn(new SharedFolderDownload(
            new ByteArrayResource("0123456789".getBytes(StandardCharsets.UTF_8)),
            0, 10, 10, false, MediaType.APPLICATION_OCTET_STREAM,
            "attachment; filename=full.flac"));
    when(browser.list("missing")).thenThrow(new ResponseStatusException(
        org.springframework.http.HttpStatus.NOT_FOUND, "Shared folder item was not found"));

    mockMvc.perform(get(BASE + "/content").queryParam("path", "music/full.flac"))
        .andExpect(status().isOk())
        .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "private, no-store"));

    mockMvc.perform(get(BASE + "/entries").queryParam("path", "missing"))
        .andExpect(status().isNotFound())
        .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "private, no-store"));
  }

  @Test
  @WithMockUser(authorities = "USER")
  void textPreview_isEscapedByJsonAndSetsNoSniff() throws Exception {
    when(previews.open("music/notes.txt")).thenReturn(SharedFolderPreview.text(
        "<script>alert('no')</script>", false));

    mockMvc.perform(get(BASE + "/preview").queryParam("path", "music/notes.txt"))
        .andExpect(status().isOk())
        .andExpect(header().string("X-Content-Type-Options", "nosniff"))
        .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "private, no-store"))
        .andExpect(jsonPath("$.text").value("<script>alert('no')</script>"));

    verify(access).requireRead();
    verify(previews).open("music/notes.txt");
  }

  @Test
  @WithMockUser(authorities = "USER")
  void pdfPreview_streamsWithNoSniffAndASandboxedInlineDisposition() throws Exception {
    when(previews.open("music/guide.pdf")).thenReturn(new SharedFolderPreview(
        SharedFolderPreviewKind.PDF, null,
        new ByteArrayResource("%PDF-safe".getBytes(StandardCharsets.UTF_8)), false,
        MediaType.APPLICATION_PDF, "inline; filename=guide.pdf"));

    mockMvc.perform(get(BASE + "/preview").queryParam("path", "music/guide.pdf"))
        .andExpect(status().isOk())
        .andExpect(header().string("X-Content-Type-Options", "nosniff"))
        .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "private, no-store"))
        .andExpect(header().string("Content-Security-Policy", "sandbox; default-src 'none'"))
        .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=guide.pdf"));

    verify(access).requireRead();
    verify(previews).open("music/guide.pdf");
  }

  @Test
  @WithMockUser(authorities = "USER")
  void mediaPreview_honorsNativeSingleRangeRequests() throws Exception {
    when(previews.open("music/track.mp3")).thenReturn(new SharedFolderPreview(
        SharedFolderPreviewKind.AUDIO, null,
        new ByteArrayResource("0123456789".getBytes(StandardCharsets.UTF_8)), false,
        MediaType.valueOf("audio/mpeg"), "inline; filename=track.mp3"));

    mockMvc.perform(get(BASE + "/preview").queryParam("path", "music/track.mp3")
            .header(HttpHeaders.RANGE, "bytes=2-5"))
        .andExpect(status().isPartialContent())
        .andExpect(header().string(HttpHeaders.ACCEPT_RANGES, "bytes"))
        .andExpect(header().string(HttpHeaders.CONTENT_RANGE, "bytes 2-5/10"))
        .andExpect(header().string(HttpHeaders.CONTENT_LENGTH, "4"));

    verify(access).requireRead();
    verify(previews).open("music/track.mp3");
  }

  @TestConfiguration
  @EnableMethodSecurity
  @EnableWebSecurity
  static class MethodSecurityTestConfiguration {
    @Bean
    SecurityFilterChain sharedFolderTestSecurityFilterChain(
        HttpSecurity http,
        SharedFolderNoStoreFilter sharedFolderNoStoreFilter) throws Exception {
      return http
          .exceptionHandling(exceptions -> exceptions
              .authenticationEntryPoint((request, response, exception) -> response.sendError(401))
              .accessDeniedHandler((request, response, exception) -> response.sendError(403)))
          .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
          .addFilterBefore(sharedFolderNoStoreFilter, UsernamePasswordAuthenticationFilter.class)
          .addFilterBefore(new TestSecurityContextBridgeFilter(),
              UsernamePasswordAuthenticationFilter.class)
          .build();
    }

    private static class TestSecurityContextBridgeFilter
        extends org.springframework.web.filter.OncePerRequestFilter {
      @Override
      protected void doFilterInternal(
          HttpServletRequest request,
          HttpServletResponse response,
          FilterChain filterChain) throws ServletException, IOException {
        var authentication = TestSecurityContextHolder.getContext().getAuthentication();
        if (authentication != null) {
          SecurityContextHolder.getContext().setAuthentication(authentication);
        }
        filterChain.doFilter(request, response);
      }
    }
  }
}
