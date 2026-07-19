package dev.christopherbell.account;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import dev.christopherbell.account.auth.AccountAuthenticationService;
import dev.christopherbell.account.follow.AccountFollowService;
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
import dev.christopherbell.account.model.dto.SharedFolderPermissionUpdate;
import dev.christopherbell.account.passwordreset.PasswordResetNotificationService;
import dev.christopherbell.account.passwordreset.PasswordResetService;
import dev.christopherbell.account.profile.AccountProfileService;
import dev.christopherbell.libs.api.exception.InvalidRequestException;
import dev.christopherbell.libs.api.exception.InvalidTokenException;
import dev.christopherbell.libs.api.exception.ResourceExistsException;
import dev.christopherbell.libs.api.exception.ResourceNotFoundException;
import dev.christopherbell.libs.security.PasswordUtil;
import dev.christopherbell.post.PostRepository;
import dev.christopherbell.sharedfolder.audit.SharedFolderAuditRecorder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

@ExtendWith(MockitoExtension.class)
public class AccountServiceTest {
  @Mock private AccountMapper accountMapper;
  @Mock private AccountRepository accountRepository;
  @Mock private PasswordResetNotificationService passwordResetNotificationService;
  @Mock private PostRepository postRepository;
  @Mock private SharedFolderAuditRecorder sharedFolderAudit;
  private AccountService accountService;

  @BeforeEach
  void setUp() {
    var authenticationService = new AccountAuthenticationService(accountRepository);
    var passwordResetService = new PasswordResetService(accountRepository, passwordResetNotificationService);
    var profileService = new AccountProfileService(accountRepository, accountMapper, postRepository);
    var followService = new AccountFollowService(accountRepository, profileService);
    var moderationService = new AccountModerationService(accountRepository, accountMapper);
    accountService = new AccountService(
        accountMapper,
        accountRepository,
        authenticationService,
        passwordResetService,
        profileService,
        followService,
        moderationService,
        sharedFolderAudit);
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
    when(accountRepository.save(eq(account))).thenReturn(account);

    var token = accountService.loginAccount(new AccountLoginRequest("USER@example.com", password));

    assertNotNull(token);
    verify(accountRepository).findByEmailIgnoreCase(eq("user@example.com"));
    verify(accountRepository).save(eq(account));
    verifyNoMoreInteractions(accountRepository);
  }

  @Test
  @DisplayName("Login: suspended account -> AccountNotActiveException")
  public void testLoginAccount_whenAccountSuspended_throwsAccountNotActiveException() throws Exception {
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

    assertThrows(
        AccountNotActiveException.class,
        () -> accountService.loginAccount(new AccountLoginRequest("USER@example.com", password)));

    verify(accountRepository).findByEmailIgnoreCase(eq("user@example.com"));
    verify(accountRepository, never()).save(eq(account));
    verifyNoMoreInteractions(accountRepository);
  }

