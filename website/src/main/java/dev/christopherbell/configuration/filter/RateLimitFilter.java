package dev.christopherbell.configuration.filter;

import dev.christopherbell.configuration.ClientIpProperties;
import dev.christopherbell.configuration.ClientIpResolver;
import dev.christopherbell.configuration.RateLimitProperties;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.Bucket4j;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Simple per-client rate limiting filter using Bucket4j.
 *
 * <p>Applies a token bucket per configured endpoint group and client IP.</p>
 */
@Order(1)
public class RateLimitFilter extends OncePerRequestFilter {

  private final Supplier<Bucket> bucketSupplier;
  private final ClientIpResolver clientIpResolver;
  private final RateLimitProperties properties;
  private final AntPathMatcher pathMatcher = new AntPathMatcher();
  private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();

  /**
   * Creates a filter with default limit of 10000 requests per minute.
   */
  public RateLimitFilter() {
    this(() -> Bucket4j.builder()
        .addLimit(Bandwidth.simple(10000, Duration.ofMinutes(1)))
        .build(), new ClientIpResolver(new ClientIpProperties()), new RateLimitProperties());
  }

  /**
   * Creates a filter with default limit and shared client IP resolution.
   *
   * @param clientIpResolver trusted forwarding header resolver
   */
  public RateLimitFilter(ClientIpResolver clientIpResolver) {
    this(clientIpResolver, new RateLimitProperties());
  }

  /**
   * Creates a filter with configured endpoint-aware limits and shared client IP resolution.
   *
   * @param clientIpResolver trusted forwarding header resolver
   * @param properties endpoint-aware rate-limit properties
   */
  public RateLimitFilter(ClientIpResolver clientIpResolver, RateLimitProperties properties) {
    this(null, clientIpResolver, properties);
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
    this(bucketSupplier, clientIpResolver, new RateLimitProperties());
  }

  private RateLimitFilter(
      Supplier<Bucket> bucketSupplier,
      ClientIpResolver clientIpResolver,
      RateLimitProperties properties
  ) {
    this.bucketSupplier = bucketSupplier;
    this.clientIpResolver = clientIpResolver;
    this.properties = properties;
  }

  @Override
  protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
      FilterChain filterChain) throws ServletException, IOException {
    String ip = clientIpResolver.resolveClientIp(request);
    var rule = matchingRule(request);
    Bucket bucket = buckets.computeIfAbsent(bucketKey(rule, ip), k -> newBucket(rule));
    if (bucket.tryConsume(1)) {
      filterChain.doFilter(request, response);
    } else {
      response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
    }
  }

  private RateLimitProperties.Rule matchingRule(HttpServletRequest request) {
    return rules().stream()
        .filter(rule -> matchesMethod(rule, request.getMethod()))
        .filter(rule -> matchesPath(rule, request.getRequestURI()))
        .findFirst()
        .orElseGet(() -> new RateLimitProperties.Rule(
            "default",
            10_000,
            Duration.ofMinutes(1),
            List.of(),
            List.of("/**")));
  }

  private List<RateLimitProperties.Rule> rules() {
    var configuredRules = properties == null ? null : properties.getRules();
    return configuredRules == null || configuredRules.isEmpty()
        ? new RateLimitProperties().getRules()
        : configuredRules;
  }

  private boolean matchesMethod(RateLimitProperties.Rule rule, String method) {
    var methods = rule.getMethods();
    if (methods == null || methods.isEmpty()) {
      return true;
    }
    return methods.stream()
        .anyMatch(configuredMethod -> configuredMethod.equalsIgnoreCase(method));
  }

  private boolean matchesPath(RateLimitProperties.Rule rule, String path) {
    var paths = rule.getPaths();
    if (paths == null || paths.isEmpty()) {
      return true;
    }
    return paths.stream().anyMatch(pattern -> pathMatcher.match(pattern, path));
  }

  private String bucketKey(RateLimitProperties.Rule rule, String ip) {
    return rule.getName().toLowerCase(Locale.ROOT) + ":" + ip;
  }

  private Bucket newBucket(RateLimitProperties.Rule rule) {
    if (bucketSupplier != null) {
      return bucketSupplier.get();
    }
    return Bucket4j.builder()
        .addLimit(Bandwidth.simple(rule.getCapacity(), rule.getWindow()))
        .build();
  }
}
