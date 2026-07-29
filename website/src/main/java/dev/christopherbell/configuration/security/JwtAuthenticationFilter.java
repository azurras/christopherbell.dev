package dev.christopherbell.configuration.security;

import dev.christopherbell.account.AccountRepository;
import dev.christopherbell.account.auth.AccountSecurityFingerprint;
import dev.christopherbell.account.model.Account;
import dev.christopherbell.account.model.AccountStatus;
import dev.christopherbell.configuration.security.browser.AuthenticatedBrowserSession;
import dev.christopherbell.configuration.security.browser.BrowserSessionService;
import dev.christopherbell.configuration.security.browser.InteractiveBrowserRequest;
import dev.christopherbell.permission.PermissionService;
import java.util.ArrayList;
import java.util.List;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
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
 * Servlet filter that authenticates an explicit bearer JWT or opaque HttpOnly browser session.
 *
 * <p>Skips paths matched by the configured {@link RequestMatcher}s. When a
 * valid token is present, sets the Spring Security {@link Authentication}
 * into the {@link SecurityContextHolder}.</p>
 */
@Order(2)
public class JwtAuthenticationFilter extends OncePerRequestFilter {

  private final List<RequestMatcher> skipMatchers = new ArrayList<>();
  private final RequestMatcher staticAssets = new StaticAssetRequestMatcher();
  private final BrowserSessionService browserSessions;
  private final InteractiveBrowserRequest interactiveRequests;
  private final BrowserAuthenticationCookies browserCookies;
  private final AccountRepository accounts;

  public JwtAuthenticationFilter(List<RequestMatcher> skipMatchers) {
    this(skipMatchers, null, null, null, null);
  }

  public JwtAuthenticationFilter(
      List<RequestMatcher> skipMatchers,
      BrowserSessionService browserSessions,
      InteractiveBrowserRequest interactiveRequests,
      BrowserAuthenticationCookies browserCookies) {
    this(skipMatchers, browserSessions, interactiveRequests, browserCookies, null);
  }

  public JwtAuthenticationFilter(
      List<RequestMatcher> skipMatchers,
      BrowserSessionService browserSessions,
      InteractiveBrowserRequest interactiveRequests,
      BrowserAuthenticationCookies browserCookies,
      AccountRepository accounts) {
    this.skipMatchers.addAll(skipMatchers);
    this.browserSessions = browserSessions;
    this.interactiveRequests = interactiveRequests;
    this.browserCookies = browserCookies;
    this.accounts = accounts;
  }

  /**
   * Determines whether this filter should be skipped for the given request.
   *
   * @param request incoming HTTP request
   * @return {@code true} if any configured skip matcher matches
   */
  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    if (staticAssets.matches(request)) {
      return true;
    }
    return isPublicRequest(request)
        && resolveBearerToken(request) == null
        && resolveCookieToken(request) == null;
  }

  @Override
  protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {

    boolean publicRequest = isPublicRequest(request);
    String bearerToken = resolveBearerToken(request);
    String cookieToken = bearerToken == null ? resolveCookieToken(request) : null;
    if (bearerToken == null && cookieToken == null) {
      // No token: continue as anonymous; downstream security will enforce access rules
      chain.doFilter(request, response);
      return;
    }
    try {
      if (bearerToken != null && accounts != null) {
        var claims = PermissionService.validateToken(bearerToken);
        var account = accounts.findById(claims.getSubject())
            .filter(candidate -> candidate.getStatus() == AccountStatus.ACTIVE)
            .filter(candidate -> AccountSecurityFingerprint.matches(
                claims.get(AccountSecurityFingerprint.CLAIM, String.class), candidate))
            .orElse(null);
        if (account != null) {
          SecurityContextHolder.getContext().setAuthentication(
              getAuthentication(account, bearerToken));
          chain.doFilter(request, response);
          return;
        }
      }
      if (cookieToken != null && browserSessions != null) {
        var resolved = browserSessions.authenticate(
            cookieToken,
            interactiveRequests != null && interactiveRequests.matches(request));
        if (resolved.isPresent()) {
          var session = resolved.get();
          SecurityContextHolder.getContext().setAuthentication(getAuthentication(session));
          if (browserCookies != null) {
            session.rotatedToken().ifPresent(token -> addCookies(
                response, browserCookies.authenticated(token)));
          }
          chain.doFilter(request, response);
          return;
        }
      }
      rejectCredential(publicRequest, response, chain, request, cookieToken != null);
    } catch (Exception e) {
      rejectCredential(publicRequest, response, chain, request, cookieToken != null);
    }
  }

  private boolean isPublicRequest(HttpServletRequest request) {
    return skipMatchers.stream().anyMatch(matcher -> matcher.matches(request));
  }

  /**
   * Resolves an explicit bearer token without interpreting browser cookie credentials as JWTs.
   *
   * @param request current HTTP request
   * @return the bearer JWT value, or {@code null} when none is present
   */
  private String resolveBearerToken(HttpServletRequest request) {
    String bearerToken = request.getHeader(HttpHeaders.AUTHORIZATION);
    if (bearerToken != null && bearerToken.startsWith("Bearer ")) {
      var token = bearerToken.substring("Bearer ".length()).trim();
      return token.isEmpty() ? null : token;
    }
    return null;
  }

  private String resolveCookieToken(HttpServletRequest request) {
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
  private Authentication getAuthentication(Account account, String token) {
    return new UsernamePasswordAuthenticationToken(
        account.getId(),
        token,
        List.of(new SimpleGrantedAuthority(account.getRole().name())));
  }

  private Authentication getAuthentication(AuthenticatedBrowserSession session) {
    return new UsernamePasswordAuthenticationToken(
        session.accountId(),
        null,
        List.of(new SimpleGrantedAuthority(session.role().name())));
  }

  private void rejectCredential(
      boolean publicRequest,
      HttpServletResponse response,
      FilterChain chain,
      HttpServletRequest request,
      boolean clearBrowserCookies) throws IOException, ServletException {
    SecurityContextHolder.clearContext();
    if (clearBrowserCookies && browserCookies != null) {
      addCookies(response, browserCookies.cleared());
    }
    if (publicRequest) {
      chain.doFilter(request, response);
    } else {
      response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
    }
  }

  private void addCookies(HttpServletResponse response, List<org.springframework.http.ResponseCookie> cookies) {
    cookies.forEach(cookie -> response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString()));
  }
}
