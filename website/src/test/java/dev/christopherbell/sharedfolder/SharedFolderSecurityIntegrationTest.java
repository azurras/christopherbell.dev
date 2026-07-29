package dev.christopherbell.sharedfolder;

import static dev.christopherbell.account.model.AccountPermission.SHARED_FOLDER_READ;
import static dev.christopherbell.account.model.AccountPermission.SHARED_FOLDER_WRITE;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.christopherbell.account.AccountRepository;
import dev.christopherbell.account.model.Account;
import dev.christopherbell.account.model.AccountPermission;
import dev.christopherbell.account.model.AccountStatus;
import dev.christopherbell.account.model.Role;
import dev.christopherbell.configuration.SharedFolderProperties;
import dev.christopherbell.configuration.security.SecurityConfig;
import dev.christopherbell.configuration.security.BrowserAuthenticationCookies;
import dev.christopherbell.configuration.security.browser.AuthenticatedBrowserSession;
import dev.christopherbell.configuration.security.browser.BrowserSessionService;
import dev.christopherbell.configuration.security.browser.InteractiveBrowserRequest;
import dev.christopherbell.libs.api.controller.ControllerExceptionHandler;
import dev.christopherbell.permission.PermissionService;
import dev.christopherbell.sharedfolder.audit.SharedFolderAuditQueryService;
import dev.christopherbell.sharedfolder.audit.SharedFolderAuditRecorder;
import dev.christopherbell.sharedfolder.recycle.SharedFolderRecycleService;
import dev.christopherbell.sharedfolder.security.SharedFolderAccessService;
import dev.christopherbell.sharedfolder.service.SharedFolderBrowserService;
import dev.christopherbell.sharedfolder.service.SharedFolderCatalogService;
import dev.christopherbell.sharedfolder.service.SharedFolderDownloadService;
import dev.christopherbell.sharedfolder.service.SharedFolderMutationService;
import dev.christopherbell.sharedfolder.service.SharedFolderPreviewService;
import dev.christopherbell.sharedfolder.upload.SharedFolderUploadService;
import dev.christopherbell.sharedfolder.radio.SharedFolderRadioService;
import dev.christopherbell.sharedfolder.web.SharedFolderAdminController;
import dev.christopherbell.sharedfolder.web.SharedFolderReadController;
import dev.christopherbell.sharedfolder.web.SharedFolderWriteController;
import jakarta.servlet.http.Cookie;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

@WebMvcTest({
    SharedFolderReadController.class,
    SharedFolderWriteController.class,
    SharedFolderAdminController.class
})
@Import({
    SecurityConfig.class,
    ControllerExceptionHandler.class,
    PermissionService.class,
    BrowserAuthenticationCookies.class,
    InteractiveBrowserRequest.class,
    SharedFolderAccessService.class,
    SharedFolderAuditQueryService.class,
    SharedFolderSecurityIntegrationTest.SharedFolderTestConfiguration.class
})
class SharedFolderSecurityIntegrationTest {
  private static final String BASE = "/api/shared-folder/2026-07-17";
  private static final Path SANDBOX = temporaryDirectory();
  private static final Path SHARED_ROOT = SANDBOX.resolve("shared");
  private static final Path SYSTEM_ROOT = SANDBOX.resolve("system");
  private static final Path OUTSIDE_SENTINEL = SANDBOX.resolve("outside.txt");

  @Autowired private WebApplicationContext applicationContext;
  @MockitoBean private AccountRepository accountRepository;
  @MockitoBean private SharedFolderDownloadService downloads;
  @MockitoBean private SharedFolderCatalogService catalog;
  @MockitoBean private SharedFolderPreviewService previews;
  @MockitoBean private SharedFolderRadioService radio;
  @MockitoBean private SharedFolderUploadService uploads;
  @MockitoBean private SharedFolderRecycleService recycle;
  @MockitoBean private SharedFolderAuditRecorder auditRecorder;
  @MockitoBean private MongoTemplate mongo;
  @MockitoBean private BrowserSessionService browserSessions;

  private final AtomicReference<Account> persistedAccount = new AtomicReference<>();
  private MockMvc mockMvc;

