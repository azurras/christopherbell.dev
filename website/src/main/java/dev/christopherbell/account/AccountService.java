package dev.christopherbell.account;

import com.mongodb.MongoWriteException;
import dev.christopherbell.account.model.dto.AccountDetail;
import dev.christopherbell.account.model.Account;
import dev.christopherbell.account.model.AccountStatus;
import dev.christopherbell.account.model.dto.AccountCreateRequest;
import dev.christopherbell.account.model.dto.AccountProfile;
import dev.christopherbell.account.model.dto.AccountUpdateRequest;
import dev.christopherbell.account.model.AccountLoginRequest;
import dev.christopherbell.account.model.Role;
import dev.christopherbell.libs.api.exception.InvalidTokenException;
import dev.christopherbell.libs.api.exception.InvalidRequestException;
import dev.christopherbell.libs.api.exception.ResourceExistsException;
import dev.christopherbell.libs.api.exception.ResourceNotFoundException;
import dev.christopherbell.libs.security.EmailSanitizer;
import dev.christopherbell.libs.security.PasswordUtil;
import dev.christopherbell.permission.PermissionService;
import dev.christopherbell.libs.security.UsernameSanitizer;
import java.security.NoSuchAlgorithmException;
import java.security.spec.InvalidKeySpecException;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
  private final AccountMapper accountMapper;
  private final AccountRepository accountRepository;

  /**
   * Approves an account by setting its approvedBy field to the current user's ID and changing its
   * status to ACTIVE.
   *
   * @param accountId - the ID of the account to approve.
   * @return the approved account.
   * @throws ResourceNotFoundException if the account cannot be found.
   */
  public AccountDetail approveAccount(String accountId) throws ResourceNotFoundException {
    log.info("Approving account with id {}", accountId);
    var account =
        accountRepository
            .findById(accountId)
            .orElseThrow(
                () ->
                    new ResourceNotFoundException(
                        String.format("Account with id %s not found.", accountId)));
    var selfAccount = getSelfAccount();
    account.setApprovedBy(selfAccount.getId());
    account.setIsApproved(true);
    account.setStatus(AccountStatus.ACTIVE);
    account.setLastUpdatedOn(Instant.now());
    accountRepository.save(account);
    return accountMapper.toAccount(account);
  }

  /**
   * Creates a new account.
   *
   * @param accountCreateRequest - contains new information for an account.
   * @return back an account object if creation was successful.
   */
  public AccountDetail createAccount(AccountCreateRequest accountCreateRequest)
      throws ResourceExistsException {
    log.info("Creating account for username {}", accountCreateRequest.username());
    var account = createAccountEntity(accountCreateRequest);
    try {
      var salt = PasswordUtil.generateSalt();
      var hash = PasswordUtil.hashPassword(accountCreateRequest.password(), salt);
      account.setPasswordSalt(salt);
      account.setPasswordHash(hash);
      accountRepository.save(account);
      log.info("Successfully created account for username {}", accountCreateRequest.username());
    } catch (NoSuchAlgorithmException | InvalidKeySpecException e) {
      throw new RuntimeException("Can't create account due to password issues", e);
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
        .email(accountCreateRequest.email())
        .firstName(accountCreateRequest.firstName())
        .isApproved(true)
        .lastName(accountCreateRequest.lastName())
        .lastUpdatedOn(Instant.now())
        .role(Role.USER)
        .status(AccountStatus.ACTIVE)
        .followingIds(new HashSet<>())
        .username(accountCreateRequest.username())
        .build();
  }

  /**
   * Deletes an account by its ID.
   *
   * @param accountId the ID of the account to delete.
   * @return the deleted account.
   * @throws ResourceNotFoundException if the account cannot be found.
   */
  public AccountDetail deleteAccount(String accountId) throws ResourceNotFoundException {
    log.info("Deleting account with id: {}", accountId);
    var account =
        accountRepository
            .findById(accountId)
            .orElseThrow(
                () ->
                    new ResourceNotFoundException(
                        String.format("Account with id %s not found.", accountId)));
    accountRepository.delete(account);
    log.info("Successfully deleted account with id: {}", accountId);
    return accountMapper.toAccount(account);
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
            .findByEmail(sanitizedEmail)
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
    var account = findBySanitizedUsername(username);
    return toPublicProfile(account, getOptionalSelfAccount());
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
    var self = getSelfEntity();
    var target = findBySanitizedUsername(username);
    if (self.getId().equals(target.getId())) {
      throw new InvalidRequestException("You cannot follow yourself.");
    }
    if (self.getFollowingIds() == null) {
      self.setFollowingIds(new HashSet<>());
    }
    self.getFollowingIds().add(target.getId());
    self.setLastUpdatedOn(Instant.now());
    accountRepository.save(self);
    return toPublicProfile(target, Optional.of(self));
  }

  /**
   * Unfollows the account identified by username for the current user.
   *
   * @param username target username to unfollow
   * @return updated public profile for the target account
   * @throws ResourceNotFoundException if the current or target account cannot be found
   */
  public AccountProfile unfollowAccount(String username) throws ResourceNotFoundException {
    var self = getSelfEntity();
    var target = findBySanitizedUsername(username);
    if (self.getFollowingIds() == null) {
      self.setFollowingIds(new HashSet<>());
    } else {
      self.getFollowingIds().remove(target.getId());
    }
    self.setLastUpdatedOn(Instant.now());
    accountRepository.save(self);
    return toPublicProfile(target, Optional.of(self));
  }

  /**
   * List all accounts in the system.
   *
   * @return a list of all accounts.
   */
  public List<AccountDetail> getAccounts() {
    log.info("Getting all accounts");
    var accounts = accountRepository.findAll();
    return accounts.stream().map(accountMapper::toAccount).toList();
  }

  /**
   * Gets the account of the currently authenticated user.
   *
   * @return the account of the currently authenticated user.
   * @throws ResourceNotFoundException if the account cannot be found.
   */
  public AccountDetail getSelfAccount() throws ResourceNotFoundException {
    return accountMapper.toAccount(getSelfEntity());
  }

  /**
   * Validates login information from a request and returns a JWT if it is correct.
   *
   * @param accountLoginRequest - account for which the requester wishes to gain access to.
   * @return a JWT token.
   * @throws InvalidTokenException - if login information is incorrect.
   */
  public String loginAccount(AccountLoginRequest accountLoginRequest) throws Exception {
    try {
      var email = accountLoginRequest.email();
      var sanitizedEmail = EmailSanitizer.sanitize(email);
      var account =
          accountRepository
              .findByEmail(sanitizedEmail)
              .orElseThrow(
                  () ->
                      new ResourceNotFoundException(
                          String.format("Account with email %s not found.", sanitizedEmail)));

      var isAuthenticated = PermissionService.isAuthenticated(accountLoginRequest, account);

      if (isAuthenticated) {
        if (PermissionService.isAccountActive(account.getStatus())) {
          account.setLastLoginOn(Instant.now());
          accountRepository.save(account);
          log.info("Successful login for account with id: {}", account.getId());
          return PermissionService.generateToken(account);
        } else {
          throw new AccountNotActiveException("Account is not active.");
        }
      } else {
        throw new InvalidTokenException("Given Login information was not correct.");
      }
    } catch (NoSuchAlgorithmException | InvalidKeySpecException e) {
      throw new InvalidTokenException("Error validating password: " + e.getMessage(), e);
    }
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
    validateUpdateRequest(request);
    var existing = getExistingOrThrow(request.id());
    applyUpdates(existing, request);
    var saved = accountRepository.save(existing);
    return accountMapper.toAccount(saved);
  }

  private void validateUpdateRequest(AccountUpdateRequest request) throws InvalidRequestException {
    if (request == null || request.id() == null || request.id().isBlank()) {
      throw new InvalidRequestException("Account id cannot be null or blank.");
    }
  }

  private Account getExistingOrThrow(String id) throws ResourceNotFoundException {
    return accountRepository
        .findById(id)
        .orElseThrow(
            () -> new ResourceNotFoundException(String.format("Account with id %s not found.", id)));
  }

  private void applyUpdates(Account existing, AccountUpdateRequest request)
      throws ResourceExistsException {
    applyBasicUpdates(existing, request);
    updateEmailIfProvided(existing, request.email());
    updateUsernameIfProvided(existing, request.username());
  }

  private void applyBasicUpdates(Account existing, AccountUpdateRequest request) {
    if (request.firstName() != null) existing.setFirstName(request.firstName());
    if (request.lastName() != null) existing.setLastName(request.lastName());
    if (request.role() != null) existing.setRole(request.role());
    if (request.status() != null) existing.setStatus(request.status());
    if (request.isApproved() != null) existing.setIsApproved(request.isApproved());
  }

  private void updateEmailIfProvided(Account existing, String email) throws ResourceExistsException {
    if (email == null) return;
    var sanitized = EmailSanitizer.sanitize(email);
    if (!sanitized.equals(existing.getEmail())) {
      ensureEmailUniqueForUpdate(sanitized, existing.getId());
    }
    existing.setEmail(sanitized);
  }

  private void updateUsernameIfProvided(Account existing, String username)
      throws ResourceExistsException {
    if (username == null) return;
    var sanitized = UsernameSanitizer.sanitize(username);
    if (!sanitized.equals(existing.getUsername())) {
      ensureUsernameUniqueForUpdate(sanitized, existing.getId());
    }
    existing.setUsername(sanitized);
  }

  private void ensureEmailUniqueForUpdate(String email, String selfId) throws ResourceExistsException {
    var owner = accountRepository.findByEmail(email);
    if (owner.isPresent() && !owner.get().getId().equals(selfId)) {
      throw new ResourceExistsException("Email already in use by another account.");
    }
  }

  private void ensureUsernameUniqueForUpdate(String username, String selfId)
      throws ResourceExistsException {
    var owner = accountRepository.findByUsername(username);
    if (owner.isPresent() && !owner.get().getId().equals(selfId)) {
      throw new ResourceExistsException("Username already in use by another account.");
    }
  }

  private Account findBySanitizedUsername(String username) throws ResourceNotFoundException {
    var sanitizedUsername = UsernameSanitizer.sanitize(username);
    return accountRepository
        .findByUsername(sanitizedUsername)
        .orElseThrow(
            () -> new ResourceNotFoundException(
                String.format("Account with username %s not found.", sanitizedUsername)));
  }

  private Account getSelfEntity() throws ResourceNotFoundException {
    var selfId = PermissionService.getSelf();
    return accountRepository
        .findById(selfId)
        .orElseThrow(
            () -> new ResourceNotFoundException(
                String.format("Account with id %s not found.", selfId)));
  }

  private Optional<Account> getOptionalSelfAccount() {
    try {
      return Optional.of(getSelfEntity());
    } catch (Exception ignored) {
      return Optional.empty();
    }
  }

  private AccountProfile toPublicProfile(Account account, Optional<Account> selfAccount) {
    var self = selfAccount.orElse(null);
    var following = account.getFollowingIds() != null ? account.getFollowingIds().size() : 0;
    var followerCount = accountRepository.countByFollowingIdsContaining(account.getId());
    var followedByMe = self != null
        && self.getFollowingIds() != null
        && self.getFollowingIds().contains(account.getId());
    var isSelf = self != null && self.getId().equals(account.getId());
    return AccountProfile.builder()
        .id(account.getId())
        .username(account.getUsername())
        .firstName(account.getFirstName())
        .lastName(account.getLastName())
        .status(account.getStatus())
        .followerCount(followerCount)
        .followingCount(following)
        .followedByMe(followedByMe)
        .self(isSelf)
        .build();
  }
}
