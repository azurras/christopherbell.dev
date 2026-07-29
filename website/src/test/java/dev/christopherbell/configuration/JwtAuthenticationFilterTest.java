package dev.christopherbell.configuration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.christopherbell.account.AccountRepository;
import dev.christopherbell.account.model.Account;
import dev.christopherbell.account.model.AccountPermission;
import dev.christopherbell.account.model.AccountStatus;
import dev.christopherbell.account.model.Role;
import dev.christopherbell.configuration.security.JwtAuthenticationFilter;
import dev.christopherbell.configuration.security.BrowserAuthenticationCookies;
import dev.christopherbell.configuration.security.BrowserSecurityProperties;
import dev.christopherbell.configuration.security.browser.AuthenticatedBrowserSession;
import dev.christopherbell.configuration.security.browser.BrowserSession;
import dev.christopherbell.configuration.security.browser.BrowserSessionActivityStore;
import dev.christopherbell.configuration.security.browser.BrowserSessionRepository;
import dev.christopherbell.configuration.security.browser.BrowserSessionService;
import dev.christopherbell.configuration.security.browser.InteractiveBrowserRequest;
import dev.christopherbell.permission.PermissionService;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.ServletException;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.net.URI;
import java.time.Clock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

class JwtAuthenticationFilterTest {

  @AfterEach
  void clearSecurityContext() {
    SecurityContextHolder.clearContext();
  }

  @Test
  @DisplayName("Valid bearer token authenticates the request")
  void doFilter_whenBearerTokenValid_setsAuthentication() throws ServletException, IOException {
    var account = account(Role.USER);
    var accounts = mock(AccountRepository.class);
    when(accounts.findById(account.getId())).thenReturn(Optional.of(account));
    var filter = new JwtAuthenticationFilter(List.of(), null, null, null, accounts);
    var request = new MockHttpServletRequest("GET", "/api/protected");
    request.addHeader("Authorization", "Bearer " + PermissionService.generateToken(account));
    var response = new MockHttpServletResponse();

    filter.doFilter(request, response, new MockFilterChain());

    var authentication = SecurityContextHolder.getContext().getAuthentication();
    assertNotNull(authentication);
    assertEquals("account-1", authentication.getName());
    assertEquals("USER", authentication.getAuthorities().iterator().next().getAuthority());
    assertEquals(200, response.getStatus());
  }

  @Test
  @DisplayName("Bearer token is rejected after account security state changes")
  void doFilter_whenAccountSecurityStateChanges_returnsUnauthorized()
      throws ServletException, IOException {
    var account = account(Role.USER);
    var token = PermissionService.generateToken(account);
    account.setPermissions(Set.of(AccountPermission.SHARED_FOLDER_READ));
    var accounts = mock(AccountRepository.class);
    when(accounts.findById(account.getId())).thenReturn(Optional.of(account));
    var filter = new JwtAuthenticationFilter(List.of(), null, null, null, accounts);
    var request = new MockHttpServletRequest("GET", "/api/protected");
    request.addHeader("Authorization", "Bearer " + token);
    var response = new MockHttpServletResponse();

    filter.doFilter(request, response, new MockFilterChain());

    assertNull(SecurityContextHolder.getContext().getAuthentication());
    assertEquals(401, response.getStatus());
  }

  @Test
  @DisplayName("Valid opaque browser session authenticates the request")
  void doFilter_whenOpaqueBrowserSessionValid_setsAuthentication()
      throws ServletException, IOException {
    var sessions = mock(BrowserSessionService.class);
    when(sessions.authenticate("session-id.secret", false)).thenReturn(Optional.of(
        new AuthenticatedBrowserSession("account-1", Role.USER, Optional.empty())));
    var filter = new JwtAuthenticationFilter(
        List.of(), sessions, new InteractiveBrowserRequest(), cookies());
    var request = new MockHttpServletRequest("GET", "/api/protected");
    request.setCookies(new Cookie("CBELL_AUTH", "session-id.secret"));
    var response = new MockHttpServletResponse();

    filter.doFilter(request, response, new MockFilterChain());

    var authentication = SecurityContextHolder.getContext().getAuthentication();
    assertNotNull(authentication);
    assertEquals("account-1", authentication.getName());
    assertEquals(200, response.getStatus());
  }

