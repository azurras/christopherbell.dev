package dev.christopherbell.account;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import dev.christopherbell.account.auth.AccountAuthenticationService;
import dev.christopherbell.account.auth.AccountLoginStore;
import dev.christopherbell.account.deletion.AccountDeletionResult;
import dev.christopherbell.account.deletion.AccountDeletionService;
import dev.christopherbell.account.deletion.AccountDeletionStatus;
import dev.christopherbell.account.follow.AccountFollowService;
import dev.christopherbell.account.follow.AccountFollowStore;
import dev.christopherbell.account.moderation.AccountModerationService;
import dev.christopherbell.account.model.Account;
import dev.christopherbell.account.model.AccountLoginRequest;
import dev.christopherbell.account.model.AccountPasswordResetConfirmRequest;
import dev.christopherbell.account.model.AccountPasswordResetRequest;
import dev.christopherbell.account.model.AccountPermission;
import dev.christopherbell.account.model.AccountStatus;
import dev.christopherbell.account.model.Role;
import dev.christopherbell.account.model.dto.AccountDetail;
import dev.christopherbell.account.model.dto.AccountCreateRequest;
import dev.christopherbell.account.model.dto.AccountUpdateRequest;
import dev.christopherbell.account.model.dto.MusicPermissionUpdate;
import dev.christopherbell.account.model.dto.SharedFolderPermissionUpdate;
import dev.christopherbell.account.passwordreset.PasswordResetNotificationService;
import dev.christopherbell.account.passwordreset.PasswordResetService;
import dev.christopherbell.account.profile.AccountProfileService;
import dev.christopherbell.permission.PermissionService;
import dev.christopherbell.admin.activity.AdminActivityService;
import dev.christopherbell.libs.api.exception.InvalidRequestException;
import dev.christopherbell.libs.api.exception.InvalidTokenException;
import dev.christopherbell.libs.api.exception.InternalServiceException;
import dev.christopherbell.libs.api.exception.ResourceExistsException;
import dev.christopherbell.libs.api.exception.ResourceNotFoundException;
import dev.christopherbell.libs.security.PasswordUtil;
import dev.christopherbell.post.PostRepository;
import dev.christopherbell.post.abuse.NewAccountVoidMutationLimiter;
import dev.christopherbell.post.abuse.VoidMutationKind;
import dev.christopherbell.sharedfolder.audit.SharedFolderAuditRecorder;
import dev.christopherbell.sharedfolder.security.SharedFolderAccessService;
import dev.christopherbell.federation.consent.FederationConsentService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Sort;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

@ExtendWith(MockitoExtension.class)
public class AccountServiceTest {
  @Mock private AccountMapper accountMapper;
  @Mock private AccountRepository accountRepository;
  @Mock private AccountLoginStore accountLoginStore;
  @Mock private AccountDeletionService accountDeletionService;
  @Mock private PasswordResetNotificationService passwordResetNotificationService;
  @Mock private PostRepository postRepository;
  @Mock private SharedFolderAuditRecorder sharedFolderAudit;
  @Mock private SharedFolderAccessService sharedFolderAccess;
  @Mock private AdminActivityService adminActivityService;
  @Mock private PermissionService permissionService;
  @Mock private NewAccountVoidMutationLimiter mutationLimiter;
  @Mock private FederationConsentService federationConsent;
  @Mock private AccountFollowStore follows;
  private AccountService accountService;

  @BeforeEach
  void setUp() {
    var authenticationService = new AccountAuthenticationService(accountRepository, accountLoginStore);
    var passwordResetService = new PasswordResetService(accountRepository, passwordResetNotificationService);
    var profileService = new AccountProfileService(accountRepository, accountMapper, postRepository, follows);
    var followService = new AccountFollowService(
        profileService,
        follows,
        mutationLimiter,
        Clock.fixed(Instant.parse("2026-07-29T04:00:00Z"), ZoneOffset.UTC));
    var moderationService = new AccountModerationService(
        accountRepository, accountMapper, adminActivityService, permissionService);
    accountService = new AccountService(
        accountMapper,
        accountRepository,
        accountDeletionService,
        authenticationService,
        passwordResetService,
        profileService,
        followService,
        moderationService,
        sharedFolderAudit,
        sharedFolderAccess,
        federationConsent);
    org.mockito.Mockito.lenient().when(sharedFolderAccess.requireAdmin()).thenReturn(
        Account.builder().id("admin-1").role(Role.ADMIN).build());
    org.mockito.Mockito.lenient().when(permissionService.getSelfId()).thenReturn("admin-1");
  }

  @Test
  @DisplayName("Update: null request -> 400 InvalidRequestException")
  public void testUpdateAccount_whenNullRequest_throwsInvalidRequestException() {
    assertThrows(InvalidRequestException.class, () -> accountService.updateAccount(null));
  }

  @Test
  @DisplayName("GetByEmail: found -> returns mapped detail")
  public void testGetAccountByEmail_whenFound_ReturnsDetail() throws Exception {
    var entity = AccountServiceStub.getAccountWhenExistsStub();
    var detail = AccountDetail.builder().id(entity.getId()).email(entity.getEmail()).build();

    when(accountRepository.findByEmailIgnoreCase(eq("old@example.com")))
        .thenReturn(Optional.of(entity));
    when(accountMapper.toAccount(eq(entity))).thenReturn(detail);

    var result = accountService.getAccountByEmail("Old@Example.com");

    assertEquals(detail, result);
    verify(accountRepository).findByEmailIgnoreCase(eq("old@example.com"));
    verify(accountMapper).toAccount(eq(entity));
    verifyNoMoreInteractions(accountRepository, accountMapper);
  }

