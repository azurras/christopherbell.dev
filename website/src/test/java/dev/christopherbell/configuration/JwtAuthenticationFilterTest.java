package dev.christopherbell.configuration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import dev.christopherbell.account.model.Account;
import dev.christopherbell.account.model.Role;
import dev.christopherbell.permission.PermissionService;
import jakarta.servlet.ServletException;
import java.io.IOException;
import java.util.List;
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
    var filter = new JwtAuthenticationFilter(List.of());
    var request = new MockHttpServletRequest("GET", "/api/protected");
    request.addHeader("Authorization", "Bearer " + token(Role.USER));
    var response = new MockHttpServletResponse();

    filter.doFilter(request, response, new MockFilterChain());

    var authentication = SecurityContextHolder.getContext().getAuthentication();
    assertNotNull(authentication);
    assertEquals("account-1", authentication.getName());
    assertEquals("USER", authentication.getAuthorities().iterator().next().getAuthority());
    assertEquals(200, response.getStatus());
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

  private String token(Role role) {
    return PermissionService.generateToken(Account.builder()
        .id("account-1")
        .role(role)
        .build());
  }
}
