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
import dev.christopherbell.configuration.security.browser.BrowserSessionService;
import dev.christopherbell.configuration.security.browser.InteractiveBrowserRequest;
import dev.christopherbell.permission.PermissionService;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.ServletException;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.net.URI;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
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
}