  @DynamicPropertySource
  static void sharedFolderProperties(DynamicPropertyRegistry registry) {
    registry.add("app.shared-folder.root", () -> SHARED_ROOT.toString());
    registry.add("app.shared-folder.system-root", () -> SYSTEM_ROOT.toString());
    registry.add("app.shared-folder.enabled", () -> "true");
  }

  @BeforeEach
  void setUp() throws IOException {
    mockMvc = MockMvcBuilders.webAppContextSetup(applicationContext)
        .apply(springSecurity())
        .build();
    resetDirectory(SHARED_ROOT);
    resetDirectory(SYSTEM_ROOT);
    Files.writeString(OUTSIDE_SENTINEL, "outside");
    persistedAccount.set(null);
    when(accountRepository.findById(any())).thenAnswer(invocation -> {
      Account account = persistedAccount.get();
      String requestedId = invocation.getArgument(0);
      return account != null && requestedId.equals(account.getId())
          ? Optional.of(account)
          : Optional.empty();
    });
    when(mongo.find(any(), org.mockito.ArgumentMatchers.<Class<Object>>any()))
        .thenReturn(List.of());
    when(browserSessions.authenticate(
        org.mockito.ArgumentMatchers.eq("session-id.secret"),
        org.mockito.ArgumentMatchers.anyBoolean())).thenAnswer(invocation -> {
          Account account = persistedAccount.get();
          return account == null
              ? Optional.empty()
              : Optional.of(new AuthenticatedBrowserSession(
                  account.getId(), account.getRole(), Optional.empty()));
        });
  }

  @AfterAll
  static void removeSandbox() throws IOException {
    deleteRecursively(SANDBOX);
  }

  @Test
  void securityFixtureUsesTheProductionFilterChain() {
    var chains = applicationContext.getBeansOfType(
        org.springframework.security.web.SecurityFilterChain.class);
    org.assertj.core.api.Assertions.assertThat(chains.keySet())
        .containsExactly("securityFilterChain");
    var chain = (org.springframework.security.web.DefaultSecurityFilterChain)
        chains.get("securityFilterChain");
    org.assertj.core.api.Assertions.assertThat(chain.getFilters())
        .anyMatch(dev.christopherbell.configuration.security.JwtAuthenticationFilter.class::isInstance);
  }

  @Test
  void responsesEmitTheBrowserSecurityPolicy() throws Exception {
    mockMvc.perform(get(BASE + "/entries").secure(true))
        .andExpect(status().isForbidden())
        .andExpect(header().string("X-Frame-Options", "SAMEORIGIN"))
        .andExpect(header().string("Content-Security-Policy",
            org.hamcrest.Matchers.containsString("frame-ancestors 'self'")))
        .andExpect(header().string("Content-Security-Policy",
            org.hamcrest.Matchers.containsString("frame-src 'self'")))
        .andExpect(header().string("Referrer-Policy", "strict-origin-when-cross-origin"))
        .andExpect(header().string("Permissions-Policy",
            "camera=(), geolocation=(), microphone=(), payment=(), usb=()"));
  }

  @Test
  void cookieAuthenticatedMutationRequiresCsrf() throws Exception {
    persist(Role.USER, AccountStatus.ACTIVE, Set.of(SHARED_FOLDER_WRITE));
    String token = "session-id.secret";

    mockMvc.perform(post(BASE + "/folders")
            .cookie(new Cookie("CBELL_AUTH", token))
            .contentType("application/json")
            .content("{\"parentPath\":\"\",\"name\":\"blocked\"}"))
        .andExpect(status().isForbidden());

    mockMvc.perform(post(BASE + "/folders")
            .with(csrf())
            .cookie(new Cookie("CBELL_AUTH", token))
            .contentType("application/json")
            .content("{\"parentPath\":\"\",\"name\":\"allowed\"}"))
        .andExpect(status().isCreated());
  }

