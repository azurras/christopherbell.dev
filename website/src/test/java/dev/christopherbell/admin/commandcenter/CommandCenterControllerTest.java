package dev.christopherbell.admin.commandcenter;

import static dev.christopherbell.admin.commandcenter.action.CommandCenterActionType.RESTART_SITE;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.christopherbell.account.AccountRepository;
import dev.christopherbell.account.model.Account;
import dev.christopherbell.account.model.AccountStatus;
import dev.christopherbell.account.model.Role;
import dev.christopherbell.admin.commandcenter.action.CommandCenterActionService;
import dev.christopherbell.admin.commandcenter.action.CommandCenterActionService.ActionChallenge;
import dev.christopherbell.admin.commandcenter.action.CommandCenterActionService.ActionConfirmation;
import dev.christopherbell.admin.commandcenter.action.CommandCenterActionService.ActionResult;
import dev.christopherbell.admin.commandcenter.logs.CommandCenterLogService;
import dev.christopherbell.admin.commandcenter.logs.CommandCenterLogService.LogPage;
import dev.christopherbell.admin.commandcenter.logs.CommandCenterLogService.LogRecord;
import dev.christopherbell.admin.commandcenter.metrics.CommandCenterMetricsService;
import dev.christopherbell.admin.commandcenter.model.CommandCenterSnapshot;
import dev.christopherbell.admin.commandcenter.model.CommandCenterSnapshot.HealthStatus;
import dev.christopherbell.libs.api.controller.ControllerExceptionHandler;
import dev.christopherbell.permission.PermissionService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.test.context.TestSecurityContextHolder;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@WebMvcTest(CommandCenterController.class)
@Import({CommandCenterAccessService.class, ControllerExceptionHandler.class,
    CommandCenterControllerTest.MethodSecurityTestConfiguration.class})
class CommandCenterControllerTest {
  private static final String BASE = "/api/admin/command-center/2026-07-12";
  private static final Instant NOW = Instant.parse("2026-07-12T18:00:00Z");

  @Autowired private MockMvc mockMvc;
  @MockitoBean private CommandCenterMetricsService metricsService;
  @MockitoBean private CommandCenterLogService logService;
  @MockitoBean private CommandCenterActionService actionService;
  @MockitoBean private AccountRepository accountRepository;
  @MockitoBean(name = "permissionService") private PermissionService permissionService;

  @BeforeEach
  void authorizeOnlyAdmins() {
    when(permissionService.hasAuthority("ADMIN")).thenAnswer(ignored ->
        SecurityContextHolder.getContext().getAuthentication().getAuthorities().stream()
            .anyMatch(authority -> authority.getAuthority().equals("ADMIN")));
    when(permissionService.getSelfId()).thenReturn("admin-1");
    when(accountRepository.findById("admin-1")).thenReturn(Optional.of(admin(
        AccountStatus.ACTIVE, true)));
  }

  @ParameterizedTest(name = "{0}: anonymous -> 401")
  @MethodSource("routes")
  void everyRoute_whenAnonymous_returnsUnauthorized(
      String name, MockHttpServletRequestBuilder request) throws Exception {
    mockMvc.perform(request).andExpect(status().isUnauthorized());

    verifyNoInteractions(metricsService, logService, actionService);
  }

  @ParameterizedTest(name = "{0}: non-admin -> 403")
  @MethodSource("routes")
  @WithMockUser(authorities = "USER")
  void everyRoute_whenNonAdmin_returnsForbidden(
      String name, MockHttpServletRequestBuilder request) throws Exception {
    mockMvc.perform(request).andExpect(status().isForbidden());

    verifyNoInteractions(metricsService, logService, actionService);
  }

  @ParameterizedTest(name = "{0}: suspended persisted admin -> 403")
  @MethodSource("routes")
  @WithMockUser(authorities = "ADMIN")
  void everyRoute_whenPersistedAdminIsSuspended_returnsForbidden(
      String name, MockHttpServletRequestBuilder request) throws Exception {
    when(accountRepository.findById("admin-1")).thenReturn(Optional.of(admin(
        AccountStatus.SUSPENDED, true)));

    mockMvc.perform(request).andExpect(status().isForbidden());

    verifyNoInteractions(metricsService, logService, actionService);
  }

  @ParameterizedTest(name = "{0}: unapproved persisted admin -> 403")
  @MethodSource("routes")
  @WithMockUser(authorities = "ADMIN")
  void everyRoute_whenPersistedAdminIsUnapproved_returnsForbidden(
      String name, MockHttpServletRequestBuilder request) throws Exception {
    when(accountRepository.findById("admin-1")).thenReturn(Optional.of(admin(
        AccountStatus.ACTIVE, false)));

    mockMvc.perform(request).andExpect(status().isForbidden());

    verifyNoInteractions(metricsService, logService, actionService);
  }