  @Test
  @DisplayName("GetByEmail: not found -> throws 404")
  public void testGetAccountByEmail_whenNotFound_Throws404() {
    when(accountRepository.findByEmailIgnoreCase(eq("missing@example.com")))
        .thenReturn(Optional.empty());

    assertThrows(ResourceNotFoundException.class, () -> accountService.getAccountByEmail("Missing@Example.com"));
    verify(accountRepository).findByEmailIgnoreCase(eq("missing@example.com"));
    verifyNoMoreInteractions(accountRepository);
  }

  @Test
  @DisplayName("Create entity: normalizes email casing and sanitizes username")
  public void testCreateAccountEntity_whenEmailMixedCase_normalizesEmailAndSanitizesUsername() {
    var request = AccountCreateRequest.builder()
        .email("Chris@Example.com")
        .firstName("Chris")
        .lastName("Bell")
        .password("pass")
        .username("Chris.Bell  ")
        .build();

    var account = accountService.createAccountEntity(request);

    assertEquals("chris@example.com", account.getEmail());
    assertEquals("Chris.Bell", account.getUsername());
  }

  @Test
  @DisplayName("Create account: federation identity is prepared before the only account write")
  void createAccountPreparesFederationBeforeSave() throws Exception {
    var request = AccountCreateRequest.builder()
        .email("user@example.com")
        .firstName("User")
        .lastName("Example")
        .password("correct-horse-battery-staple")
        .username("user")
        .federatePublicVoidPosts(true)
        .build();

    accountService.createAccount(request);

    var account = ArgumentCaptor.forClass(Account.class);
    var order = org.mockito.Mockito.inOrder(federationConsent, accountRepository);
    order.verify(federationConsent).prepareNewAccount(account.capture(), eq(true));
    order.verify(accountRepository).save(account.getValue());
  }

  @Test
  @DisplayName("Create account: rejected federation enrollment performs no account write")
  void createAccountWhenFederationEnrollmentFailsDoesNotSave() throws Exception {
    var request = AccountCreateRequest.builder()
        .email("user@example.com")
        .firstName("User")
        .lastName("Example")
        .password("correct-horse-battery-staple")
        .username("user")
        .federatePublicVoidPosts(true)
        .build();
    doThrow(new InvalidRequestException("Federation enrollment is unavailable."))
        .when(federationConsent)
        .prepareNewAccount(any(Account.class), eq(true));

    assertThrows(InvalidRequestException.class, () -> accountService.createAccount(request));

    verify(accountRepository, never()).save(any(Account.class));
  }

  @Test
  @DisplayName("Create account: credential provider failure preserves cause in named exception")
  void createAccountWhenPasswordHashingFailsUsesInternalServiceException() throws Exception {
    var request = AccountCreateRequest.builder()
        .email("user@example.com")
        .firstName("User")
        .lastName("Example")
        .password("pass")
        .username("user")
        .build();
    var failure = new java.security.NoSuchAlgorithmException("provider-secret");

    try (MockedStatic<PasswordUtil> passwords = mockStatic(PasswordUtil.class)) {
      passwords.when(() -> PasswordUtil.hashPassword("pass")).thenThrow(failure);

      var exception = assertThrows(
          InternalServiceException.class,
          () -> accountService.createAccount(request));

      assertSame(failure, exception.getCause());
    }
  }

  @Test
  @DisplayName("Login: mixed-case email uses case-insensitive normalized lookup")
  public void testLoginAccount_whenEmailCaseDiffers_authenticates() throws Exception {
    var password = "CorrectHorseBatteryStaple";
    var salt = PasswordUtil.generateSalt();
    var account = Account.builder()
        .id("acc-login")
        .email("User@Example.com")
        .passwordSalt(salt)
        .passwordHash(PasswordUtil.hashPassword(password, salt))
        .role(Role.USER)
        .status(AccountStatus.ACTIVE)
        .build();

    when(accountRepository.findByEmailIgnoreCase(eq("user@example.com")))
        .thenReturn(Optional.of(account));
    when(accountLoginStore.completeLogin(eq(account), anyString(), any(Instant.class)))
        .thenAnswer(invocation -> {
          account.setPasswordHash(invocation.getArgument(1));
          account.setPasswordSalt(null);
          account.setLastLoginOn(invocation.getArgument(2));
          return Optional.of(account);
        });

    var token = accountService.loginAccount(new AccountLoginRequest("USER@example.com", password));

    assertNotNull(token);
    assertNull(account.getPasswordSalt());
    assertTrue(account.getPasswordHash().startsWith("pbkdf2-sha256$210000$"));
    assertTrue(PasswordUtil.verifyPassword(password, null, account.getPasswordHash()));
    verify(accountRepository).findByEmailIgnoreCase(eq("user@example.com"));
    verify(accountLoginStore).completeLogin(eq(account), anyString(), any(Instant.class));
    verifyNoMoreInteractions(accountRepository);
  }

  @Test
  @DisplayName("Login: concurrent demotion is preserved and controls the issued token")
  void testLoginAccount_whenDemotedDuringVerification_usesAtomicCurrentState() throws Exception {
    var password = "CorrectHorseBatteryStaple";
    var hash = PasswordUtil.hashPassword(password);
    var observed = Account.builder()
        .id("acc-login-race")
        .email("user@example.com")
        .passwordHash(hash)
        .role(Role.ADMIN)
        .status(AccountStatus.ACTIVE)
        .build();
    var demoted = Account.builder()
        .id(observed.getId())
        .email(observed.getEmail())
        .passwordHash(hash)
        .role(Role.USER)
        .status(AccountStatus.ACTIVE)
        .build();
    when(accountRepository.findByEmailIgnoreCase("user@example.com"))
        .thenReturn(Optional.of(observed));
    when(accountLoginStore.completeLogin(eq(observed), eq(hash), any(Instant.class)))
        .thenReturn(Optional.of(demoted));

    var token = accountService.loginAccount(new AccountLoginRequest("user@example.com", password));

    assertEquals("USER", PermissionService.validateToken(token).get(Account.PROPERTY_ROLE));
    assertEquals(Role.ADMIN, observed.getRole());
    verify(accountRepository, never()).save(any(Account.class));
  }

