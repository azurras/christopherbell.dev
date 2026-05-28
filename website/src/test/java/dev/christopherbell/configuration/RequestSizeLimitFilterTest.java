package dev.christopherbell.configuration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import dev.christopherbell.configuration.filter.RequestSizeLimitFilter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
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
    verify(chain, times(1)).doFilter(request, response);
  }
}