  @Test
  @DisplayName("Follow account: current user follows target")
  public void testFollowAccount_whenValid_addsTargetToFollowingSet() throws Exception {
    var self = Account.builder()
        .id("self")
        .username("self")
        .role(Role.USER)
        .followingIds(new java.util.HashSet<>())
        .build();
    var target = Account.builder()
        .id("target")
        .username("target")
        .role(Role.USER)
        .followingIds(new java.util.HashSet<>())
        .build();
    var token = dev.christopherbell.permission.PermissionService.generateToken(self);
    SecurityContextHolder.getContext()
        .setAuthentication(new UsernamePasswordAuthenticationToken("self", token));

    try {
      when(accountRepository.findById(eq("self"))).thenReturn(Optional.of(self));
      when(accountRepository.findByUsername(eq("target"))).thenReturn(Optional.of(target));
      when(accountRepository.countByFollowingIdsContaining(eq("target"))).thenReturn(1L);
      when(accountRepository.save(eq(self))).thenReturn(self);

      var profile = accountService.followAccount("target");

      org.junit.jupiter.api.Assertions.assertTrue(self.getFollowingIds().contains("target"));
      assertEquals("target", profile.username());
      assertEquals(1L, profile.followerCount());
      org.junit.jupiter.api.Assertions.assertTrue(profile.followedByMe());
      verify(accountRepository).findById(eq("self"));
      verify(accountRepository).findByUsername(eq("target"));
      verify(accountRepository).save(eq(self));
      verify(accountRepository).countByFollowingIdsContaining(eq("target"));
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
        .followingIds(new java.util.HashSet<>())
        .build();

    when(accountRepository.findByUsername(eq("target"))).thenReturn(Optional.of(account));
    when(accountRepository.countByFollowingIdsContaining(eq("target"))).thenReturn(2L);
    when(postRepository.countByAccountIdAndParentIdIsNull(eq("target"))).thenReturn(3L);
    when(postRepository.countByAccountIdAndParentIdIsNotNull(eq("target"))).thenReturn(5L);

    var profile = accountService.getPublicProfile("target");

    assertEquals(3, profile.postCount());
    assertEquals(5, profile.replyCount());
    assertEquals(2, profile.followerCount());
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
        .setAuthentication(new UsernamePasswordAuthenticationToken("self", token));

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
  @DisplayName("Delete: found -> deletes and returns mapped detail")
  public void testDeleteAccount_whenFound_DeletesAndReturnsDetail() throws Exception {
    var entity = AccountServiceStub.getAccountWhenExistsStub();
    var detail = AccountDetail.builder().id(entity.getId()).build();

    when(accountRepository.findById(eq(AccountServiceStub.ID)))
        .thenReturn(Optional.of(entity));
    when(accountMapper.toAccount(eq(entity))).thenReturn(detail);

    var result = accountService.deleteAccount(AccountServiceStub.ID);

    assertEquals(detail, result);
    verify(accountRepository).findById(eq(AccountServiceStub.ID));
    verify(accountRepository).delete(eq(entity));
    verify(accountMapper).toAccount(eq(entity));
    verifyNoMoreInteractions(accountRepository, accountMapper);
  }

  @Test
  @DisplayName("Delete: not found -> throws 404")
  public void testDeleteAccount_whenNotFound_Throws404() {
    when(accountRepository.findById(eq(AccountServiceStub.ID)))
        .thenReturn(Optional.empty());

    assertThrows(ResourceNotFoundException.class, () -> accountService.deleteAccount(AccountServiceStub.ID));
    verify(accountRepository).findById(eq(AccountServiceStub.ID));
    verifyNoMoreInteractions(accountRepository);
  }

  @Test
  @DisplayName("Approve: found -> sets flags, saves, returns mapped detail")
  public void testApproveAccount_whenFound_ApprovesAndReturnsDetail() throws Exception {
    var entity = AccountServiceStub.getAccountWhenExistsStub();
    var approved = AccountDetail.builder().id(entity.getId()).build();
    var self = Account.builder().id("self-1").role(Role.ADMIN).build();
    var token = dev.christopherbell.permission.PermissionService.generateToken(self);
    SecurityContextHolder.getContext()
        .setAuthentication(new UsernamePasswordAuthenticationToken("self-1", token));
    when(accountRepository.findById(eq(AccountServiceStub.ID))).thenReturn(Optional.of(entity));
    when(accountRepository.save(eq(entity))).thenReturn(entity);
    when(accountMapper.toAccount(eq(entity))).thenReturn(approved);

    try {
      var result = accountService.approveAccount(AccountServiceStub.ID);

      assertEquals(approved, result);
      assertEquals("self-1", entity.getApprovedBy());
      verify(accountRepository).findById(eq(AccountServiceStub.ID));
      verify(accountRepository).save(eq(entity));
      verify(accountMapper).toAccount(eq(entity));
    } finally {
      SecurityContextHolder.clearContext();
    }
  }

  @Test
  @DisplayName("Approve: not found -> throws 404")
  public void testApproveAccount_whenNotFound_Throws404() {
    when(accountRepository.findById(eq(AccountServiceStub.ID))).thenReturn(Optional.empty());

    assertThrows(ResourceNotFoundException.class, () -> accountService.approveAccount(AccountServiceStub.ID));

    verify(accountRepository).findById(eq(AccountServiceStub.ID));
    verifyNoMoreInteractions(accountRepository);
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
        .isApproved(true)
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
    verify(accountRepository).findByEmailIgnoreCase(eq("chris@example.com"));
    verify(accountRepository).findByUsernameIgnoreCase(eq("Chris.Bell"));
    verify(accountRepository).save(eq(existing));
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
        .isApproved(existing.getIsApproved())
        .build();
    when(accountMapper.toAccount(eq(existing))).thenReturn(detail);

    AccountDetail result = accountService.updateAccount(request);

    assertEquals(Role.ADMIN, result.getRole());
    assertEquals(existing.getEmail(), result.getEmail());
    assertEquals(existing.getUsername(), result.getUsername());
    assertEquals(existing.getFirstName(), result.getFirstName());
    assertEquals(existing.getLastName(), result.getLastName());
    assertEquals(existing.getStatus(), result.getStatus());
    assertEquals(existing.getIsApproved(), result.getIsApproved());

    verify(accountRepository).findById(eq(AccountServiceStub.ID));
    verify(accountRepository).save(eq(existing));
    verify(accountMapper).toAccount(eq(existing));
    verifyNoMoreInteractions(accountRepository);
  }

  @Test
  @DisplayName("Update: flags only -> updates status and approval")
  public void testUpdateAccount_whenFlagsOnly_updatesStatusAndApproval() throws Exception {
    var existing = AccountServiceStub.getAccountWhenExistsStub();
    var request = AccountServiceStub.getAccountUpdateRequestWhenFlagsOnlyStub();

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
        .isApproved(true)
        .build();
    when(accountMapper.toAccount(eq(existing))).thenReturn(detail);

    AccountDetail result = accountService.updateAccount(request);

    assertEquals(AccountStatus.ACTIVE, result.getStatus());
    assertEquals(true, result.getIsApproved());

    verify(accountRepository).findById(eq(AccountServiceStub.ID));
    verify(accountRepository).save(eq(existing));
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
        .isApproved(existing.getIsApproved())
        .build();
    when(accountMapper.toAccount(eq(existing))).thenReturn(detail);

    AccountDetail result = accountService.updateAccount(request);

    assertEquals(existing.getEmail(), result.getEmail());
    assertEquals(existing.getUsername(), result.getUsername());
    assertEquals(existing.getFirstName(), result.getFirstName());
    assertEquals(existing.getLastName(), result.getLastName());
    assertEquals(existing.getRole(), result.getRole());
    assertEquals(existing.getStatus(), result.getStatus());
    assertEquals(existing.getIsApproved(), result.getIsApproved());

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
    verify(sharedFolderAudit).recordCurrent(
        "PERMISSION_CHANGE", account.getId(), null, "rejected", "invalid_request");
  }

  private String hashResetToken(String token) throws Exception {
    var digest = MessageDigest.getInstance("SHA-256");
    var hash = digest.digest(token.getBytes(StandardCharsets.UTF_8));
    return Base64.getEncoder().encodeToString(hash);
  }
}