  @Test
  void anonymousRequestsAreDeniedAtEverySharedFolderBoundary() throws Exception {
    mockMvc.perform(get(BASE + "/entries"))
        .andExpect(status().isForbidden())
        .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "private, no-store"));
    mockMvc.perform(post(BASE + "/folders")
            .contentType("application/json")
            .content("{\"parentPath\":\"\",\"name\":\"docs\"}"))
        .andExpect(status().isForbidden())
        .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "private, no-store"));
    mockMvc.perform(get(BASE + "/admin/audit"))
        .andExpect(status().isForbidden())
        .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "private, no-store"));
  }

  @Test
  void permissionsFormASeparateImmediatelyRevocableCapabilityBoundary() throws Exception {
    persist(Role.USER, AccountStatus.ACTIVE, Set.of(SHARED_FOLDER_READ));
    String token = tokenForCurrentAccount();

    mockMvc.perform(get(BASE + "/entries").header(HttpHeaders.AUTHORIZATION, token))
        .andExpect(status().isOk())
        .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "private, no-store"))
        .andExpect(header().string("X-Content-Type-Options", "nosniff"))
        .andExpect(jsonPath("$.path").value(""));

    persist(Role.USER, AccountStatus.ACTIVE, Set.of());
    mockMvc.perform(get(BASE + "/entries").header(HttpHeaders.AUTHORIZATION, token))
        .andExpect(status().isUnauthorized());

    persist(Role.USER, AccountStatus.ACTIVE, Set.of(SHARED_FOLDER_WRITE));
    token = tokenForCurrentAccount();
    mockMvc.perform(get(BASE + "/entries").header(HttpHeaders.AUTHORIZATION, token))
        .andExpect(status().isOk());
    mockMvc.perform(post(BASE + "/folders")
            .header(HttpHeaders.AUTHORIZATION, token)
            .contentType("application/json")
            .content("{\"parentPath\":\"\",\"name\":\"docs\"}"))
        .andExpect(status().isCreated())
        .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "private, no-store"))
        .andExpect(header().string("X-Content-Type-Options", "nosniff"))
        .andExpect(jsonPath("$.path").value("docs"));
  }

  @Test
  void readOnlyAccountCannotMutateWithoutAWriteGrant() throws Exception {
    persist(Role.USER, AccountStatus.ACTIVE, Set.of(SHARED_FOLDER_READ));
    String token = tokenForCurrentAccount();

    mockMvc.perform(post(BASE + "/folders")
            .header(HttpHeaders.AUTHORIZATION, token)
            .contentType("application/json")
            .content("{\"parentPath\":\"\",\"name\":\"docs\"}"))
        .andExpect(status().isForbidden())
        .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "private, no-store"))
        .andExpect(jsonPath("$.messages[0].code").value("ACCESS_DENIED"));
  }

  @ParameterizedTest
  @EnumSource(value = Role.class, names = {"USER", "MOD"})
  void ordinaryRolesNeedAnExplicitSharedFolderCapability(Role role) throws Exception {
    persist(role, AccountStatus.ACTIVE, Set.of());
    String token = tokenForCurrentAccount();

    mockMvc.perform(get(BASE + "/entries").header(HttpHeaders.AUTHORIZATION, token))
        .andExpect(status().isForbidden());

    persist(role, AccountStatus.ACTIVE, Set.of(SHARED_FOLDER_READ));
    mockMvc.perform(get(BASE + "/entries").header(HttpHeaders.AUTHORIZATION, token))
        .andExpect(status().isUnauthorized());
    mockMvc.perform(get(BASE + "/entries")
            .header(HttpHeaders.AUTHORIZATION, tokenForCurrentAccount()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.entries").isArray());
  }

  @Test
  void accountStatusAloneControlsLifecycleEligibility() throws Exception {
    persist(Role.USER, AccountStatus.INACTIVE, Set.of(SHARED_FOLDER_WRITE));
    String token = tokenForCurrentAccount();
    mockMvc.perform(get(BASE + "/entries").header(HttpHeaders.AUTHORIZATION, token))
        .andExpect(status().isUnauthorized());

    persist(Role.USER, AccountStatus.ACTIVE, Set.of(SHARED_FOLDER_WRITE));
    mockMvc.perform(get(BASE + "/entries")
            .header(HttpHeaders.AUTHORIZATION, tokenForCurrentAccount()))
        .andExpect(status().isOk());
  }

  @Test
  void administratorDefaultsApplyButFreshPersistedRoleStillControlsAdministration() throws Exception {
    persist(Role.ADMIN, AccountStatus.ACTIVE, Set.of());
    String staleAdminToken = tokenForCurrentAccount();

    mockMvc.perform(get(BASE + "/entries").header(HttpHeaders.AUTHORIZATION, staleAdminToken))
        .andExpect(status().isOk());
    mockMvc.perform(post(BASE + "/folders")
            .header(HttpHeaders.AUTHORIZATION, staleAdminToken)
            .contentType("application/json")
            .content("{\"parentPath\":\"\",\"name\":\"admin-docs\"}"))
        .andExpect(status().isCreated());
    mockMvc.perform(get(BASE + "/admin/audit")
            .header(HttpHeaders.AUTHORIZATION, staleAdminToken))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$").isArray());

    persist(Role.USER, AccountStatus.ACTIVE,
        Set.of(SHARED_FOLDER_READ, SHARED_FOLDER_WRITE));
    mockMvc.perform(get(BASE + "/admin/audit")
            .header(HttpHeaders.AUTHORIZATION, staleAdminToken))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void traversalEncodingsAreRejectedWithoutTouchingOutsideTheSharedRoot() throws Exception {
    persist(Role.USER, AccountStatus.ACTIVE, Set.of(SHARED_FOLDER_READ));
    String token = tokenForCurrentAccount();

    for (String path : List.of("../outside.txt", "..\\outside.txt", "C:\\outside.txt")) {
      mockMvc.perform(get(BASE + "/entries")
              .header(HttpHeaders.AUTHORIZATION, token)
              .queryParam("path", path))
          .andExpect(status().isNotFound())
          .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "private, no-store"));
    }

    mockMvc.perform(get(URI.create(
            BASE + "/entries?path=%252e%252e%252foutside.txt"))
            .header(HttpHeaders.AUTHORIZATION, token))
        .andExpect(status().isNotFound());
    mockMvc.perform(get(URI.create(
            BASE + "/entries?path=%2e%2e%2foutside.txt"))
            .header(HttpHeaders.AUTHORIZATION, token))
        .andExpect(status().isNotFound());

    org.assertj.core.api.Assertions.assertThat(Files.readString(OUTSIDE_SENTINEL))
        .isEqualTo("outside");
  }

  private void persist(
      Role role,
      AccountStatus status,
      Set<AccountPermission> permissions) {
    persistedAccount.set(Account.builder()
        .id("account-1")
        .role(role)
        .status(status)
        .permissions(permissions)
        .build());
  }

  private String tokenForCurrentAccount() {
    return "Bearer " + PermissionService.generateToken(persistedAccount.get());
  }

  private static Path temporaryDirectory() {
    try {
      Path sandbox = Files.createTempDirectory("shared-folder-security-");
      Files.createDirectories(sandbox.resolve("shared"));
      Files.createDirectories(sandbox.resolve("system"));
      return sandbox;
    } catch (IOException exception) {
      throw new ExceptionInInitializerError(exception);
    }
  }

  private static void resetDirectory(Path root) throws IOException {
    if (Files.exists(root)) {
      try (var entries = Files.walk(root)) {
        for (Path path : entries.sorted(Comparator.reverseOrder()).toList()) {
          if (!path.equals(root)) Files.deleteIfExists(path);
        }
      }
    }
    Files.createDirectories(root);
  }

  private static void deleteRecursively(Path root) throws IOException {
    if (!Files.exists(root)) return;
    try (var entries = Files.walk(root)) {
      for (Path path : entries.sorted(Comparator.reverseOrder()).toList()) {
        Files.deleteIfExists(path);
      }
    }
  }

  @TestConfiguration(proxyBeanMethods = false)
  static class SharedFolderTestConfiguration {
    @Bean
    SharedFolderBrowserService sharedFolderBrowserService(SharedFolderProperties properties) {
      return new SharedFolderBrowserService(properties);
    }

    @Bean
    SharedFolderMutationService sharedFolderMutationService(
        SharedFolderAccessService access,
        SharedFolderProperties properties) {
      return new SharedFolderMutationService(access, properties);
    }
  }
}
