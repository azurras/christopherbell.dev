package dev.christopherbell.federation.discovery;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpHeaders;
import org.springframework.web.filter.OncePerRequestFilter;

/** Applies public no-store headers before discovery success or error responses are written. */
public final class FederationNoStoreFilter extends OncePerRequestFilter {

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    String path = request.getRequestURI();
    return !("/.well-known/webfinger".equals(path)
        || "/.well-known/nodeinfo".equals(path)
        || "/nodeinfo/2.1".equals(path)
        || path.startsWith("/ap/users/"));
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request,
      HttpServletResponse response,
      FilterChain filterChain
  ) throws ServletException, IOException {
    response.setHeader(HttpHeaders.CACHE_CONTROL, "public, no-store");
    response.setHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "*");
    response.setHeader("X-Content-Type-Options", "nosniff");
    filterChain.doFilter(request, response);
  }
}
