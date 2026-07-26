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
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

/** Applies endpoint-aware, per-client token buckets with bounded process-local state. */
@Order(1)
public class RateLimitFilter extends OncePerRequestFilter {
  private static final int MAX_CLIENT_KEY_LENGTH = 64;
  private static final String RATE_LIMITED = "RATE_LIMITED";
  private static final String RATE_LIMIT_DESCRIPTION =
      "Too many requests. Try again later.";
  private static final long NANOS_PER_SECOND = Duration.ofSeconds(1).toNanos();

  private final Supplier<Bucket> bucketSupplier;
  private final ClientIpResolver clientIpResolver;
  private final RateLimitProperties properties;
  private final ApiErrorResponseWriter errors;
  private final Clock clock;
  private final RateLimitBucketStore buckets;
  private final AntPathMatcher pathMatcher = new AntPathMatcher();

  /** Creates a filter with repository defaults. */
  public RateLimitFilter() {
    this(
        null,
        new ClientIpResolver(new ClientIpProperties()),
        new RateLimitProperties(),
        new ApiErrorResponseWriter(new ObjectMapper()),
        Clock.systemUTC());
  }

  /** Creates a filter with default rules and shared client-IP resolution. */
  public RateLimitFilter(ClientIpResolver clientIpResolver) {
    this(clientIpResolver, new RateLimitProperties());
  }

  /** Creates a filter with configured endpoint-aware limits. */
  public RateLimitFilter(ClientIpResolver clientIpResolver, RateLimitProperties properties) {
    this(
        clientIpResolver,
        properties,
        new ApiErrorResponseWriter(new ObjectMapper()),
        Clock.systemUTC());
  }

  /** Creates a filter with injectable JSON and wall-clock boundaries. */
  public RateLimitFilter(
      ClientIpResolver clientIpResolver,
      RateLimitProperties properties,
      ApiErrorResponseWriter errors,
      Clock clock
  ) {
    this(null, clientIpResolver, properties, errors, clock);
  }

  /** Creates a filter with a custom bucket supplier for focused tests. */
  public RateLimitFilter(Supplier<Bucket> bucketSupplier) {
    this(bucketSupplier, new ClientIpResolver(new ClientIpProperties()));
  }

  /** Creates a filter with a custom bucket supplier and client-IP resolver for focused tests. */
  public RateLimitFilter(Supplier<Bucket> bucketSupplier, ClientIpResolver clientIpResolver) {
    this(
        bucketSupplier,
        clientIpResolver,
        new RateLimitProperties(),
        new ApiErrorResponseWriter(new ObjectMapper()),
        Clock.systemUTC());
  }

  private RateLimitFilter(
      Supplier<Bucket> bucketSupplier,
      ClientIpResolver clientIpResolver,
      RateLimitProperties properties,
      ApiErrorResponseWriter errors,
      Clock clock
  ) {
    this.bucketSupplier = bucketSupplier;
    this.clientIpResolver = clientIpResolver;
    this.properties = properties;
    this.errors = errors;
    this.clock = clock;
    this.buckets = new RateLimitBucketStore(properties.getMaxBuckets(), System::nanoTime);
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request,
      HttpServletResponse response,
      FilterChain filterChain
  ) throws ServletException, IOException {
    String ip = clientIpResolver.resolveClientIp(request);
    RateLimitProperties.Rule rule = matchingRule(request);
    Bucket bucket = buckets.getOrCreate(
        bucketKey(rule, ip), rule.getWindow(), () -> newBucket(rule));
    var probe = bucket.tryConsumeAndReturnRemaining(1);
    long waitSeconds = probe.isConsumed()
        ? durationSecondsCeiling(rule.getWindow())
        : nanosToSecondsCeiling(probe.getNanosToWaitForRefill());
    setRateLimitHeaders(
        response,
        rule.getCapacity(),
        probe.getRemainingTokens(),
        saturatingAdd(clock.instant().getEpochSecond(), waitSeconds));

    if (probe.isConsumed()) {
      filterChain.doFilter(request, response);
      return;
    }

    response.setHeader("Retry-After", Long.toString(waitSeconds));
    errors.write(
        response,
        HttpStatus.TOO_MANY_REQUESTS.value(),
        RATE_LIMITED,
        RATE_LIMIT_DESCRIPTION);
  }

  private void setRateLimitHeaders(
      HttpServletResponse response,
      long limit,
      long remaining,
      long resetEpochSeconds
  ) {
    response.setHeader("X-RateLimit-Limit", Long.toString(limit));
    response.setHeader("X-RateLimit-Remaining", Long.toString(remaining));
    response.setHeader("X-RateLimit-Reset", Long.toString(resetEpochSeconds));
  }

  private RateLimitProperties.Rule matchingRule(HttpServletRequest request) {
    return rules().stream()
        .filter(rule -> matchesMethod(rule, request.getMethod()))
        .filter(rule -> matchesPath(rule, request.getRequestURI()))
        .findFirst()
        .orElseGet(() -> new RateLimitProperties.Rule(
            "default", 10_000, Duration.ofMinutes(1), List.of(), List.of("/**")));
  }

  private List<RateLimitProperties.Rule> rules() {
    List<RateLimitProperties.Rule> configuredRules = properties.getRules();
    return configuredRules == null || configuredRules.isEmpty()
        ? new RateLimitProperties().getRules()
        : configuredRules;
  }

  private boolean matchesMethod(RateLimitProperties.Rule rule, String method) {
    List<String> methods = rule.getMethods();
    if (methods == null || methods.isEmpty()) {
      return true;
    }
    return methods.stream().anyMatch(configuredMethod -> configuredMethod.equalsIgnoreCase(method));
  }

  private boolean matchesPath(RateLimitProperties.Rule rule, String path) {
    List<String> paths = rule.getPaths();
    if (paths == null || paths.isEmpty()) {
      return true;
    }
    return paths.stream().anyMatch(pattern -> pathMatcher.match(pattern, path));
  }

  private String bucketKey(RateLimitProperties.Rule rule, String ip) {
    String safeIp = ip == null || ip.isBlank() || ip.length() > MAX_CLIENT_KEY_LENGTH
        ? "unknown" : ip;
    return rule.getName().toLowerCase(Locale.ROOT) + ":" + safeIp;
  }

  private Bucket newBucket(RateLimitProperties.Rule rule) {
    if (bucketSupplier != null) {
      return bucketSupplier.get();
    }
    return Bucket4j.builder()
        .addLimit(Bandwidth.simple(rule.getCapacity(), rule.getWindow()))
        .build();
  }

  private long durationSecondsCeiling(Duration duration) {
    long seconds = duration.getSeconds();
    return duration.getNano() == 0 ? Math.max(1, seconds) : saturatingAdd(seconds, 1);
  }

  private long nanosToSecondsCeiling(long nanos) {
    if (nanos <= 0) {
      return 1;
    }
    return 1 + ((nanos - 1) / NANOS_PER_SECOND);
  }

  private long saturatingAdd(long left, long right) {
    try {
      return Math.addExact(left, right);
    } catch (ArithmeticException ignored) {
      return right >= 0 ? Long.MAX_VALUE : Long.MIN_VALUE;
    }
  }
}
