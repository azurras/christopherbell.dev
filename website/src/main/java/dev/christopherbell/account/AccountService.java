package dev.christopherbell.account;

import com.mongodb.MongoWriteException;
import dev.christopherbell.account.auth.AccountAuthenticationService;
import dev.christopherbell.account.deletion.AccountDeletionResult;
import dev.christopherbell.account.deletion.AccountDeletionService;
import dev.christopherbell.account.follow.AccountFollowService;
import dev.christopherbell.account.moderation.AccountModerationService;
import dev.christopherbell.account.model.dto.AccountDetail;
import dev.christopherbell.account.model.dto.FederationConsentStatus;
import dev.christopherbell.account.model.Account;
import dev.christopherbell.account.model.AccountPasswordResetConfirmRequest;
import dev.christopherbell.account.model.AccountPasswordResetRequest;
import dev.christopherbell.account.model.AccountPermission;
import dev.christopherbell.account.model.AccountStatus;
import dev.christopherbell.account.model.dto.AccountCreateRequest;
import dev.christopherbell.account.model.dto.AccountProfile;
import dev.christopherbell.account.model.dto.AccountUsernameSuggestion;
import dev.christopherbell.account.model.dto.AccountUpdateRequest;
import dev.christopherbell.account.model.dto.MusicPermissionUpdate;
import dev.christopherbell.account.model.dto.SharedFolderPermissionUpdate;
import dev.christopherbell.account.model.AccountLoginRequest;
import dev.christopherbell.account.model.Role;
import dev.christopherbell.sharedfolder.audit.SharedFolderAuditRecorder;
import dev.christopherbell.sharedfolder.security.SharedFolderAccessService;
import dev.christopherbell.account.passwordreset.PasswordResetService;
import dev.christopherbell.account.profile.AccountProfileService;
import dev.christopherbell.libs.api.exception.InvalidTokenException;
import dev.christopherbell.libs.api.exception.InvalidRequestException;
import dev.christopherbell.libs.api.exception.InternalServiceException;
import dev.christopherbell.libs.api.exception.ResourceExistsException;
import dev.christopherbell.libs.api.exception.ResourceNotFoundException;
import dev.christopherbell.libs.security.EmailSanitizer;
import dev.christopherbell.libs.security.PasswordUtil;
import dev.christopherbell.libs.security.UsernameSanitizer;
import dev.christopherbell.federation.consent.FederationConsentService;
import java.security.NoSuchAlgorithmException;
import java.security.spec.InvalidKeySpecException;
import java.time.Instant;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

/**
 * Represents the service responsible for handling getting, creating, updating, and deleting
 * accounts.
 */
@RequiredArgsConstructor
@Slf4j
@Service
public class AccountService {
  private static final int DEFAULT_USERNAME_SUGGESTION_LIMIT = 8;
  private static final int MAX_USERNAME_SUGGESTION_LIMIT = 12;
  private static final int MAX_USERNAME_SEARCH_PREFIX_LENGTH = 32;
  private static final Pattern USERNAME_SEARCH_INVALID_CHARS = Pattern.compile("[^a-zA-Z0-9._-]");

  private final AccountMapper accountMapper;
  private final AccountRepository accountRepository;
  private final AccountDeletionService accountDeletionService;
  private final AccountAuthenticationService accountAuthenticationService;
  private final PasswordResetService passwordResetService;
  private final AccountProfileService accountProfileService;
  private final AccountFollowService accountFollowService;
  private final AccountModerationService accountModerationService;
  private final SharedFolderAuditRecorder sharedFolderAudit;
  private final SharedFolderAccessService sharedFolderAccess;
  private final FederationConsentService federationConsent;

  /**
   * Approves an account by setting its approvedBy field to the current user's ID and changing its
   * status to ACTIVE.
   *
   * @param accountId - the ID of the account to approve.
   * @return the approved account.
   * @throws ResourceNotFoundException if the account cannot be found.
   */
  public AccountDetail approveAccount(String accountId)
      throws InvalidRequestException, ResourceNotFoundException {
    return accountModerationService.approveAccount(accountId);
  }

  /**
   * Creates a new account.
   *
   * @param accountCreateRequest - contains new information for an account.
   * @return back an account object if creation was successful.
   */
  public AccountDetail createAccount(AccountCreateRequest accountCreateRequest)
      throws ResourceExistsException, InvalidRequestException {
    log.info("Creating account for username {}", accountCreateRequest.username());
    var account = createAccountEntity(accountCreateRequest);
    try {
      var salt = PasswordUtil.generateSalt();
      var hash = PasswordUtil.hashPassword(accountCreateRequest.password(), salt);
      account.setPasswordSalt(salt);
      account.setPasswordHash(hash);
      federationConsent.prepareNewAccount(account, accountCreateRequest.federationRequested());
      accountRepository.save(account);
      log.info("Successfully created account for username {}", accountCreateRequest.username());
    } catch (NoSuchAlgorithmException | InvalidKeySpecException e) {
      throw new InternalServiceException("Failed to create account credentials", e);
    } catch (DuplicateKeyException | MongoWriteException e) {
      throw new ResourceExistsException("Account with given email or username already exists.", e);
    }
    return accountMapper.toAccount(account);
  }