  @ParameterizedTest(name = "{0}: missing persisted admin -> 403")
  @MethodSource("routes")
  @WithMockUser(authorities = "ADMIN")
  void everyRoute_whenPersistedAdminIsMissing_returnsForbidden(
      String name, MockHttpServletRequestBuilder request) throws Exception {
    when(accountRepository.findById("admin-1")).thenReturn(Optional.empty());

    mockMvc.perform(request).andExpect(status().isForbidden());

    verifyNoInteractions(metricsService, logService, actionService);
  }

  @Test
  @DisplayName("Snapshot: admin -> 200 with response envelope")
  @WithMockUser(authorities = "ADMIN")
  void snapshot_whenAdmin_returnsSnapshot() throws Exception {
    when(metricsService.snapshot()).thenReturn(new CommandCenterSnapshot(
        HealthStatus.HEALTHY, NOW, List.of(), Map.of(), List.of(), null, "1.2.3", 45));

    mockMvc.perform(get(BASE + "/snapshot"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").value(true))
        .andExpect(jsonPath("$.payload.health").value("HEALTHY"))
        .andExpect(jsonPath("$.payload.applicationVersion").value("1.2.3"))
        .andExpect(jsonPath("$.payload.applicationUptimeSeconds").value(45));

    verify(metricsService).snapshot();
  }

  @Test
  @DisplayName("Logs: admin -> 200 with filters and page payload")
  @WithMockUser(authorities = "ADMIN")
  void logs_whenAdmin_returnsLogPage() throws Exception {
    when(logService.read("cursor-1", "WARN", "database"))
        .thenReturn(new LogPage("cursor-2", List.of(
            new LogRecord(18, "WARN", "database unavailable")), false, "AVAILABLE"));

    mockMvc.perform(get(BASE + "/logs")
            .queryParam("cursor", "cursor-1")
            .queryParam("level", "WARN")
            .queryParam("query", "database"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").value(true))
        .andExpect(jsonPath("$.payload.nextCursor").value("cursor-2"))
        .andExpect(jsonPath("$.payload.records[0].level").value("WARN"))
        .andExpect(jsonPath("$.payload.records[0].text").value("database unavailable"));

    verify(logService).read("cursor-1", "WARN", "database");
  }

  @Test
  @DisplayName("Action challenge: admin -> 200 with safe challenge payload")
  @WithMockUser(authorities = "ADMIN")
  void challenge_whenAdmin_returnsChallenge() throws Exception {
    when(actionService.createChallenge(eq(RESTART_SITE), any(HttpServletRequest.class))).thenReturn(
        new ActionChallenge("challenge-1", RESTART_SITE, NOW.plusSeconds(120), "RESTART SITE"));

    mockMvc.perform(post(BASE + "/action-challenges")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"action\":\"RESTART_SITE\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").value(true))
        .andExpect(jsonPath("$.payload.id").value("challenge-1"))
        .andExpect(jsonPath("$.payload.action").value("RESTART_SITE"))
        .andExpect(jsonPath("$.payload.confirmationPhrase").value("RESTART SITE"));

    verify(actionService).createChallenge(eq(RESTART_SITE), any(HttpServletRequest.class));
  }

  @Test
  @DisplayName("Snapshot: pending machine action -> 200 with countdown state")
  @WithMockUser(authorities = "ADMIN")
  void snapshot_whenPowerActionPending_returnsActionPendingState() throws Exception {
    when(metricsService.snapshot()).thenReturn(new CommandCenterSnapshot(
        HealthStatus.ACTION_PENDING, NOW, List.of(), Map.of(), List.of(),
        new CommandCenterSnapshot.PendingAction("RESTART_COMPUTER", NOW.plusSeconds(60), true),
        "1.2.3", 45));

    mockMvc.perform(get(BASE + "/snapshot"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.payload.health").value("ACTION_PENDING"))
        .andExpect(jsonPath("$.payload.pendingAction.action").value("RESTART_COMPUTER"))
        .andExpect(jsonPath("$.payload.pendingAction.cancellable").value(true));
  }

  @Test
  @DisplayName("Action execute: admin -> 202 with acceptance payload")
  @WithMockUser(authorities = "ADMIN")
  void execute_whenAdmin_returnsAccepted() throws Exception {
    when(actionService.execute(any(ActionConfirmation.class), any(HttpServletRequest.class)))
        .thenReturn(new ActionResult(RESTART_SITE, true, NOW, NOW.plusSeconds(2)));

    mockMvc.perform(post(BASE + "/actions")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"challengeId":"challenge-1","action":"RESTART_SITE",
                 "password":"private","confirmationPhrase":"RESTART SITE"}
                """))
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.success").value(true))
        .andExpect(jsonPath("$.payload.action").value("RESTART_SITE"))
        .andExpect(jsonPath("$.payload.accepted").value(true));

    verify(actionService).execute(any(ActionConfirmation.class), any(HttpServletRequest.class));
  }

  @Test
  @DisplayName("Action cancel: admin -> 200 with acceptance payload")
  @WithMockUser(authorities = "ADMIN")
  void cancel_whenAdmin_returnsResult() throws Exception {
    when(actionService.cancel(any(HttpServletRequest.class)))
        .thenReturn(new ActionResult(RESTART_SITE, true, NOW, NOW));

    mockMvc.perform(post(BASE + "/actions/cancel"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").value(true))
        .andExpect(jsonPath("$.payload.accepted").value(true));

    verify(actionService).cancel(any(HttpServletRequest.class));
  }

  @Test
  @DisplayName("Logs: query longer than 100 characters -> 400 without service call")
  @WithMockUser(authorities = "ADMIN")
  void logs_whenQueryTooLong_returnsBadRequest() throws Exception {
    mockMvc.perform(get(BASE + "/logs").queryParam("query", "x".repeat(101)))
        .andExpect(status().isBadRequest());

    verifyNoInteractions(metricsService, logService, actionService);
  }

  @Test
  @DisplayName("Action challenge: missing action -> 400 without service call")
  @WithMockUser(authorities = "ADMIN")
  void challenge_whenActionMissing_returnsBadRequest() throws Exception {
    mockMvc.perform(post(BASE + "/action-challenges")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{}"))
        .andExpect(status().isBadRequest());

    verifyNoInteractions(metricsService, logService, actionService);
  }

  @Test
  @DisplayName("Action execute: blank protected fields -> 400 without service call")
  @WithMockUser(authorities = "ADMIN")
  void execute_whenRequiredFieldsBlank_returnsBadRequest() throws Exception {
    mockMvc.perform(post(BASE + "/actions")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"challengeId":"","action":"RESTART_SITE","password":"",
                 "confirmationPhrase":""}
                """))
        .andExpect(status().isBadRequest());

    verifyNoInteractions(metricsService, logService, actionService);
  }

  private static Stream<Arguments> routes() {
    return Stream.of(
        Arguments.of("snapshot", get(BASE + "/snapshot")),
        Arguments.of("logs", get(BASE + "/logs")),
        Arguments.of("challenge", post(BASE + "/action-challenges")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"action\":\"RESTART_SITE\"}")),
        Arguments.of("execute", post(BASE + "/actions")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"challengeId":"challenge-1","action":"RESTART_SITE",
                 "password":"private","confirmationPhrase":"RESTART SITE"}
                """)),
        Arguments.of("cancel", post(BASE + "/actions/cancel")));
  }

  private Account admin(AccountStatus status, boolean approved) {
    return Account.builder()
        .id("admin-1")
        .role(Role.ADMIN)
        .status(status)
        .isApproved(approved)
        .build();
  }

  @TestConfiguration
  @EnableMethodSecurity
  @EnableWebSecurity
  static class MethodSecurityTestConfiguration {
    @Bean
    SecurityFilterChain commandCenterTestSecurityFilterChain(HttpSecurity http) throws Exception {
      return http
          .csrf(AbstractHttpConfigurer::disable)
          .exceptionHandling(exceptions -> exceptions
              .authenticationEntryPoint((request, response, exception) -> response.sendError(401))
              .accessDeniedHandler((request, response, exception) -> response.sendError(403)))
          .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
          .addFilterBefore(new TestSecurityContextBridgeFilter(),
              UsernamePasswordAuthenticationFilter.class)
          .build();
    }

    private static class TestSecurityContextBridgeFilter
        extends org.springframework.web.filter.OncePerRequestFilter {
      @Override
      protected void doFilterInternal(
          HttpServletRequest request,
          HttpServletResponse response,
          FilterChain filterChain) throws ServletException, IOException {
        var authentication = TestSecurityContextHolder.getContext().getAuthentication();
        if (authentication != null) {
          SecurityContextHolder.getContext().setAuthentication(authentication);
        }
        filterChain.doFilter(request, response);
      }
    }
  }
}
