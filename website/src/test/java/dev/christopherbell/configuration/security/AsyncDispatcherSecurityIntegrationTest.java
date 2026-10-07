package dev.christopherbell.configuration.security;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.christopherbell.account.api.LoginTokensFixture;
import dev.christopherbell.account.AccountRepository;
import dev.christopherbell.configuration.security.browser.BrowserSessionActivityStore;
import dev.christopherbell.configuration.security.browser.BrowserSessionAuthenticationStore;
import dev.christopherbell.configuration.security.browser.BrowserSessionRepository;
import dev.christopherbell.configuration.security.browser.InteractiveBrowserRequest;
import jakarta.servlet.DispatcherType;
import java.nio.charset.StandardCharsets;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.task.AsyncTaskExecutor;
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
import org.springframework.web.servlet.config.annotation.AsyncSupportConfigurer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

@WebMvcTest(AsyncDispatcherSecurityIntegrationTest.ProtectedController.class)
@Import({
    SecurityConfig.class,
    LoginTokensFixture.TestConfigurationWithLoginTokens.class,
    BrowserAuthenticationCookies.class,
    InteractiveBrowserRequest.class,
    AsyncDispatcherSecurityIntegrationTest.ProtectedController.class,
    AsyncDispatcherSecurityIntegrationTest.DeferredAsyncConfiguration.class
})
class AsyncDispatcherSecurityIntegrationTest {
  @Autowired private SecurityFilterChain securityFilterChain;
  @Autowired private DeferredTaskExecutor asyncTaskExecutor;
  @Autowired private MockMvc mockMvc;
  @MockitoBean private AccountRepository accounts;
  @MockitoBean private BrowserSessionRepository browserSessions;
  @MockitoBean private BrowserSessionActivityStore browserSessionActivity;
  @MockitoBean private BrowserSessionAuthenticationStore browserSessionAuthentications;

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

    // The request thread has finished; only now may the streaming body write, as a servlet
    // container orders it. Running it concurrently raced the mock response's header map.
    asyncTaskExecutor.runPendingTasks();
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

  /**
   * MockMvc starts async work immediately, while the request thread is still writing headers
   * on the same non-thread-safe mock response. Deferring the work until the test releases it
   * keeps the two threads apart.
   */
  @TestConfiguration(proxyBeanMethods = false)
  static class DeferredAsyncConfiguration {
    @Bean
    DeferredTaskExecutor deferredTaskExecutor() {
      return new DeferredTaskExecutor();
    }

    @Bean
    WebMvcConfigurer deferredAsyncSupport(DeferredTaskExecutor deferredTaskExecutor) {
      return new WebMvcConfigurer() {
        @Override
        public void configureAsyncSupport(AsyncSupportConfigurer configurer) {
          configurer.setTaskExecutor(deferredTaskExecutor);
        }
      };
    }
  }

  /** Queues async tasks and runs them on the calling thread when the test releases them. */
  static final class DeferredTaskExecutor implements AsyncTaskExecutor {
    private final Queue<Runnable> pendingTasks = new ConcurrentLinkedQueue<>();

    @Override
    public void execute(Runnable task) {
      pendingTasks.add(task);
    }

    void runPendingTasks() {
      Runnable task;
      while ((task = pendingTasks.poll()) != null) {
        task.run();
      }
    }
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
