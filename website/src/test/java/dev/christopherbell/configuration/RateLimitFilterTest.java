package dev.christopherbell.configuration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import dev.christopherbell.configuration.filter.RateLimitFilter;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.Bucket4j;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Duration;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

public class RateLimitFilterTest {

  @Test
  public void testRateLimitExceeded() throws ServletException, IOException {
    Supplier<Bucket> supplier = () -> Bucket4j.builder()
        .addLimit(Bandwidth.simple(1, Duration.ofMinutes(1)))
        .build();
    RateLimitFilter filter = new RateLimitFilter(supplier);
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setRemoteAddr("1.1.1.1");
    MockHttpServletResponse response = new MockHttpServletResponse();
    FilterChain chain = mock(FilterChain.class);

    // First request allowed
    filter.doFilter(request, response, chain);
    verify(chain, times(1)).doFilter(request, response);

    // Second request denied
    MockHttpServletResponse response2 = new MockHttpServletResponse();
    filter.doFilter(request, response2, chain);
    assertEquals(429, response2.getStatus());
  }

  @Test
  public void testDifferentIpSeparateBuckets() throws ServletException, IOException {
    Supplier<Bucket> supplier = () -> Bucket4j.builder()
        .addLimit(Bandwidth.simple(1, Duration.ofMinutes(1)))
        .build();
    RateLimitFilter filter = new RateLimitFilter(supplier);
    FilterChain chain = mock(FilterChain.class);

    MockHttpServletRequest request1 = new MockHttpServletRequest();
    request1.setRemoteAddr("1.1.1.1");
    filter.doFilter(request1, new MockHttpServletResponse(), chain);

    MockHttpServletRequest request2 = new MockHttpServletRequest();
    request2.setRemoteAddr("2.2.2.2");
    filter.doFilter(request2, new MockHttpServletResponse(), chain);

    verify(chain, times(2))
        .doFilter(any(HttpServletRequest.class), any(HttpServletResponse.class));
  }
}
