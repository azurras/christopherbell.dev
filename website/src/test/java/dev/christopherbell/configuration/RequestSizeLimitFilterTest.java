package dev.christopherbell.configuration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import dev.christopherbell.configuration.filter.RequestSizeLimitFilter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

public class RequestSizeLimitFilterTest {

  @Test
  public void rejectsLargeRequest() throws ServletException, IOException {
    RequestSizeLimitFilter filter = new RequestSizeLimitFilter(10);
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setContent(new byte[20]);
    MockHttpServletResponse response = new MockHttpServletResponse();
    FilterChain chain = mock(FilterChain.class);

    filter.doFilter(request, response, chain);

    assertEquals(413, response.getStatus());
    verify(chain, times(0)).doFilter(request, response);
  }

  @Test
  public void allowsSmallRequest() throws ServletException, IOException {
    RequestSizeLimitFilter filter = new RequestSizeLimitFilter(10);
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setContent(new byte[5]);
    MockHttpServletResponse response = new MockHttpServletResponse();
    FilterChain chain = mock(FilterChain.class);

    filter.doFilter(request, response, chain);

    assertEquals(200, response.getStatus());
    verify(chain, times(1)).doFilter(any(HttpServletRequest.class), same(response));
  }

  @Test
  public void rejectsLargeRequestWhenContentLengthIsMissing()
      throws ServletException, IOException {
    RequestSizeLimitFilter filter = new RequestSizeLimitFilter(10);
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setContent(new byte[20]);
    var streamingRequest = new HttpServletRequestWrapper(request) {
      @Override
      public int getContentLength() {
        return -1;
      }

      @Override
      public long getContentLengthLong() {
        return -1;
      }
    };
    MockHttpServletResponse response = new MockHttpServletResponse();
    FilterChain chain = (servletRequest, servletResponse) -> {
      ServletInputStream inputStream = ((HttpServletRequest) servletRequest).getInputStream();
      while (inputStream.read() != -1) {
        // Drain the request body to exercise streaming enforcement.
      }
    };

    filter.doFilter(streamingRequest, response, chain);

    assertEquals(413, response.getStatus());
  }

  @Test
  public void sharedFolderUploadChunksUseTheirConfiguredLimitWithoutChangingOtherRoutes()
      throws ServletException, IOException {
    RequestSizeLimitFilter filter = new RequestSizeLimitFilter(10, 8);
    FilterChain drain = (servletRequest, servletResponse) -> {
      ServletInputStream input = ((HttpServletRequest) servletRequest).getInputStream();
      while (input.read() != -1) {
        // Exercise the same streamed enforcement used for chunked HTTP bodies.
      }
    };

    MockHttpServletRequest exactChunk = new MockHttpServletRequest(
        "PUT", "/api/shared-folder/2026-07-17/uploads/id/chunks/0");
    exactChunk.setContent(new byte[8]);
    MockHttpServletResponse exactResponse = new MockHttpServletResponse();
    filter.doFilter(exactChunk, exactResponse, drain);

    MockHttpServletRequest oversizeChunk = new MockHttpServletRequest(
        "PUT", "/api/shared-folder/2026-07-17/uploads/id/chunks/0");
    oversizeChunk.setContent(new byte[9]);
    MockHttpServletResponse oversizeResponse = new MockHttpServletResponse();
    filter.doFilter(oversizeChunk, oversizeResponse, drain);

    MockHttpServletRequest ordinary = new MockHttpServletRequest("POST", "/api/ordinary");
    ordinary.setContent(new byte[11]);
    MockHttpServletResponse ordinaryResponse = new MockHttpServletResponse();
    filter.doFilter(ordinary, ordinaryResponse, drain);

    MockHttpServletRequest complete = new MockHttpServletRequest(
        "POST", "/api/shared-folder/2026-07-17/uploads/id/complete");
    complete.setContent(new byte[9]);
    MockHttpServletResponse completeResponse = new MockHttpServletResponse();
    filter.doFilter(complete, completeResponse, drain);

    MockHttpServletRequest unknownLengthChunk = new MockHttpServletRequest(
        "PUT", "/api/shared-folder/2026-07-17/uploads/id/chunks/0");
    unknownLengthChunk.setContent(new byte[9]);
    HttpServletRequestWrapper streamedChunk = new HttpServletRequestWrapper(unknownLengthChunk) {
      @Override public int getContentLength() { return -1; }
      @Override public long getContentLengthLong() { return -1; }
    };
    MockHttpServletResponse streamedChunkResponse = new MockHttpServletResponse();
    filter.doFilter(streamedChunk, streamedChunkResponse, drain);

    assertEquals(200, exactResponse.getStatus());
    assertEquals(413, oversizeResponse.getStatus());
    assertEquals(413, ordinaryResponse.getStatus());
    assertEquals(200, completeResponse.getStatus());
    assertEquals(413, streamedChunkResponse.getStatus());
  }
}
