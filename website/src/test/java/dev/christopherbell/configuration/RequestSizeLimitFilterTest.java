package dev.christopherbell.configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import dev.christopherbell.configuration.filter.ApiErrorResponseWriter;
import dev.christopherbell.configuration.filter.RequestSizeLimitFilter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.util.unit.DataSize;
import tools.jackson.databind.ObjectMapper;

class RequestSizeLimitFilterTest {
  private final ApiErrorResponseWriter errors =
      new ApiErrorResponseWriter(new ObjectMapper());

  @Test
  void oversizedJsonUsesStandardEnvelopeWithoutInvokingTheChain() throws Exception {
    var filter = new RequestSizeLimitFilter(
        new RequestSizeProperties(DataSize.ofBytes(10)), DataSize.ofBytes(8), errors);
    var request = new MockHttpServletRequest("POST", "/api/example");
    var sensitiveBody = "sensitive-request-body";
    request.setContentType(MediaType.APPLICATION_JSON_VALUE);
    request.setContent(sensitiveBody.getBytes(StandardCharsets.UTF_8));
    var response = new MockHttpServletResponse();
    var chain = mock(FilterChain.class);

    filter.doFilter(request, response, chain);

    assertThat(response.getStatus()).isEqualTo(413);
    assertThat(response.getContentAsString())
        .contains("\"success\":false", "REQUEST_TOO_LARGE")
        .doesNotContain(sensitiveBody);
    verifyNoInteractions(chain);
  }

  @Test
  void unknownLengthJsonAndUploadChunksRemainIndependentlyBounded() throws Exception {
    var filter = new RequestSizeLimitFilter(
        new RequestSizeProperties(DataSize.ofBytes(10)), DataSize.ofBytes(8), errors);

    assertStreamingStatus(filter, "POST", "/api/example", 11, 413);
    assertStreamingStatus(
        filter, "PUT", "/api/shared-folder/2026-07-17/uploads/id/chunks/0", 8, 200);
    assertStreamingStatus(
        filter, "PUT", "/api/shared-folder/2026-07-17/uploads/id/chunks/0", 9, 413);
  }

  @Test
  void unknownLengthOverflowIsRejectedWhenDownstreamReadsOnlyOneByte() throws Exception {
    var filter = new RequestSizeLimitFilter(
        new RequestSizeProperties(DataSize.ofBytes(10)), DataSize.ofBytes(8), errors);
    var request = new MockHttpServletRequest("POST", "/api/example");
    request.setContentType(MediaType.APPLICATION_JSON_VALUE);
    request.setContent(new byte[11]);
    var streamedRequest = new HttpServletRequestWrapper(request) {
      @Override
      public int getContentLength() {
        return -1;
      }

      @Override
      public long getContentLengthLong() {
        return -1;
      }
    };
    var response = new MockHttpServletResponse();
    FilterChain earlyReader = (servletRequest, servletResponse) ->
        ((HttpServletRequest) servletRequest).getInputStream().read();

    filter.doFilter(streamedRequest, response, earlyReader);

    assertThat(response.getStatus()).isEqualTo(413);
    assertThat(response.getContentAsString()).contains("REQUEST_TOO_LARGE");
  }

  @Test
  void unknownLengthUploadChunkRemainsStreaming() throws Exception {
    var filter = new RequestSizeLimitFilter(
        new RequestSizeProperties(DataSize.ofBytes(10)), DataSize.ofBytes(8), errors);
    var request = new MockHttpServletRequest(
        "PUT", "/api/shared-folder/2026-07-17/uploads/id/chunks/0");
    request.setContent(new byte[8]);
    var streamedRequest = new HttpServletRequestWrapper(request) {
      @Override
      public int getContentLength() {
        return -1;
      }

      @Override
      public long getContentLengthLong() {
        return -1;
      }
    };
    var observedLength = new AtomicLong(Long.MIN_VALUE);
    FilterChain earlyReader = (servletRequest, servletResponse) -> {
      var bounded = (HttpServletRequest) servletRequest;
      observedLength.set(bounded.getContentLengthLong());
      bounded.getInputStream().read();
    };
    var response = new MockHttpServletResponse();

    filter.doFilter(streamedRequest, response, earlyReader);

    assertThat(response.getStatus()).isEqualTo(200);
    assertThat(observedLength).hasValue(-1);
  }

  private void assertStreamingStatus(
      RequestSizeLimitFilter filter,
      String method,
      String path,
      int bodySize,
      int expectedStatus
  ) throws Exception {
    var request = new MockHttpServletRequest(method, path);
    request.setContentType(MediaType.APPLICATION_JSON_VALUE);
    request.setContent(new byte[bodySize]);
    var streamedRequest = new HttpServletRequestWrapper(request) {
      @Override
      public int getContentLength() {
        return -1;
      }

      @Override
      public long getContentLengthLong() {
        return -1;
      }
    };
    var response = new MockHttpServletResponse();
    FilterChain drain = (servletRequest, servletResponse) -> {
      ServletInputStream input = ((HttpServletRequest) servletRequest).getInputStream();
      while (input.read() != -1) {
        // Drain the request body to exercise streamed enforcement.
      }
    };

    filter.doFilter(streamedRequest, response, drain);

    assertThat(response.getStatus()).isEqualTo(expectedStatus);
    if (expectedStatus == 413) {
      assertThat(response.getContentAsString()).contains("REQUEST_TOO_LARGE");
    }
  }
}
