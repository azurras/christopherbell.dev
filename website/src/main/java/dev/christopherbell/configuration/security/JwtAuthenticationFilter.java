package dev.christopherbell.configuration.security;

import dev.christopherbell.account.AccountRepository;
import dev.christopherbell.account.api.LoginTokens;
import dev.christopherbell.account.auth.AccountSecurityFingerprint;
import dev.christopherbell.account.model.Account;
import dev.christopherbell.account.model.AccountStatus;
import dev.christopherbell.configuration.security.browser.AuthenticatedBrowserSession;
import dev.christopherbell.configuration.security.browser.BrowserSessionService;
import dev.christopherbell.configuration.security.browser.InteractiveBrowserRequest;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;
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
  private final LoginTokens loginTokens;

  public JwtAuthenticationFilter(List<RequestMatcher> skipMatchers) {
    this(skipMatchers, null, null, null, null, null);
  }

  public JwtAuthenticationFilter(
      List<RequestMatcher> skipMatchers,
      BrowserSessionService browserSessions,
      InteractiveBrowserRequest interactiveRequests,
      BrowserAuthenticationCookies browserCookies) {
    this(skipMatchers, browserSessions, interactiveRequests, browserCookies, null, null);
  }

  public JwtAuthenticationFilter(
      List<RequestMatcher> skipMatchers,
      BrowserSessionService browserSessions,
      InteractiveBrowserRequest interactiveRequests,
      BrowserAuthenticationCookies browserCookies,
      AccountRepository accounts,
      LoginTokens loginTokens) {
    this.skipMatchers.addAll(skipMatchers);
    this.browserSessions = browserSessions;
    this.interactiveRequests = interactiveRequests;
    this.browserCookies = browserCookies;
    this.accounts = accounts;
    this.loginTokens = loginTokens;
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
    Optional<Authenticated> authenticated;
    try {
      authenticated = authenticate(request, bearerToken, cookieToken);
    } catch (RuntimeException invalidCredential) {
      rejectCredential(publicRequest, response, chain, request, cookieToken != null);
      return;
    }
    if (authenticated.isEmpty()) {
      rejectCredential(publicRequest, response, chain, request, cookieToken != null);
      return;
    }

    var result = authenticated.orElseThrow();
    SecurityContextHolder.getContext().setAuthentication(result.authentication());
    if (browserCookies != null) {
      result.rotatedToken().ifPresent(token -> addCookies(response, browserCookies.authenticated(token)));
    }
    chain.doFilter(request, response);
  }

  /**
   * Authenticates an explicit bearer token first, then a browser session cookie.
   *
   * @return the authentication and any rotated session token, or empty when neither credential is valid
   * @throws RuntimeException when a credential is malformed or cannot be verified
   */
  private Optional<Authenticated> authenticate(
      HttpServletRequest request,
      String bearerToken,
      String cookieToken
  ) {
    if (bearerToken != null && accounts != null && loginTokens != null) {
      var bearer = bearerAccount(bearerToken)
          .map(account -> new Authenticated(getAuthentication(account, bearerToken), Optional.empty()));
      if (bearer.isPresent()) {
        return bearer;
      }
    }
    if (cookieToken != null && browserSessions != null) {
      var interactive = interactiveRequests != null && interactiveRequests.matches(request);
      return browserSessions.authenticate(cookieToken, interactive)
          .map(session -> new Authenticated(getAuthentication(session), session.rotatedToken()));
    }
    return Optional.empty();
  }

  /** The active account whose security fingerprint matches the token's claims. */
  private Optional<Account> bearerAccount(String bearerToken) {
    var claims = loginTokens.verifiedClaimsOf(bearerToken);
    return accounts.findById(claims.getSubject())
        .filter(candidate -> candidate.getStatus() == AccountStatus.ACTIVE)
        .filter(candidate -> AccountSecurityFingerprint.matches(
            claims.get(AccountSecurityFingerprint.CLAIM, String.class), candidate));
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

  private void addCookies(HttpServletResponse response, List<ResponseCookie> cookies) {
    cookies.forEach(cookie -> response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString()));
  }

  /** A successful authentication and the browser session token to rotate to, if any. */
  private record Authenticated(Authentication authentication, Optional<String> rotatedToken) {}
}