  @Test
  @DisplayName("A concurrent rotation loser response cannot erase the winner cookie")
  void doFilter_whenConcurrentRotationLoserRespondsLast_preservesWinnerCookie()
      throws ServletException, IOException {
    var fixture = new ConcurrentRotationFixture();
    var browserCookies = new HashMap<String, String>();

    var winnerResponse = fixture.authenticateOriginalToken();
    applySetCookies(browserCookies, winnerResponse);
    String winnerToken = browserCookies.get(BrowserAuthenticationCookies.AUTH_COOKIE_NAME);
    SecurityContextHolder.clearContext();
    var loserResponse = fixture.authenticateOriginalToken();
    applySetCookies(browserCookies, loserResponse);

    assertNotNull(winnerToken);
    assertEquals(200, winnerResponse.getStatus());
    assertEquals(200, loserResponse.getStatus());
    assertEquals(winnerToken, browserCookies.get(BrowserAuthenticationCookies.AUTH_COOKIE_NAME));
    assertTrue(loserResponse.getHeaders("Set-Cookie").isEmpty());
  }

  @Test
  @DisplayName("Revocation winning a concurrent rotation rejects and clears browser cookies")
  void doFilter_whenRevocationWinsConcurrentRotation_clearsCookies()
      throws ServletException, IOException {
    var fixture = new ConcurrentRotationFixture(true);
    var browserCookies = new HashMap<String, String>();
    applySetCookies(browserCookies, fixture.authenticateOriginalToken());
    SecurityContextHolder.clearContext();

    var revokedResponse = fixture.authenticateOriginalToken();
    applySetCookies(browserCookies, revokedResponse);

    assertEquals(401, revokedResponse.getStatus());
    assertNull(browserCookies.get(BrowserAuthenticationCookies.AUTH_COOKIE_NAME));
    assertTrue(revokedResponse.getHeaders("Set-Cookie").stream()
        .anyMatch(header -> header.contains("CBELL_AUTH=") && header.contains("Max-Age=0")));
  }

  @Test
  @DisplayName("An unexplained rotation miss rejects and clears the still-current cookie")
  void doFilter_whenRotationMissReloadsCurrentCredential_clearsCookies()
      throws ServletException, IOException {
    var fixture = new ConcurrentRotationFixture(false, true);

    var response = fixture.authenticateOriginalToken();

    assertEquals(401, response.getStatus());
    assertNull(SecurityContextHolder.getContext().getAuthentication());
    assertTrue(response.getHeaders("Set-Cookie").stream()
        .anyMatch(header -> header.contains("CBELL_AUTH=") && header.contains("Max-Age=0")));
  }

  @Test
  @DisplayName("Browser account lookup failure rejects and clears the cookie")
  void doFilter_whenBrowserAccountLookupFails_returnsUnauthorized()
      throws ServletException, IOException {
    var account = account(Role.USER);
    var accounts = mock(AccountRepository.class);
    var sessionRepository = mock(BrowserSessionRepository.class);
    var activity = mock(BrowserSessionActivityStore.class);
    var saved = ArgumentCaptor.forClass(BrowserSession.class);
    when(accounts.findById(account.getId()))
        .thenReturn(Optional.of(account))
        .thenThrow(new DataAccessResourceFailureException("mongo"));
    when(sessionRepository.save(saved.capture())).thenAnswer(invocation -> invocation.getArgument(0));
    when(sessionRepository.findById(org.mockito.ArgumentMatchers.anyString()))
        .thenAnswer(invocation -> Optional.of(saved.getValue()));
    var sessions = new BrowserSessionService(
        sessionRepository, activity, accounts, Clock.systemUTC());
    String token = sessions.create(PermissionService.generateToken(account));
    var filter = new JwtAuthenticationFilter(
        List.of(), sessions, new InteractiveBrowserRequest(), cookies());
    var request = new MockHttpServletRequest("GET", "/api/protected");
    request.setCookies(new Cookie("CBELL_AUTH", token));
    var response = new MockHttpServletResponse();

    filter.doFilter(request, response, new MockFilterChain());

    assertNull(SecurityContextHolder.getContext().getAuthentication());
    assertEquals(401, response.getStatus());
    assertTrue(response.getHeaders("Set-Cookie").stream()
        .anyMatch(header -> header.contains("CBELL_AUTH=") && header.contains("Max-Age=0")));
  }

