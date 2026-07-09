package dev.christopherbell.configuration.filter;

import dev.christopherbell.configuration.ClientIpProperties;
import dev.christopherbell.configuration.ClientIpResolver;
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
  private final ClientIpResolver clientIpResolver;
  private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();

  /**
   * Creates a filter with default limit of 10000 requests per minute.
   */
  public RateLimitFilter() {
    this(() -> Bucket4j.builder()
        .addLimit(Bandwidth.simple(10000, Duration.ofMinutes(1)))
        .build(), new ClientIpResolver(new ClientIpProperties()));
  }

  /**
   * Creates a filter with default limit and shared client IP resolution.
   *
   * @param clientIpResolver trusted forwarding header resolver
   */
  public RateLimitFilter(ClientIpResolver clientIpResolver) {
    this(() -> Bucket4j.builder()
        .addLimit(Bandwidth.simple(10000, Duration.ofMinutes(1)))
        .build(), clientIpResolver);
  }

  /**
   * Creates a filter with a custom bucket supplier. Intended for testing.
   *
   * @param bucketSupplier factory for new buckets per client key
   */
  public RateLimitFilter(Supplier<Bucket> bucketSupplier) {
    this(bucketSupplier, new ClientIpResolver(new ClientIpProperties()));
  }

  /**
   * Creates a filter with a custom bucket supplier and client IP resolver. Intended for testing.
   *
   * @param bucketSupplier factory for new buckets per client key
   * @param clientIpResolver trusted forwarding header resolver
   */
  public RateLimitFilter(Supplier<Bucket> bucketSupplier, ClientIpResolver clientIpResolver) {
    this.bucketSupplier = bucketSupplier;
    this.clientIpResolver = clientIpResolver;
  }

  @Override
  protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
      FilterChain filterChain) throws ServletException, IOException {
    String ip = clientIpResolver.resolveClientIp(request);
    Bucket bucket = buckets.computeIfAbsent(ip, k -> bucketSupplier.get());
    if (bucket.tryConsume(1)) {
      filterChain.doFilter(request, response);
    } else {
      response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
    }
  }

}