  @Test
  @DisplayName("Login: suspended account uses the generic rejection")
  public void testLoginAccount_whenAccountSuspended_throwsGenericInvalidToken() throws Exception {
    var password = "CorrectHorseBatteryStaple";
    var salt = PasswordUtil.generateSalt();
    var account = Account.builder()
        .id("acc-suspended")
        .email("user@example.com")
        .passwordSalt(salt)
        .passwordHash(PasswordUtil.hashPassword(password, salt))
        .role(Role.USER)
        .status(AccountStatus.SUSPENDED)
        .build();

    when(accountRepository.findByEmailIgnoreCase(eq("user@example.com")))
        .thenReturn(Optional.of(account));

    var rejection = assertThrows(
        InvalidTokenException.class,
        () -> accountService.loginAccount(new AccountLoginRequest("USER@example.com", password)));
    assertEquals("Login failed.", rejection.getMessage());

    verify(accountRepository).findByEmailIgnoreCase(eq("user@example.com"));
    verify(accountRepository, never()).save(eq(account));
    verifyNoMoreInteractions(accountRepository);
  }

  @Test
  @DisplayName("Login: unknown email and wrong password are externally indistinguishable")
  void testLoginAccount_whenUnknownOrWrongPassword_usesSameRejection() throws Exception {
    var password = "CorrectHorseBatteryStaple";
    var salt = PasswordUtil.generateSalt();
    var account = Account.builder()
        .id("acc-login")
        .email("user@example.com")
        .passwordSalt(salt)
        .passwordHash(PasswordUtil.hashPassword(password, salt))
        .role(Role.USER)
        .status(AccountStatus.ACTIVE)
        .build();
    when(accountRepository.findByEmailIgnoreCase(eq("missing@example.com")))
        .thenReturn(Optional.empty());
    when(accountRepository.findByEmailIgnoreCase(eq("user@example.com")))
        .thenReturn(Optional.of(account));

    var unknown = assertThrows(InvalidTokenException.class,
        () -> accountService.loginAccount(
            new AccountLoginRequest("missing@example.com", "WrongPassword123")));
    var wrong = assertThrows(InvalidTokenException.class,
        () -> accountService.loginAccount(
            new AccountLoginRequest("user@example.com", "WrongPassword123")));

    assertEquals("Login failed.", unknown.getMessage());
    assertEquals(unknown.getMessage(), wrong.getMessage());
    verify(accountRepository, never()).save(any(Account.class));
  }

  @Test
  @DisplayName("Follow account: current user follows target")
  public void testFollowAccount_whenValid_addsTargetToFollowingSet() throws Exception {
    var self = Account.builder()
        .id("self")
        .username("self")
        .role(Role.USER)
        .build();
    var target = Account.builder()
        .id("target")
        .username("target")
        .role(Role.USER)
        .build();
    var token = dev.christopherbell.permission.PermissionService.generateToken(self);
    SecurityContextHolder.getContext()
        .setAuthentication(new UsernamePasswordAuthenticationToken("self", token, java.util.List.of()));

    try {
      when(accountRepository.findById(eq("self"))).thenReturn(Optional.of(self));
      when(accountRepository.findByUsernameAndStatus(eq("target"), eq(AccountStatus.ACTIVE)))
          .thenReturn(Optional.of(target));
      when(follows.follow(eq("self"), eq("target"), any(Instant.class)))
          .thenReturn(new AccountFollowStore.FollowTransition(true, false));
      when(follows.countFollowers(eq("target"))).thenReturn(1L);
      when(follows.exists(eq("self"), eq("target"))).thenReturn(true);

      var profile = accountService.followAccount("target");

      assertEquals("target", profile.username());
      assertEquals(1L, profile.followerCount());
      org.junit.jupiter.api.Assertions.assertTrue(profile.followedByMe());
      verify(accountRepository).findById(eq("self"));
      verify(accountRepository).findByUsernameAndStatus(eq("target"), eq(AccountStatus.ACTIVE));
      verify(mutationLimiter).require(eq(self), eq(VoidMutationKind.FOLLOW));
      verify(follows).follow(eq("self"), eq("target"), any(Instant.class));
      verify(follows).countFollowers(eq("target"));
      verify(postRepository).countByAccountIdAndParentIdIsNull(eq("target"));
      verify(postRepository).countByAccountIdAndParentIdIsNotNull(eq("target"));
      verifyNoMoreInteractions(accountRepository);
    } finally {
      SecurityContextHolder.clearContext();
    }
  }

  @Test
  @DisplayName("Public profile includes safe activity stats")
  public void testGetPublicProfile_includesSafeActivityStats() throws Exception {
    var account = Account.builder()
        .id("target")
        .username("target")
        .role(Role.USER)
        .build();

    when(accountRepository.findByUsernameAndStatus(eq("target"), eq(AccountStatus.ACTIVE)))
        .thenReturn(Optional.of(account));
    when(follows.countFollowers(eq("target"))).thenReturn(2L);
    when(postRepository.countByAccountIdAndParentIdIsNull(eq("target"))).thenReturn(3L);
    when(postRepository.countByAccountIdAndParentIdIsNotNull(eq("target"))).thenReturn(5L);

    var profile = accountService.getPublicProfile("target");

    assertEquals(3, profile.postCount());
    assertEquals(5, profile.replyCount());
    assertEquals(2, profile.followerCount());
  }

