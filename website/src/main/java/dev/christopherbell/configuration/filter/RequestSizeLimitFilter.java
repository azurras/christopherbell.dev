package dev.christopherbell.configuration.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpStatus;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Servlet filter that rejects requests exceeding a configured maximum size.
 *
 * <p>Defaults to 1 MB when no explicit limit is provided.</p>
 */
public class RequestSizeLimitFilter extends OncePerRequestFilter {

  private final long maxSizeBytes;

  /**
   * Creates a filter with a default limit of 1 MB.
   */
  public RequestSizeLimitFilter() {
    this(1_000_000L);
  }

  /**
   * Creates a filter with a custom size limit. Intended for testing or configuration.
   *
   * @param maxSizeBytes maximum allowed request size in bytes
   */
  public RequestSizeLimitFilter(long maxSizeBytes) {
    this.maxSizeBytes = maxSizeBytes;
  }

  @Override
  protected void doFilterInternal(HttpServletRequest request,
      HttpServletResponse response,
      FilterChain filterChain) throws ServletException, IOException {
    long contentLength = request.getContentLengthLong();
    if (contentLength > maxSizeBytes) {
      response.setStatus(HttpStatus.PAYLOAD_TOO_LARGE.value());
      return;
    }
    filterChain.doFilter(request, response);
  }
}
