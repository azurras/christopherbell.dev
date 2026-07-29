package dev.christopherbell.configuration.security;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.christopherbell.account.AccountRepository;
import dev.christopherbell.configuration.security.browser.BrowserSessionRepository;
import dev.christopherbell.configuration.security.browser.InteractiveBrowserRequest;
import jakarta.servlet.DispatcherType;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authorization.AuthorizationDeniedException;
import org.springframework.security.test.context.support.WithAnonymousUser;
import org.springframework.security.web.DefaultSecurityFilterChain;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

@WebMvcTest(AsyncDispatcherSecurityIntegrationTest.ProtectedController.class)
@Import({
    SecurityConfig.class,
    BrowserAuthenticationCookies.class,
    InteractiveBrowserRequest.class,
    AsyncDispatcherSecurityIntegrationTest.ProtectedController.class
})
class AsyncDispatcherSecurityIntegrationTest {
  @Autowired private SecurityFilterChain securityFilterChain;
  @Autowired private MockMvc mockMvc;
  @MockitoBean private AccountRepository accounts;
  @MockitoBean private BrowserSessionRepository browserSessions;

  @Test
  @WithAnonymousUser
  void protectedInitialRequestStillRequiresAuthentication() {
    assertThrows(AuthorizationDeniedException.class,
        () -> passesAuthorization(DispatcherType.REQUEST));
  }

  @Test
  @WithAnonymousUser
  void asyncAndErrorRedispatchesDoNotRequireSecondAuthentication() throws Exception {
    assertTrue(passesAuthorization(DispatcherType.ASYNC));
    assertTrue(passesAuthorization(DispatcherType.ERROR));
  }

  @Test
  void authenticatedStreamingRequestCompletesThroughAsyncRedispatch() throws Exception {
    var result = mockMvc.perform(get("/api/test/protected-stream")
            .with(org.springframework.security.test.web.servlet.request
                .SecurityMockMvcRequestPostProcessors.user("test-user")))
        .andExpect(request().asyncStarted())
        .andReturn();

    result.getAsyncResult();
    mockMvc.perform(asyncDispatch(result))
        .andExpect(status().isOk())
        .andExpect(content().string("ready"));
  }

  private boolean passesAuthorization(DispatcherType dispatcherType) throws Exception {
    var request = new MockHttpServletRequest("GET", "/api/test/protected");
    request.setServletPath("/api/test/protected");
    request.setDispatcherType(dispatcherType);
    var continued = new AtomicBoolean();
    authorizationFilter().doFilter(
        request,
        new MockHttpServletResponse(),
        (ignoredRequest, ignoredResponse) -> continued.set(true));
    return continued.get();
  }

  private AuthorizationFilter authorizationFilter() {
    return ((DefaultSecurityFilterChain) securityFilterChain).getFilters().stream()
        .filter(AuthorizationFilter.class::isInstance)
        .map(AuthorizationFilter.class::cast)
        .findFirst()
        .orElseThrow();
  }

  @RestController
  public static class ProtectedController {
    @GetMapping("/api/test/protected")
    String protectedEndpoint() {
      return "protected";
    }

    @GetMapping("/api/test/protected-stream")
    ResponseEntity<StreamingResponseBody> protectedStream() {
      return ResponseEntity.ok(output ->
          output.write("ready".getBytes(StandardCharsets.UTF_8)));
    }
  }
}