  @Test
  @DisplayName("Public profile excludes accounts outside the active lifecycle")
  public void testGetPublicProfile_whenAccountIsNotActive_returnsNotFound() {
    when(accountRepository.findByUsernameAndStatus(eq("retired"), eq(AccountStatus.ACTIVE)))
        .thenReturn(Optional.empty());

    assertThrows(ResourceNotFoundException.class,
        () -> accountService.getPublicProfile("retired"));
    verify(accountRepository).findByUsernameAndStatus(eq("retired"), eq(AccountStatus.ACTIVE));
  }

  @Test
  @DisplayName("Username suggestions: searches active accounts by prefix and excludes self")
  public void testSearchUsernameSuggestions_whenMatches_returnsActiveNonSelfUsernames() throws Exception {
    var self = Account.builder()
        .id("self")
        .username("alex")
        .role(Role.USER)
        .status(AccountStatus.ACTIVE)
        .build();
    var alice = Account.builder()
        .id("alice-id")
        .username("alice")
        .role(Role.USER)
        .status(AccountStatus.ACTIVE)
        .build();
    var alina = Account.builder()
        .id("alina-id")
        .username("alina")
        .role(Role.USER)
        .status(AccountStatus.ACTIVE)
        .build();
    var token = dev.christopherbell.permission.PermissionService.generateToken(self);
    SecurityContextHolder.getContext()
        .setAuthentication(new UsernamePasswordAuthenticationToken("self", token, java.util.List.of()));

    try {
      when(accountRepository.findById(eq("self"))).thenReturn(Optional.of(self));
      when(accountRepository.findByUsernameStartingWithIgnoreCaseAndStatusOrderByUsernameAsc(
          eq("ali"),
          eq(AccountStatus.ACTIVE),
          eq(PageRequest.of(0, 5))))
          .thenReturn(List.of(alice, alina, self));

      var suggestions = accountService.searchUsernameSuggestions(" ali ", 5);

      assertEquals(List.of("alice", "alina"), suggestions.stream().map(s -> s.username()).toList());
      verify(accountRepository).findById(eq("self"));
      verify(accountRepository).findByUsernameStartingWithIgnoreCaseAndStatusOrderByUsernameAsc(
          eq("ali"),
          eq(AccountStatus.ACTIVE),
          eq(PageRequest.of(0, 5)));
      verifyNoMoreInteractions(accountRepository);
    } finally {
      SecurityContextHolder.clearContext();
    }
  }

  @Test
  @DisplayName("Username suggestions: blank prefix returns empty list without repository search")
  public void testSearchUsernameSuggestions_whenBlank_returnsEmptyList() throws Exception {
    var suggestions = accountService.searchUsernameSuggestions(" ", 5);

    assertEquals(List.of(), suggestions);
    verifyNoMoreInteractions(accountRepository);
  }

  @Test
  @DisplayName("Password reset request: existing email stores token and sends link")
  public void testRequestPasswordReset_whenAccountExists_storesTokenAndSendsLink() {
    var account = AccountServiceStub.getAccountWhenExistsStub();
    when(accountRepository.findByEmailIgnoreCase(eq("old@example.com")))
        .thenReturn(Optional.of(account));
    when(accountRepository.save(eq(account))).thenReturn(account);

    accountService.requestPasswordReset(
        new AccountPasswordResetRequest("Old@Example.com"),
        "https://example.com");

    assertNotNull(account.getPasswordResetTokenHash());
    assertNotNull(account.getPasswordResetTokenExpiresOn());
    var resetUrl = ArgumentCaptor.forClass(String.class);
    verify(accountRepository).findByEmailIgnoreCase(eq("old@example.com"));
    verify(accountRepository).save(eq(account));
    verify(passwordResetNotificationService).sendPasswordReset(eq(account), resetUrl.capture());
    org.junit.jupiter.api.Assertions.assertTrue(
        resetUrl.getValue().startsWith("https://example.com/reset-password?token="));
  }

  @Test
  @DisplayName("Password reset request: unknown email returns generically without sending")
  public void testRequestPasswordReset_whenAccountMissing_doesNotSend() {
    when(accountRepository.findByEmailIgnoreCase(eq("missing@example.com")))
        .thenReturn(Optional.empty());

    accountService.requestPasswordReset(
        new AccountPasswordResetRequest("Missing@Example.com"),
        "https://example.com");

    verify(accountRepository).findByEmailIgnoreCase(eq("missing@example.com"));
    verifyNoMoreInteractions(accountRepository, passwordResetNotificationService);
  }

  @Test
  @DisplayName("Password reset confirm: valid token updates password and clears token")
  public void testResetPassword_whenTokenValid_updatesPasswordAndClearsToken() throws Exception {
    var token = "valid-reset-token";
    var tokenHash = hashResetToken(token);
    var account = AccountServiceStub.getAccountWhenExistsStub();
    account.setPasswordResetTokenHash(tokenHash);
    account.setPasswordResetTokenExpiresOn(Instant.now().plusSeconds(3600));

    when(accountRepository.findByPasswordResetTokenHash(eq(tokenHash)))
        .thenReturn(Optional.of(account));
    when(accountRepository.save(eq(account))).thenReturn(account);

    accountService.resetPassword(new AccountPasswordResetConfirmRequest(token, "new-password"));

    assertNull(account.getPasswordResetTokenHash());
    assertNull(account.getPasswordResetTokenExpiresOn());
    org.junit.jupiter.api.Assertions.assertTrue(
        PasswordUtil.verifyPassword("new-password", account.getPasswordSalt(), account.getPasswordHash()));
    verify(accountRepository).findByPasswordResetTokenHash(eq(tokenHash));
    verify(accountRepository).save(eq(account));
    verifyNoMoreInteractions(accountRepository);
  }