  @Test
  @DisplayName("A JWT in the browser cookie is rejected")
  void doFilter_whenBrowserCookieContainsJwt_returnsUnauthorized()
      throws ServletException, IOException {
    var sessions = mock(BrowserSessionService.class);
    when(sessions.authenticate(org.mockito.ArgumentMatchers.anyString(),
        org.mockito.ArgumentMatchers.anyBoolean())).thenReturn(Optional.empty());
    var filter = new JwtAuthenticationFilter(
        List.of(), sessions, new InteractiveBrowserRequest(), cookies());
    var request = new MockHttpServletRequest("GET", "/api/protected");
    request.setCookies(new Cookie("CBELL_AUTH", token(Role.USER)));
    var response = new MockHttpServletResponse();

    filter.doFilter(request, response, new MockFilterChain());

    assertNull(SecurityContextHolder.getContext().getAuthentication());
    assertEquals(401, response.getStatus());
  }

  @Test
  @DisplayName("Explicit bearer header takes precedence over browser cookie")
  void doFilter_whenBearerAndCookiePresent_prioritizesBearer()
      throws ServletException, IOException {
    var filter = new JwtAuthenticationFilter(List.of());
    var request = new MockHttpServletRequest("GET", "/api/protected");
    request.addHeader("Authorization", "Bearer not-a-token");
    request.setCookies(new Cookie("CBELL_AUTH", token(Role.USER)));
    var response = new MockHttpServletResponse();

    filter.doFilter(request, response, new MockFilterChain());

    assertNull(SecurityContextHolder.getContext().getAuthentication());
    assertEquals(401, response.getStatus());
  }

  @Test
  @DisplayName("Invalid bearer token is rejected")
  void doFilter_whenBearerTokenInvalid_returnsUnauthorized() throws ServletException, IOException {
    var filter = new JwtAuthenticationFilter(List.of());
    var request = new MockHttpServletRequest("GET", "/api/protected");
    request.addHeader("Authorization", "Bearer not-a-token");
    var response = new MockHttpServletResponse();

    filter.doFilter(request, response, new MockFilterChain());

    assertNull(SecurityContextHolder.getContext().getAuthentication());
    assertEquals(401, response.getStatus());
  }

  @Test
  @DisplayName("Invalid bearer token on public request continues anonymously")
  void doFilter_whenPublicRequestHasInvalidBearerToken_continuesAnonymously()
      throws ServletException, IOException {
    var filter = new JwtAuthenticationFilter(List.of(request -> true));
    var request = new MockHttpServletRequest("GET", "/public");
    request.addHeader("Authorization", "Bearer not-a-token");
    var response = new MockHttpServletResponse();

    filter.doFilter(request, response, new MockFilterChain());

    assertNull(SecurityContextHolder.getContext().getAuthentication());
    assertEquals(200, response.getStatus());
  }

