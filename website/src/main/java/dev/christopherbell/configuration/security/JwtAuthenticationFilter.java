package dev.christopherbell.configuration.security;

import dev.christopherbell.account.model.Account;
import dev.christopherbell.permission.PermissionService;
import io.jsonwebtoken.Claims;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.web.util.WebUtils;

/**
 * Servlet filter that authenticates an explicit bearer JWT or the HttpOnly browser cookie.
 *
 * <p>Skips paths matched by the configured {@link RequestMatcher}s. When a
 * valid token is present, sets the Spring Security {@link Authentication}
 * into the {@link SecurityContextHolder}.</p>
 */
@Order(2)
public class JwtAuthenticationFilter extends OncePerRequestFilter {

  private final List<RequestMatcher> skipMatchers = new ArrayList<>();

  public JwtAuthenticationFilter(List<RequestMatcher> skipMatchers) {
    this.skipMatchers.addAll(skipMatchers);
  }

  /**
   * Determines whether this filter should be skipped for the given request.
   *
   * @param request incoming HTTP request
   * @return {@code true} if any configured skip matcher matches
   */
  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    return isPublicRequest(request) && resolveToken(request) == null;
  }

  @Override
  protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {

    boolean publicRequest = isPublicRequest(request);
    String token = resolveToken(request);
    if (token == null) {
      // No token: continue as anonymous; downstream security will enforce access rules
      chain.doFilter(request, response);
      return;
    }
    try {
      if (Objects.nonNull(PermissionService.validateToken(token))) {
        Authentication authenticationToken = getAuthentication(token);
        SecurityContextHolder.getContext().setAuthentication(authenticationToken);
        if (authenticationToken.isAuthenticated()) {
          chain.doFilter(request, response);
        } else {
          response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        }
      }
    } catch (Exception e) {
      if (publicRequest) {
        SecurityContextHolder.clearContext();
        chain.doFilter(request, response);
      } else {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
      }
    }
  }

  private boolean isPublicRequest(HttpServletRequest request) {
    return skipMatchers.stream().anyMatch(matcher -> matcher.matches(request));
  }

  /**
   * Resolves an explicit bearer token first, otherwise the browser authentication cookie.
   *
   * @param request current HTTP request
   * @return the JWT value, or {@code null} when no supported credential is present
   */
  private String resolveToken(HttpServletRequest request) {
    String bearerToken = request.getHeader(HttpHeaders.AUTHORIZATION);
    if (bearerToken != null && bearerToken.startsWith("Bearer ")) {
      var token = bearerToken.substring("Bearer ".length()).trim();
      return token.isEmpty() ? null : token;
    }
    var cookie = WebUtils.getCookie(request, BrowserAuthenticationCookies.AUTH_COOKIE_NAME);
    if (cookie == null || cookie.getValue() == null || cookie.getValue().isBlank()) {
      return null;
    }
    return cookie.getValue().trim();
  }

  /**
   * Builds an {@link Authentication} from a validated JWT.
   *
   * @param token the raw JWT token
   * @return a {@link UsernamePasswordAuthenticationToken} populated with subject and authorities
   */
  private Authentication getAuthentication(String token) {
    Claims claims = PermissionService.validateToken(token);
    String username = claims.getSubject();
    String roles = claims.get(Account.PROPERTY_ROLE, String.class);
    List<GrantedAuthority> authorities = Arrays.stream(roles.split(","))
        .map(SimpleGrantedAuthority::new)
        .collect(Collectors.toList());
    return new UsernamePasswordAuthenticationToken(username, token, authorities);
  }
}