  @Test
  @DisplayName("Password reset confirm: invalid token throws InvalidTokenException")
  public void testResetPassword_whenTokenInvalid_throwsInvalidTokenException() {
    when(accountRepository.findByPasswordResetTokenHash(anyString()))
        .thenReturn(Optional.empty());

    assertThrows(
        InvalidTokenException.class,
        () -> accountService.resetPassword(new AccountPasswordResetConfirmRequest("bad-token", "new-password")));
  }

  @Test
  @DisplayName("GetByUsername: found -> returns mapped detail")
  public void testGetAccountByUsername_whenFound_ReturnsDetail() throws Exception {
    var entity = AccountServiceStub.getAccountWhenExistsStub();
    var detail = AccountDetail.builder().id(entity.getId()).username(entity.getUsername()).build();

    when(accountRepository.findByUsername(eq("old_user")))
        .thenReturn(Optional.of(entity));
    when(accountMapper.toAccount(eq(entity))).thenReturn(detail);

    var result = accountService.getAccountByUsername("old_user");

    assertEquals(detail, result);
    verify(accountRepository).findByUsername(eq("old_user"));
    verify(accountMapper).toAccount(eq(entity));
    verifyNoMoreInteractions(accountRepository, accountMapper);
  }

  @Test
  @DisplayName("GetByUsername: not found -> throws 404")
  public void testGetAccountByUsername_whenNotFound_Throws404() {
    when(accountRepository.findByUsername(eq("missing_user")))
        .thenReturn(Optional.empty());

    assertThrows(ResourceNotFoundException.class, () -> accountService.getAccountByUsername("missing_user"));
    verify(accountRepository).findByUsername(eq("missing_user"));
    verifyNoMoreInteractions(accountRepository);
  }

  @Test
  @DisplayName("GetById: found -> returns mapped detail")
  public void testGetAccountById_whenFound_ReturnsDetail() throws Exception {
    var entity = AccountServiceStub.getAccountWhenExistsStub();
    var detail = AccountDetail.builder().id(entity.getId()).build();

    when(accountRepository.findById(eq(AccountServiceStub.ID)))
        .thenReturn(Optional.of(entity));
    when(accountMapper.toAccount(eq(entity))).thenReturn(detail);

    var result = accountService.getAccountById(AccountServiceStub.ID);

    assertEquals(detail, result);
    verify(accountRepository).findById(eq(AccountServiceStub.ID));
    verify(accountMapper).toAccount(eq(entity));
    verifyNoMoreInteractions(accountRepository, accountMapper);
  }

  @Test
  @DisplayName("Delete delegates to the durable privacy deletion service")
  public void testDeleteAccount_whenFound_ReturnsDeletionResult() throws Exception {
    var deletion = new AccountDeletionResult(
        "deleted:abcdef012345", AccountDeletionStatus.COMPLETE, 6);
    when(accountDeletionService.delete(AccountServiceStub.ID)).thenReturn(deletion);

    var result = accountService.deleteAccount(AccountServiceStub.ID);

    assertEquals(deletion, result);
    verify(accountDeletionService).delete(AccountServiceStub.ID);
  }

  @Test
  @DisplayName("Delete: not found -> throws 404")
  public void testDeleteAccount_whenNotFound_Throws404() throws Exception {
    when(accountDeletionService.delete(AccountServiceStub.ID))
        .thenThrow(new ResourceNotFoundException("Account was not found."));

    assertThrows(ResourceNotFoundException.class, () -> accountService.deleteAccount(AccountServiceStub.ID));
    verify(accountDeletionService).delete(AccountServiceStub.ID);
  }

  @Test
  @DisplayName("Update: blank id -> 400 InvalidRequestException")
  public void testUpdateAccount_whenBlankId_throwsInvalidRequestException() {
    var request = AccountServiceStub.getAccountUpdateRequestWhenBlankIdStub();
    assertThrows(InvalidRequestException.class, () -> accountService.updateAccount(request));
  }

  @Test
  @DisplayName("Update: not found -> 404 ResourceNotFoundException")
  public void testUpdateAccount_whenNotFound_throwsResourceNotFoundException() {
    var request = AccountUpdateRequest.builder().id(AccountServiceStub.ID).build();

    when(accountRepository.findById(eq(AccountServiceStub.ID)))
        .thenReturn(Optional.empty());

    assertThrows(ResourceNotFoundException.class, () -> accountService.updateAccount(request));

    verify(accountRepository).findById(eq(AccountServiceStub.ID));
    verifyNoMoreInteractions(accountRepository);
  }

  @Test
  @DisplayName("Update: all fields set -> applies changes, sanitizes, returns detail")
  public void testUpdateAccount_whenValid_appliesChangesAndReturnsDetail() throws Exception {
    var existing = AccountServiceStub.getAccountWhenExistsStub();
    var request = AccountServiceStub.getAccountUpdateRequestWhenAllFieldsSetStub();

    when(accountRepository.findById(eq(AccountServiceStub.ID)))
        .thenReturn(Optional.of(existing));
    // No conflicts for new email/username
    when(accountRepository.findByEmailIgnoreCase(eq("chris@example.com")))
        .thenReturn(Optional.empty());
    when(accountRepository.findByUsernameIgnoreCase(eq("Chris.Bell")))
        .thenReturn(Optional.empty());
    when(accountRepository.save(eq(existing))).thenReturn(existing);

    var detail = AccountDetail.builder()
        .id(AccountServiceStub.ID)
        .email("chris@example.com")
        .firstName("Chris")
        .lastName("Bell")
        .username("Chris.Bell")
        .role(Role.ADMIN)
        .status(AccountStatus.ACTIVE)
        .build();
    when(accountMapper.toAccount(eq(existing))).thenReturn(detail);

    AccountDetail result = accountService.updateAccount(request);

    assertNotNull(result);
    assertEquals(AccountServiceStub.ID, result.getId());
    assertEquals("chris@example.com", result.getEmail());
    assertEquals("Chris", result.getFirstName());
    assertEquals("Bell", result.getLastName());
    assertEquals("Chris.Bell", result.getUsername());
    assertEquals(Role.ADMIN, result.getRole());
    assertEquals(AccountStatus.ACTIVE, result.getStatus());

    verify(accountRepository).findById(eq(AccountServiceStub.ID));
    verify(accountRepository).findById("admin-1");
    verify(accountRepository).findByEmailIgnoreCase(eq("chris@example.com"));
    verify(accountRepository).findByUsernameIgnoreCase(eq("Chris.Bell"));
    verify(accountRepository, org.mockito.Mockito.times(2)).save(eq(existing));
    verify(accountMapper).toAccount(eq(existing));
    verifyNoMoreInteractions(accountRepository);
  }

