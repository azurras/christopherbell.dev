package dev.christopherbell.configuration.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;
import java.io.IOException;
import java.time.Duration;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.CacheControl;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Upgrades successful release-versioned static resources to immutable one-year caching. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class VersionedStaticAssetCacheFilter extends OncePerRequestFilter {
  private static final Set<String> CACHEABLE_METHODS = Set.of("GET", "HEAD");
  private static final Pattern VERSIONED_ASSET = Pattern.compile(
      "^/[^/]+/(?:(?:css|js|images)/.+|favicon\\.ico)$");
  private static final String BASE_CACHE_CONTROL = CacheControl
      .maxAge(Duration.ofHours(1))
      .cachePublic()
      .getHeaderValue();
  private static final String VERSIONED_CACHE_CONTROL = CacheControl
      .maxAge(Duration.ofDays(365))
      .cachePublic()
      .immutable()
      .getHeaderValue();

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    return !CACHEABLE_METHODS.contains(request.getMethod())
        || !VERSIONED_ASSET.matcher(request.getRequestURI()).matches();
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request,
      HttpServletResponse response,
      FilterChain filterChain) throws ServletException, IOException {
    filterChain.doFilter(request, new VersionedCacheResponse(response));
  }

  private static final class VersionedCacheResponse extends HttpServletResponseWrapper {
    private VersionedCacheResponse(HttpServletResponse response) {
      super(response);
    }

    @Override
    public void setHeader(String name, String value) {
      super.setHeader(name, cacheControl(name, value));
    }

    @Override
    public void addHeader(String name, String value) {
      super.addHeader(name, cacheControl(name, value));
    }

    private String cacheControl(String name, String value) {
      if ("Cache-Control".equalsIgnoreCase(name) && BASE_CACHE_CONTROL.equals(value)) {
        return VERSIONED_CACHE_CONTROL;
      }
      return value;
    }
  }
}
