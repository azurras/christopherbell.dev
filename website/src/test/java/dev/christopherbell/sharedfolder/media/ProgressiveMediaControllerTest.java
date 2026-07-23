package dev.christopherbell.sharedfolder.media;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.christopherbell.libs.api.controller.ControllerExceptionHandler;
import dev.christopherbell.sharedfolder.web.SharedFolderNoStoreFilter;
import dev.christopherbell.sharedfolder.audit.SharedFolderAuditRecorder;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@WebMvcTest(ProgressiveMediaController.class)
@Import({ControllerExceptionHandler.class, SharedFolderNoStoreFilter.class,
    ProgressiveMediaControllerTest.Security.class})
class ProgressiveMediaControllerTest {
  private static final String BASE = "/api/shared-folder/2026-07-17/media";
  @Autowired MockMvc mockMvc;
  @MockitoBean MediaPlaybackService media;
  @MockitoBean ProgressiveMediaStreamer streamer;
  @MockitoBean SharedFolderAuditRecorder audit;

  @Test
  void mediaRoutesRequireAuthentication() throws Exception {
    for (var request : List.of(
        get(BASE + "/jobs/job-1"), delete(BASE + "/jobs/job-1"),
        get(BASE + "/jobs/job-1/stream"), post(BASE + "/fallback")
            .contentType("application/json").content("{\"path\":\"a.mkv\",\"profile\":\"VIDEO_MP4\"}"))) {
      mockMvc.perform(request).andExpect(status().isUnauthorized())
          .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "private, no-store"));
    }
  }

  @Test
  void ownedUserCanClassifyQueuePollAndCancelWithoutPathLeak() throws Exception {
    when(media.playback("video/source.mkv"))
        .thenReturn(new MediaPlaybackDescriptor(MediaPlaybackMode.DIRECT_PROBE, null, null, null));
    when(media.requestFallback("video/source.mkv", MediaOutputProfile.VIDEO_MP4))
        .thenReturn(new MediaPlaybackDescriptor(
            MediaPlaybackMode.TRANSCODING, "job-1", MediaJobStatus.QUEUED,
            MediaOutputProfile.VIDEO_MP4));
    when(media.job("job-1")).thenReturn(new MediaPlaybackDescriptor(
        MediaPlaybackMode.TRANSCODING, "job-1", MediaJobStatus.BUFFERING,
        MediaOutputProfile.VIDEO_MP4));
    when(media.cancel("job-1")).thenReturn(new MediaPlaybackDescriptor(
        MediaPlaybackMode.TRANSCODING, "job-1", MediaJobStatus.CANCELED,
        MediaOutputProfile.VIDEO_MP4));

    mockMvc.perform(post(BASE + "/playback").with(basic()).contentType("application/json")
            .content("{\"path\":\"video/source.mkv\"}"))
        .andExpect(status().isOk()).andExpect(jsonPath("$.mode").value("DIRECT_PROBE"));
    mockMvc.perform(post(BASE + "/fallback").with(basic()).contentType("application/json")
            .content("{\"path\":\"video/source.mkv\",\"profile\":\"VIDEO_MP4\"}"))
        .andExpect(status().isAccepted()).andExpect(jsonPath("$.jobId").value("job-1"))
        .andExpect(jsonPath("$.sourcePath").doesNotExist());
    mockMvc.perform(get(BASE + "/jobs/job-1").with(basic()))
        .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("BUFFERING"));
    mockMvc.perform(delete(BASE + "/jobs/job-1").with(basic()))
        .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCELED"));
  }

  @Test
  void terminalDerivativeStreamsReturnRetryableStatusInsteadOfEmptySuccess() throws Exception {
    MediaJob failed = terminalJob(MediaJobStatus.FAILED);
    MediaJob noSpace = terminalJob(MediaJobStatus.INSUFFICIENT_SPACE);
    when(media.requireVisibleJob("failed")).thenReturn(failed);
    when(media.requireVisibleJob("space")).thenReturn(noSpace);

    mockMvc.perform(get(BASE + "/jobs/failed/stream").with(basic()))
        .andExpect(status().isServiceUnavailable())
        .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "private, no-store"));
    mockMvc.perform(get(BASE + "/jobs/space/stream").with(basic()))
        .andExpect(status().isInsufficientStorage());
  }

  @Test
  void readyDerivativeUsesTheStreamingResponseHandler() throws Exception {
    MediaJob ready = terminalJob(MediaJobStatus.READY);
    ready.setId("ready");
    ready.setProfile(MediaOutputProfile.AUDIO_M4A);
    var selection = org.mockito.Mockito.mock(ProgressiveMediaStreamer.ReadySelection.class);
    when(media.requireVisibleJob("ready")).thenReturn(ready);
    when(streamer.openReady(ready, null)).thenReturn(selection);
    when(selection.length()).thenReturn(5L);
    when(selection.partial()).thenReturn(false);
    doAnswer(invocation -> {
      invocation.<java.io.OutputStream>getArgument(1).write("ready".getBytes());
      return null;
    }).when(streamer).copyReady(any(), any());

    MvcResult response = mockMvc.perform(get(BASE + "/jobs/ready/stream").with(basic()))
        .andExpect(request().asyncStarted())
        .andReturn();

    mockMvc.perform(asyncDispatch(response))
        .andExpect(status().isOk())
        .andExpect(header().string(HttpHeaders.CONTENT_TYPE, "audio/mp4"));
  }

  private MediaJob terminalJob(MediaJobStatus status) {
    MediaJob job = new MediaJob();
    job.setId(status.name().toLowerCase());
    job.setSourcePath("video/source.mkv");
    job.setProfile(MediaOutputProfile.VIDEO_MP4);
    job.setStatus(status);
    return job;
  }

  private org.springframework.test.web.servlet.request.RequestPostProcessor basic() {
    return org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors
        .httpBasic("reader", "password");
  }

  @TestConfiguration
  @EnableWebSecurity
  static class Security {
    @Bean InMemoryUserDetailsManager users() {
      return new InMemoryUserDetailsManager(User.withUsername("reader").password("{noop}password")
          .authorities("USER").build());
    }

    @Bean SecurityFilterChain chain(HttpSecurity http, SharedFolderNoStoreFilter noStore)
        throws Exception {
      return http.csrf(csrf -> csrf.disable())
          .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
          .httpBasic(basic -> {})
          .addFilterBefore(noStore,
              org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter.class)
          .build();
    }
  }
}