  @Test
  @DisplayName("Update: role only -> updates role, keeps others unchanged")
  public void testUpdateAccount_whenRoleOnly_updatesRoleAndKeepsOthers() throws Exception {
    var existing = AccountServiceStub.getAccountWhenExistsStub();
    var request = AccountServiceStub.getAccountUpdateRequestWhenRoleOnlyStub();

    when(accountRepository.findById(eq(AccountServiceStub.ID)))
        .thenReturn(Optional.of(existing));
    when(accountRepository.save(eq(existing))).thenReturn(existing);

    var detail = AccountDetail.builder()
        .id(AccountServiceStub.ID)
        .email(existing.getEmail())
        .firstName(existing.getFirstName())
        .lastName(existing.getLastName())
        .username(existing.getUsername())
        .role(Role.ADMIN)
        .status(existing.getStatus())
        .build();
    when(accountMapper.toAccount(eq(existing))).thenReturn(detail);

    AccountDetail result = accountService.updateAccount(request);

    assertEquals(Role.ADMIN, result.getRole());
    assertEquals(existing.getEmail(), result.getEmail());
    assertEquals(existing.getUsername(), result.getUsername());
    assertEquals(existing.getFirstName(), result.getFirstName());
    assertEquals(existing.getLastName(), result.getLastName());
    assertEquals(existing.getStatus(), result.getStatus());

    verify(accountRepository).findById(eq(AccountServiceStub.ID));
    verify(accountRepository).findById("admin-1");
    verify(accountRepository, org.mockito.Mockito.times(2)).save(eq(existing));
    verify(accountMapper).toAccount(eq(existing));
    verifyNoMoreInteractions(accountRepository);
  }

  @Test
  @DisplayName("Update: status only -> updates the account lifecycle")
  public void testUpdateAccount_whenStatusOnly_updatesStatus() throws Exception {
    var existing = AccountServiceStub.getAccountWhenExistsStub();
    var request = AccountServiceStub.getAccountUpdateRequestWhenStatusOnlyStub();

    when(accountRepository.findById(eq(AccountServiceStub.ID)))
        .thenReturn(Optional.of(existing));
    when(accountRepository.save(eq(existing))).thenReturn(existing);

    var detail = AccountDetail.builder()
        .id(AccountServiceStub.ID)
        .email(existing.getEmail())
        .firstName(existing.getFirstName())
        .lastName(existing.getLastName())
        .username(existing.getUsername())
        .role(existing.getRole())
        .status(AccountStatus.ACTIVE)
        .build();
    when(accountMapper.toAccount(eq(existing))).thenReturn(detail);

    AccountDetail result = accountService.updateAccount(request);

    assertEquals(AccountStatus.ACTIVE, result.getStatus());

    verify(accountRepository).findById(eq(AccountServiceStub.ID));
    verify(accountRepository).findById("admin-1");
    verify(accountRepository, org.mockito.Mockito.times(2)).save(eq(existing));
    verify(accountMapper).toAccount(eq(existing));
    verifyNoMoreInteractions(accountRepository);
  }

  @Test
  @DisplayName("Update: only id -> keeps all values unchanged")
  public void testUpdateAccount_whenOnlyId_keepsExistingValues() throws Exception {
    var existing = AccountServiceStub.getAccountWhenExistsStub();
    var request = AccountServiceStub.getAccountUpdateRequestWhenOnlyIdStub();

    when(accountRepository.findById(eq(AccountServiceStub.ID)))
        .thenReturn(Optional.of(existing));
    when(accountRepository.save(eq(existing))).thenReturn(existing);

    var detail = AccountDetail.builder()
        .id(AccountServiceStub.ID)
        .email(existing.getEmail())
        .firstName(existing.getFirstName())
        .lastName(existing.getLastName())
        .username(existing.getUsername())
        .role(existing.getRole())
        .status(existing.getStatus())
        .build();
    when(accountMapper.toAccount(eq(existing))).thenReturn(detail);

    AccountDetail result = accountService.updateAccount(request);

    assertEquals(existing.getEmail(), result.getEmail());
    assertEquals(existing.getUsername(), result.getUsername());
    assertEquals(existing.getFirstName(), result.getFirstName());
    assertEquals(existing.getLastName(), result.getLastName());
    assertEquals(existing.getRole(), result.getRole());
    assertEquals(existing.getStatus(), result.getStatus());

    verify(accountRepository).findById(eq(AccountServiceStub.ID));
    verify(accountRepository).save(eq(existing));
    verify(accountMapper).toAccount(eq(existing));
    verifyNoMoreInteractions(accountRepository);
  }