  /**
   * Creates a new default account entity using a given account object.
   *
   * @param accountCreateRequest - the account to create the accountEntity based on.
   * @return a new account entity with default settings.
   */
  public Account createAccountEntity(AccountCreateRequest accountCreateRequest) {
    return Account.builder()
        .id(String.valueOf(UUID.randomUUID()))
        .approvedBy(null)
        .createdOn(Instant.now())
        .email(EmailSanitizer.sanitize(accountCreateRequest.email()))
        .firstName(accountCreateRequest.firstName())
        .isApproved(true)
        .lastName(accountCreateRequest.lastName())
        .lastUpdatedOn(Instant.now())
        .role(Role.USER)
        .status(AccountStatus.ACTIVE)
        .followingIds(new HashSet<>())
        .username(UsernameSanitizer.sanitize(accountCreateRequest.username()))
        .build();
  }

  /**
   * Deletes an account by its ID.
   *
   * @param accountId the ID of the account to delete.
   * @return the deleted account.
   * @throws ResourceNotFoundException if the account cannot be found.
   */
  public AccountDeletionResult deleteAccount(String accountId)
      throws InvalidRequestException, ResourceNotFoundException {
    return accountDeletionService.delete(accountId);
  }

  /**
   * Gets an account by its email address.
   *
   * @param email the email address of the account.
   * @return the account with the given email address.
   * @throws ResourceNotFoundException if the account cannot be found.
   */
  public AccountDetail getAccountByEmail(String email) throws ResourceNotFoundException {
    var sanitizedEmail = EmailSanitizer.sanitize(email);
    var account =
        accountRepository
            .findByEmailIgnoreCase(sanitizedEmail)
            .orElseThrow(
                () ->
                    new ResourceNotFoundException(
                        String.format("Account with email %s not found.", sanitizedEmail)));
    return accountMapper.toAccount(account);
  }

  /**
   * Gets an account by its ID.
   *
   * @param id the ID of the account.
   * @return the account with the given ID.
   * @throws ResourceNotFoundException if the account cannot be found.
   */
  public AccountDetail getAccountById(String id) throws ResourceNotFoundException {
    log.info("Getting account with id {}", id);
    var account =
        accountRepository
            .findById(id)
            .orElseThrow(
                () ->
                    new ResourceNotFoundException(
                        String.format("Account with id %s not found.", id)));
    log.info("Successfully got account with id {}", id);
    return accountMapper.toAccount(account);
  }

  /**
   * Gets an account by its username.
   *
   * @param username the username of the account.
   * @return the account with the given username.
   * @throws ResourceNotFoundException if the account cannot be found.
   */
  public AccountDetail getAccountByUsername(String username) throws ResourceNotFoundException {
    var sanitizedUsername = UsernameSanitizer.sanitize(username);
    log.info("Getting account with username: {}", sanitizedUsername);
    var account =
        accountRepository
            .findByUsername(sanitizedUsername)
            .orElseThrow(
                () ->
                    new ResourceNotFoundException(
                        String.format("Account with username %s not found.", sanitizedUsername)));
    log.info("Successfully got account with username: {}", sanitizedUsername);
    return accountMapper.toAccount(account);
  }

  /**
   * Gets a public account profile by username without exposing private fields like email.
   *
   * @param username the username to resolve
   * @return public profile metadata and current viewer follow state
   * @throws ResourceNotFoundException if no account exists for the username
   */
  public AccountProfile getPublicProfile(String username) throws ResourceNotFoundException {
    return accountProfileService.getPublicProfile(username);
  }

  /**
   * Follows the account identified by username for the current user.
   *
   * @param username target username to follow
   * @return updated public profile for the target account
   * @throws ResourceNotFoundException if the current or target account cannot be found
   * @throws InvalidRequestException if the caller tries to follow themself
   */
  public AccountProfile followAccount(String username)
      throws ResourceNotFoundException, InvalidRequestException {
    return accountFollowService.followAccount(username);
  }

  /**
   * Unfollows the account identified by username for the current user.
   *
   * @param username target username to unfollow
   * @return updated public profile for the target account
   * @throws ResourceNotFoundException if the current or target account cannot be found
   */
  public AccountProfile unfollowAccount(String username) throws ResourceNotFoundException {
    return accountFollowService.unfollowAccount(username);
  }