  @Test
  @DisplayName("Public requests without bearer tokens skip authentication")
  void doFilter_whenPublicRequestHasNoBearerToken_skipsAuthentication()
      throws ServletException, IOException {
    var filter = new JwtAuthenticationFilter(List.of(request -> true));
    var request = new MockHttpServletRequest("GET", "/public");
    var response = new MockHttpServletResponse();

    filter.doFilter(request, response, new MockFilterChain());

    assertNull(SecurityContextHolder.getContext().getAuthentication());
    assertEquals(200, response.getStatus());
  }

  @Test
  @DisplayName("Static assets skip cookie authentication unconditionally")
  void doFilter_whenStaticAssetHasBrowserCookie_skipsSessionLookup()
      throws ServletException, IOException {
    var sessions = mock(BrowserSessionService.class);
    var filter = new JwtAuthenticationFilter(
        List.of(request -> true), sessions, new InteractiveBrowserRequest(), cookies());
    var request = new MockHttpServletRequest("GET", "/js/app.js");
    request.setCookies(new Cookie("CBELL_AUTH", "session-id.secret"));
    var response = new MockHttpServletResponse();

    filter.doFilter(request, response, new MockFilterChain());

    org.mockito.Mockito.verifyNoInteractions(sessions);
    assertTrue(response.getHeaders("Set-Cookie").isEmpty());
    assertEquals(200, response.getStatus());
  }

  @Test
  @DisplayName("Versioned static assets skip bearer authentication unconditionally")
  void doFilter_whenVersionedStaticAssetHasBearer_skipsAccountLookup()
      throws ServletException, IOException {
    var accounts = mock(AccountRepository.class);
    var account = account(Role.USER);
    var filter = new JwtAuthenticationFilter(
        List.of(request -> true), null, null, null, accounts);
    var request = new MockHttpServletRequest(
        "GET", "/0123456789abcdef0123456789abcdef01234567/css/main.css");
    request.addHeader("Authorization", "Bearer " + PermissionService.generateToken(account));
    var response = new MockHttpServletResponse();

    filter.doFilter(request, response, new MockFilterChain());

    org.mockito.Mockito.verifyNoInteractions(accounts);
    assertEquals(200, response.getStatus());
  }

  private String token(Role role) {
    return PermissionService.generateToken(account(role));
  }

  private Account account(Role role) {
    return Account.builder()
        .id("account-1")
        .passwordHash("password-hash")
        .role(role)
        .status(AccountStatus.ACTIVE)
        .permissions(Set.of())
        .build();
  }

  private BrowserAuthenticationCookies cookies() {
    return new BrowserAuthenticationCookies(
        new BrowserSecurityProperties(URI.create("https://example.test"), true, true));
  }

  private void applySetCookies(
      Map<String, String> browserCookies, MockHttpServletResponse response) {
    for (String header : response.getHeaders("Set-Cookie")) {
      String nameValue = header.substring(0, header.indexOf(';'));
      int separator = nameValue.indexOf('=');
      String name = nameValue.substring(0, separator);
      String value = nameValue.substring(separator + 1);
      if (header.contains("Max-Age=0")) {
        browserCookies.remove(name);
      } else {
        browserCookies.put(name, value);
      }
    }
  }

  private static BrowserSession copy(BrowserSession session) {
    return BrowserSession.builder()
        .id(session.getId())
        .accountId(session.getAccountId())
        .role(session.getRole())
        .tokenHash(session.getTokenHash())
        .previousTokenHash(session.getPreviousTokenHash())
        .previousTokenExpiresOn(session.getPreviousTokenExpiresOn())
        .accountSecurityFingerprint(session.getAccountSecurityFingerprint())
        .createdOn(session.getCreatedOn())
        .lastSeenOn(session.getLastSeenOn())
        .rotatedOn(session.getRotatedOn())
        .idleExpiresOn(session.getIdleExpiresOn())
        .absoluteExpiresOn(session.getAbsoluteExpiresOn())
        .build();
  }

  private final class ConcurrentRotationFixture {
    private static final Instant CREATED_ON = Instant.parse("2026-07-28T12:00:00Z");
    private static final Instant ROTATED_ON = CREATED_ON.plus(Duration.ofDays(1));