  @Test
  @DisplayName("Update: invalid email -> throws IllegalArgumentException")
  public void testUpdateAccount_whenInvalidEmail_throwsIllegalArgumentException() {
    var existing = AccountServiceStub.getAccountWhenExistsStub();
    var request = AccountServiceStub.getAccountUpdateRequestWhenInvalidEmailStub();

    when(accountRepository.findById(eq(AccountServiceStub.ID)))
        .thenReturn(Optional.of(existing));

    assertThrows(IllegalArgumentException.class, () -> accountService.updateAccount(request));

    verify(accountRepository).findById(eq(AccountServiceStub.ID));
    verifyNoMoreInteractions(accountRepository);
  }

  @Test
  @DisplayName("Update: invalid username -> throws IllegalArgumentException")
  public void testUpdateAccount_whenInvalidUsername_throwsIllegalArgumentException() {
    var existing = AccountServiceStub.getAccountWhenExistsStub();
    var request = AccountServiceStub.getAccountUpdateRequestWhenInvalidUsernameStub();

    when(accountRepository.findById(eq(AccountServiceStub.ID)))
        .thenReturn(Optional.of(existing));

    assertThrows(IllegalArgumentException.class, () -> accountService.updateAccount(request));

    verify(accountRepository).findById(eq(AccountServiceStub.ID));
    verifyNoMoreInteractions(accountRepository);
  }

  @Test
  @DisplayName("Update: email exists -> throws ResourceExistsException and does not save")
  public void testUpdateAccount_whenEmailExists_throwsResourceExistsException() {
    var existing = AccountServiceStub.getAccountWhenExistsStub();
    var request = AccountServiceStub.getAccountUpdateRequestWhenAllFieldsSetStub();

    // Another account already owns the sanitized target email
    var other = Account.builder()
        .id("acc-999")
        .email("chris@example.com")
        .username("someoneElse")
        .build();

    when(accountRepository.findById(eq(AccountServiceStub.ID)))
        .thenReturn(Optional.of(existing));
    when(accountRepository.findByEmailIgnoreCase(eq("chris@example.com")))
        .thenReturn(Optional.of(other));

    assertThrows(ResourceExistsException.class, () -> accountService.updateAccount(request));

    verify(accountRepository).findById(eq(AccountServiceStub.ID));
    verify(accountRepository).findById("admin-1");
    verify(accountRepository).findByEmailIgnoreCase(eq("chris@example.com"));
    verifyNoMoreInteractions(accountRepository);
  }

  @Test
  @DisplayName("Update: username exists -> throws ResourceExistsException and does not save")
  public void testUpdateAccount_whenUsernameExists_throwsResourceExistsException() {
    var existing = AccountServiceStub.getAccountWhenExistsStub();
    var request = AccountServiceStub.getAccountUpdateRequestWhenAllFieldsSetStub();

    // Email is available, but username is taken by another account
    var other = Account.builder()
        .id("acc-888")
        .email("someone@example.com")
        .username("Chris.Bell")
        .build();

    when(accountRepository.findById(eq(AccountServiceStub.ID)))
        .thenReturn(Optional.of(existing));
    when(accountRepository.findByEmailIgnoreCase(eq("chris@example.com")))
        .thenReturn(Optional.empty());
    when(accountRepository.findByUsernameIgnoreCase(eq("Chris.Bell")))
        .thenReturn(Optional.of(other));

    assertThrows(ResourceExistsException.class, () -> accountService.updateAccount(request));

    verify(accountRepository).findById(eq(AccountServiceStub.ID));
    verify(accountRepository).findById("admin-1");
    verify(accountRepository).findByEmailIgnoreCase(eq("chris@example.com"));
    verify(accountRepository).findByUsernameIgnoreCase(eq("Chris.Bell"));
    verifyNoMoreInteractions(accountRepository);
  }

  @Test
  @DisplayName("Shared folder permissions: write requires read and changes persist independently of role")
  public void sharedFolderStateCannotBecomeWriteOnly() throws Exception {
    var account = Account.builder().id("account-permissions").role(Role.USER).build();
    when(accountRepository.findById(eq(account.getId()))).thenReturn(Optional.of(account));
    when(accountRepository.save(eq(account))).thenReturn(account);
    when(accountMapper.toAccount(eq(account))).thenReturn(AccountDetail.builder().id(account.getId()).build());

    accountService.updateSharedFolderPermissions(
        account.getId(), new SharedFolderPermissionUpdate(true, true));
    assertEquals(
        java.util.Set.of(AccountPermission.SHARED_FOLDER_READ, AccountPermission.SHARED_FOLDER_WRITE),
        account.getPermissions());

    accountService.updateSharedFolderPermissions(
        account.getId(), new SharedFolderPermissionUpdate(false, false));
    assertEquals(java.util.Set.of(), account.getPermissions());

    assertThrows(
        InvalidRequestException.class,
        () -> accountService.updateSharedFolderPermissions(
            account.getId(), new SharedFolderPermissionUpdate(false, true)));
    verify(sharedFolderAudit, org.mockito.Mockito.times(2)).recordCurrent(
        "PERMISSION_CHANGE", account.getId(), null, "accepted", null);
    verify(sharedFolderAudit).recordRejectedOnce(
        "PERMISSION_CHANGE", account.getId(), "invalid_request");
  }