  /**
   * List all accounts in the system.
   *
   * @return a list of all accounts.
   */
  public List<AccountDetail> getAccounts() {
    log.info("Getting first bounded account page for compatibility endpoint");
    var page = PageRequest.of(
        0,
        50,
        Sort.by(Sort.Direction.DESC, "createdOn")
            .and(Sort.by(Sort.Direction.DESC, "id")));
    var accounts = accountRepository.findAll(page).getContent();
    return accounts.stream().map(accountMapper::toAccount).toList();
  }

  /**
   * Gets the account of the currently authenticated user.
   *
   * @return the account of the currently authenticated user.
   * @throws ResourceNotFoundException if the account cannot be found.
   */
  public AccountDetail getSelfAccount() throws ResourceNotFoundException {
    return accountProfileService.getSelfAccount();
  }

  /** Applies the current account's explicit federation consent choice. */
  public AccountDetail setFederationEnabled(String accountId, boolean enabled)
      throws InvalidRequestException, ResourceNotFoundException {
    return federationConsent.setEnabled(accountId, enabled);
  }

  /** Returns authoritative consent and whether this deployment can enroll identities. */
  public FederationConsentStatus getFederationConsent(String accountId)
      throws ResourceNotFoundException {
    return federationConsent.status(accountId);
  }

  /**
   * Searches active accounts by username prefix for recipient autocomplete.
   *
   * @param usernamePrefix partial username typed by the caller
   * @param requestedLimit maximum number of suggestions requested by the caller
   * @return public-safe username suggestions excluding the current account
   * @throws ResourceNotFoundException if the current account cannot be resolved
   */
  public List<AccountUsernameSuggestion> searchUsernameSuggestions(
      String usernamePrefix,
      Integer requestedLimit
  ) throws ResourceNotFoundException {
    var prefix = sanitizeUsernameSearchPrefix(usernamePrefix);
    if (prefix.isBlank()) {
      return List.of();
    }

    var self = accountProfileService.getSelfEntity();
    var page = PageRequest.of(0, normalizeUsernameSuggestionLimit(requestedLimit));
    return accountRepository
        .findByUsernameStartingWithIgnoreCaseAndStatusOrderByUsernameAsc(
            prefix,
            AccountStatus.ACTIVE,
            page)
        .stream()
        .filter(account -> !self.getId().equals(account.getId()))
        .map(account -> new AccountUsernameSuggestion(account.getUsername()))
        .toList();
  }

  /**
   * Validates login information from a request and returns a JWT if it is correct.
   *
   * @param accountLoginRequest - account for which the requester wishes to gain access to.
   * @return a JWT token.
   * @throws InvalidTokenException - if login information is incorrect.
   */
  public String loginAccount(AccountLoginRequest accountLoginRequest) throws Exception {
    return accountAuthenticationService.loginAccount(accountLoginRequest);
  }

  private String sanitizeUsernameSearchPrefix(String usernamePrefix) {
    var cleaned = USERNAME_SEARCH_INVALID_CHARS
        .matcher(String.valueOf(usernamePrefix == null ? "" : usernamePrefix).strip())
        .replaceAll("");
    if (cleaned.length() <= MAX_USERNAME_SEARCH_PREFIX_LENGTH) {
      return cleaned;
    }
    return cleaned.substring(0, MAX_USERNAME_SEARCH_PREFIX_LENGTH);
  }

  private int normalizeUsernameSuggestionLimit(Integer requestedLimit) {
    if (requestedLimit == null || requestedLimit < 1) {
      return DEFAULT_USERNAME_SUGGESTION_LIMIT;
    }
    return Math.min(requestedLimit, MAX_USERNAME_SUGGESTION_LIMIT);
  }

  /**
   * Requests a password reset. The response is intentionally generic so callers cannot enumerate
   * registered email addresses.
   *
   * @param request request containing the account email address
   * @param baseUrl absolute application base URL used to build the reset link
   */
  public void requestPasswordReset(AccountPasswordResetRequest request, String baseUrl) {
    passwordResetService.requestPasswordReset(request, baseUrl);
  }

  /**
   * Completes a password reset by validating the supplied reset token and replacing the password.
   *
   * @param request reset token and new password
   * @throws InvalidRequestException if the request is malformed
   * @throws InvalidTokenException if the token is missing, invalid, or expired
   */
  public void resetPassword(AccountPasswordResetConfirmRequest request)
      throws InvalidRequestException, InvalidTokenException {
    passwordResetService.resetPassword(request);
  }

  /**
   * Updates an existing account with the provided request values.
   * Only non-null fields in the request are applied.
   *
   * @param request the update request containing fields to change; must include a non-blank id
   * @return the updated account detail
   * @throws InvalidRequestException if the request or id is null/blank
   * @throws ResourceNotFoundException if the account with the given id does not exist
   * @throws ResourceExistsException if unique fields (email/username) conflict with another account
   */
  public AccountDetail updateAccount(AccountUpdateRequest request)
      throws InvalidRequestException, ResourceNotFoundException, ResourceExistsException {
    return accountModerationService.updateAccount(request);
  }

