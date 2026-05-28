package dev.christopherbell.account.moderation;

import dev.christopherbell.account.AccountMapper;
import dev.christopherbell.account.AccountRepository;
import dev.christopherbell.account.model.Account;
import dev.christopherbell.account.model.AccountStatus;
import dev.christopherbell.account.model.dto.AccountDetail;
import dev.christopherbell.account.model.dto.AccountUpdateRequest;
import dev.christopherbell.libs.api.exception.InvalidRequestException;
import dev.christopherbell.libs.api.exception.ResourceExistsException;
import dev.christopherbell.libs.api.exception.ResourceNotFoundException;
import dev.christopherbell.libs.security.EmailSanitizer;
import dev.christopherbell.libs.security.UsernameSanitizer;
import dev.christopherbell.permission.PermissionService;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Owns administrator-driven account approval, status, and role changes.
 */
@RequiredArgsConstructor
@Slf4j
@Service
public class AccountModerationService {
  private final AccountRepository accountRepository;
  private final AccountMapper accountMapper;

  /**
   * Approves an account and records the current admin id.
   */
  public AccountDetail approveAccount(String accountId) throws ResourceNotFoundException {
    log.info("Approving account with id {}", accountId);
    var account = getExistingOrThrow(accountId);
    account.setApprovedBy(PermissionService.getSelf());
    account.setIsApproved(true);
    account.setStatus(AccountStatus.ACTIVE);
    account.setLastUpdatedOn(Instant.now());
    accountRepository.save(account);
    return accountMapper.toAccount(account);
  }

  /**
   * Applies admin account updates while preserving unique email and username constraints.
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
    var owner = accountRepository.findByEmailIgnoreCase(email);
    if (owner.isPresent() && !owner.get().getId().equals(selfId)) {
      throw new ResourceExistsException("Email already in use by another account.");
    }
  }

  private void ensureUsernameUniqueForUpdate(String username, String selfId)
      throws ResourceExistsException {
    var owner = accountRepository.findByUsernameIgnoreCase(username);
    if (owner.isPresent() && !owner.get().getId().equals(selfId)) {
      throw new ResourceExistsException("Username already in use by another account.");
    }
  }
}
