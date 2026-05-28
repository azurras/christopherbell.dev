package dev.christopherbell.configuration.filter;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.Bucket4j;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Duration;
import org.springframework.http.HttpStatus;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.core.annotation.Order;

/**
 * Simple per-client rate limiting filter using Bucket4j.
 *
 * <p>Applies a token bucket per client IP to restrict request throughput.
 * Default limit is 50 requests per minute.</p>
 */
@Order(1)
public class RateLimitFilter extends OncePerRequestFilter {

  private final Supplier<Bucket> bucketSupplier;
  private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();

  /**
   * Creates a filter with default limit of 10000 requests per minute.
   */
  public RateLimitFilter() {
    this(() -> Bucket4j.builder()
        .addLimit(Bandwidth.simple(10000, Duration.ofMinutes(1)))
        .build());
  }

  /**
   * Creates a filter with a custom bucket supplier. Intended for testing.
   *
   * @param bucketSupplier factory for new buckets per client key
   */
  public RateLimitFilter(Supplier<Bucket> bucketSupplier) {
    this.bucketSupplier = bucketSupplier;
  }

  @Override
  protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
      FilterChain filterChain) throws ServletException, IOException {
    String ip = extractClientIp(request);
    Bucket bucket = buckets.computeIfAbsent(ip, k -> bucketSupplier.get());
    if (bucket.tryConsume(1)) {
      filterChain.doFilter(request, response);
    } else {
      response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
    }
  }

  /**
   * Resolves the client IP address, preferring {@code X-Forwarded-For} if present.
   *
   * @param request current HTTP request
   * @return the best-effort client IP address
   */
  private String extractClientIp(HttpServletRequest request) {
    String forwarded = request.getHeader("X-Forwarded-For");
    if (forwarded != null && !forwarded.isBlank()) {
      return forwarded.split(",")[0].trim();
    }
    return request.getRemoteAddr();
  }
}