  /**
   * Replaces an account's shared-folder capabilities without changing its role.
   *
   * @param accountId account whose capabilities are being changed
   * @param request requested read and write state
   * @return the saved account detail with its stored capabilities
   * @throws InvalidRequestException if the request is malformed or enables write without read
   * @throws ResourceNotFoundException if the account does not exist
   */
  public AccountDetail updateSharedFolderPermissions(
      String accountId,
      SharedFolderPermissionUpdate request) throws InvalidRequestException, ResourceNotFoundException {
    String auditResource = safeAuditAccountId(accountId);
    try {
      sharedFolderAccess.requireAdmin();
      if (request == null || request.read() == null || request.write() == null) {
        throw new InvalidRequestException("Shared-folder permissions are required.");
      }
      if (!request.read() && request.write()) {
        throw new InvalidRequestException("Shared-folder write requires read.");
      }

      var account = accountRepository.findById(accountId)
          .orElseThrow(() -> new ResourceNotFoundException("Account not found."));
      var next = account.getPermissions() == null || account.getPermissions().isEmpty()
          ? EnumSet.noneOf(AccountPermission.class)
          : EnumSet.copyOf(account.getPermissions());
      next.remove(AccountPermission.SHARED_FOLDER_READ);
      next.remove(AccountPermission.SHARED_FOLDER_WRITE);
      if (request.read()) {
        next.add(AccountPermission.SHARED_FOLDER_READ);
      }
      if (request.write()) {
        next.add(AccountPermission.SHARED_FOLDER_WRITE);
      }
      account.setPermissions(next);
      AccountDetail saved = accountMapper.toAccount(accountRepository.save(account));
      sharedFolderAudit.recordCurrent(
          "PERMISSION_CHANGE", auditResource, null, "accepted", null);
      return saved;
    } catch (InvalidRequestException failure) {
      sharedFolderAudit.recordRejectedOnce(
          "PERMISSION_CHANGE", auditResource, "invalid_request");
      throw failure;
    } catch (ResourceNotFoundException failure) {
      sharedFolderAudit.recordRejectedOnce(
          "PERMISSION_CHANGE", auditResource, "not_found");
      throw failure;
    } catch (RuntimeException failure) {
      sharedFolderAudit.recordFailureOnce("PERMISSION_CHANGE", auditResource, failure);
      throw failure;
    }
  }

  /** Replaces an account's Music capabilities while preserving unrelated capability families. */
  public AccountDetail updateMusicPermissions(
      String accountId,
      MusicPermissionUpdate request) throws InvalidRequestException, ResourceNotFoundException {
    String auditResource = safeAuditAccountId(accountId);
    try {
      sharedFolderAccess.requireAdmin();
      if (request == null || request.read() == null || request.write() == null) {
        throw new InvalidRequestException("Music permissions are required.");
      }
      if (!request.read() && request.write()) {
        throw new InvalidRequestException("Music write requires read.");
      }

      var account = accountRepository.findById(accountId)
          .orElseThrow(() -> new ResourceNotFoundException("Account not found."));
      var next = account.getPermissions() == null || account.getPermissions().isEmpty()
          ? EnumSet.noneOf(AccountPermission.class)
          : EnumSet.copyOf(account.getPermissions());
      next.remove(AccountPermission.MUSIC_READ);
      next.remove(AccountPermission.MUSIC_WRITE);
      if (request.read()) {
        next.add(AccountPermission.MUSIC_READ);
      }
      if (request.write()) {
        next.add(AccountPermission.MUSIC_WRITE);
      }
      account.setPermissions(next);
      AccountDetail saved = accountMapper.toAccount(accountRepository.save(account));
      sharedFolderAudit.recordCurrent(
          "MUSIC_PERMISSION_CHANGE", auditResource, null, "accepted", null);
      return saved;
    } catch (InvalidRequestException failure) {
      sharedFolderAudit.recordRejectedOnce(
          "MUSIC_PERMISSION_CHANGE", auditResource, "invalid_request");
      throw failure;
    } catch (ResourceNotFoundException failure) {
      sharedFolderAudit.recordRejectedOnce(
          "MUSIC_PERMISSION_CHANGE", auditResource, "not_found");
      throw failure;
    } catch (RuntimeException failure) {
      sharedFolderAudit.recordFailureOnce("MUSIC_PERMISSION_CHANGE", auditResource, failure);
      throw failure;
    }
  }

  private String safeAuditAccountId(String accountId) {
    return accountId != null && accountId.length() <= 128
        && accountId.matches("[A-Za-z0-9._-]+") ? accountId : "invalid-account";
  }
}