  @Test
  @DisplayName("Shared folder permissions: updating them preserves Music capabilities")
  void sharedFolderPermissionUpdatePreservesMusicCapabilities() throws Exception {
    var account = Account.builder()
        .id("account-permission-families")
        .role(Role.USER)
        .permissions(java.util.Set.of(
            AccountPermission.MUSIC_READ,
            AccountPermission.MUSIC_WRITE))
        .build();
    when(accountRepository.findById(account.getId())).thenReturn(Optional.of(account));
    when(accountRepository.save(account)).thenReturn(account);
    when(accountMapper.toAccount(account)).thenReturn(
        AccountDetail.builder().id(account.getId()).permissions(account.getPermissions()).build());

    accountService.updateSharedFolderPermissions(
        account.getId(), new SharedFolderPermissionUpdate(true, false));

    assertEquals(
        java.util.Set.of(
            AccountPermission.MUSIC_READ,
            AccountPermission.MUSIC_WRITE,
            AccountPermission.SHARED_FOLDER_READ),
        account.getPermissions());
  }

  @Test
  @DisplayName("Music permissions: write implies read and Shared Folder capabilities are preserved")
  void musicPermissionUpdatePreservesSharedFolderCapabilities() throws Exception {
    var account = Account.builder()
        .id("account-music-permissions")
        .role(Role.USER)
        .permissions(java.util.Set.of(
            AccountPermission.SHARED_FOLDER_READ,
            AccountPermission.SHARED_FOLDER_WRITE))
        .build();
    when(accountRepository.findById(account.getId())).thenReturn(Optional.of(account));
    when(accountRepository.save(account)).thenReturn(account);
    when(accountMapper.toAccount(account)).thenReturn(
        AccountDetail.builder().id(account.getId()).permissions(account.getPermissions()).build());

    accountService.updateMusicPermissions(
        account.getId(), new MusicPermissionUpdate(true, true));

    assertEquals(
        java.util.Set.of(
            AccountPermission.SHARED_FOLDER_READ,
            AccountPermission.SHARED_FOLDER_WRITE,
            AccountPermission.MUSIC_READ,
            AccountPermission.MUSIC_WRITE),
        account.getPermissions());
    assertThrows(
        InvalidRequestException.class,
        () -> accountService.updateMusicPermissions(
            account.getId(), new MusicPermissionUpdate(false, true)));
    verify(sharedFolderAudit).recordCurrent(
        "MUSIC_PERMISSION_CHANGE", account.getId(), null, "accepted", null);
    verify(sharedFolderAudit).recordRejectedOnce(
        "MUSIC_PERMISSION_CHANGE", account.getId(), "invalid_request");
  }

  @Test
  @DisplayName("Shared folder permissions: fresh persisted admin state is required")
  void sharedFolderPermissionsRequireFreshAdminState() {
    org.mockito.Mockito.doThrow(new org.springframework.security.access.AccessDeniedException(
        "demoted")).when(sharedFolderAccess).requireAdmin();

    assertThrows(
        org.springframework.security.access.AccessDeniedException.class,
        () -> accountService.updateSharedFolderPermissions(
            "account-permissions", new SharedFolderPermissionUpdate(true, false)));

    verify(sharedFolderAudit).recordFailureOnce(
        eq("PERMISSION_CHANGE"), eq("account-permissions"), any(
            org.springframework.security.access.AccessDeniedException.class));
    verifyNoMoreInteractions(accountRepository);
  }

  @Test
  @DisplayName("Shared folder permissions: not-found and persistence failures are rejected audit events")
  void sharedFolderPermissionFailuresAreAudited() throws Exception {
    when(accountRepository.findById("missing-account")).thenReturn(Optional.empty());

    assertThrows(ResourceNotFoundException.class, () -> accountService.updateSharedFolderPermissions(
        "missing-account", new SharedFolderPermissionUpdate(true, false)));
    verify(sharedFolderAudit).recordRejectedOnce(
        "PERMISSION_CHANGE", "missing-account", "not_found");

    var account = Account.builder().id("save-failure").role(Role.USER).build();
    when(accountRepository.findById(account.getId())).thenReturn(Optional.of(account));
    var persistenceFailure = new org.springframework.dao.DataAccessResourceFailureException("mongo");
    when(accountRepository.save(account)).thenThrow(persistenceFailure);

    assertThrows(org.springframework.dao.DataAccessResourceFailureException.class,
        () -> accountService.updateSharedFolderPermissions(
            account.getId(), new SharedFolderPermissionUpdate(true, false)));
    verify(sharedFolderAudit).recordFailureOnce(
        "PERMISSION_CHANGE", account.getId(), persistenceFailure);
  }

  @Test
  @DisplayName("Shared folder permissions: malformed target ids use a bounded audit resource")
  void malformedSharedFolderPermissionTargetIsSafelyAudited() {
    String unsafeId = "bad:id/with\\separators";

    assertThrows(InvalidRequestException.class,
        () -> accountService.updateSharedFolderPermissions(
            unsafeId, new SharedFolderPermissionUpdate(false, true)));

    verify(sharedFolderAudit).recordRejectedOnce(
        "PERMISSION_CHANGE", "invalid-account", "invalid_request");
  }

  @Test
  @DisplayName("Legacy admin account list is bounded to a stable first page")
  void getAccounts_usesBoundedCompatibilityPage() {
    var account = Account.builder().id("account-1").build();
    var detail = AccountDetail.builder().id("account-1").build();
    var pageRequest = PageRequest.of(
        0,
        50,
        Sort.by(Sort.Direction.DESC, "createdOn")
            .and(Sort.by(Sort.Direction.DESC, "id")));
    when(accountRepository.findAll(pageRequest)).thenReturn(new PageImpl<>(List.of(account)));
    when(accountMapper.toAccount(account)).thenReturn(detail);

    assertEquals(List.of(detail), accountService.getAccounts());

    verify(accountRepository).findAll(pageRequest);
  }

  private String hashResetToken(String token) throws Exception {
    var digest = MessageDigest.getInstance("SHA-256");
    var hash = digest.digest(token.getBytes(StandardCharsets.UTF_8));
    return Base64.getEncoder().encodeToString(hash);
  }
}