    private final Account account = account(Role.USER);
    private final AccountRepository accounts = mock(AccountRepository.class);
    private final BrowserSessionRepository sessions = mock(BrowserSessionRepository.class);
    private final BrowserSessionActivityStore activity = mock(BrowserSessionActivityStore.class);
    private final AtomicInteger reads = new AtomicInteger();
    private final AtomicInteger rotations = new AtomicInteger();
    private final boolean revokeOnLostRotation;
    private final boolean rejectInitialRotation;
    private final JwtAuthenticationFilter filter;
    private final String originalToken;
    private final BrowserSession staleSnapshot;
    private BrowserSession persisted;
    private boolean revoked;

    private ConcurrentRotationFixture() {
      this(false, false);
    }

    private ConcurrentRotationFixture(boolean revokeOnLostRotation) {
      this(revokeOnLostRotation, false);
    }

    private ConcurrentRotationFixture(
        boolean revokeOnLostRotation, boolean rejectInitialRotation) {
      this.revokeOnLostRotation = revokeOnLostRotation;
      this.rejectInitialRotation = rejectInitialRotation;
      when(accounts.findById(account.getId())).thenReturn(Optional.of(account));
      when(sessions.save(org.mockito.ArgumentMatchers.any(BrowserSession.class)))
          .thenAnswer(invocation -> {
            persisted = copy(invocation.getArgument(0));
            return invocation.getArgument(0);
          });
      var creator = new BrowserSessionService(
          sessions, activity, accounts, Clock.fixed(CREATED_ON, ZoneOffset.UTC));
      originalToken = creator.create(PermissionService.generateToken(account));
      staleSnapshot = copy(persisted);
      when(sessions.findById(org.mockito.ArgumentMatchers.anyString()))
          .thenAnswer(invocation -> {
            if (reads.getAndIncrement() < 2) return Optional.of(copy(staleSnapshot));
            return revoked ? Optional.empty() : Optional.of(copy(persisted));
          });
      when(activity.rotate(
          org.mockito.ArgumentMatchers.anyString(),
          org.mockito.ArgumentMatchers.anyString(),
          org.mockito.ArgumentMatchers.any(),
          org.mockito.ArgumentMatchers.anyString(),
          org.mockito.ArgumentMatchers.any(),
          org.mockito.ArgumentMatchers.any(),
          org.mockito.ArgumentMatchers.any()))
          .thenAnswer(invocation -> {
            int rotation = rotations.getAndIncrement();
            if (rotation == 0 && rejectInitialRotation) return Optional.empty();
            if (rotation > 0) {
              revoked = revokeOnLostRotation;
              return Optional.empty();
            }
            persisted.setPreviousTokenHash(invocation.getArgument(1));
            persisted.setPreviousTokenExpiresOn(invocation.getArgument(5));
            persisted.setTokenHash(invocation.getArgument(3));
            persisted.setRotatedOn(invocation.getArgument(4));
            persisted.setLastSeenOn(invocation.getArgument(4));
            persisted.setIdleExpiresOn(invocation.getArgument(6));
            return Optional.of(copy(persisted));
          });
      var browserSessions = new BrowserSessionService(
          sessions, activity, accounts, Clock.fixed(ROTATED_ON, ZoneOffset.UTC));
      filter = new JwtAuthenticationFilter(
          List.of(), browserSessions, new InteractiveBrowserRequest(), cookies());
    }

    private MockHttpServletResponse authenticateOriginalToken()
        throws ServletException, IOException {
      var request = new MockHttpServletRequest("GET", "/account");
      request.addHeader("Accept", "text/html");
      request.setCookies(new Cookie(BrowserAuthenticationCookies.AUTH_COOKIE_NAME, originalToken));
      var response = new MockHttpServletResponse();

      filter.doFilter(request, response, new MockFilterChain());

      return response;
    }
  }
}
