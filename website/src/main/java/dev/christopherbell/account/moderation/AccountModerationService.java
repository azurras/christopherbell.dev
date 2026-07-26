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
import dev.christopherbell.admin.activity.AdminActivityService;
import dev.christopherbell.admin.activity.ModerationAuditCommand;
import java.time.Instant;
import java.util.Map;
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
  private final AdminActivityService adminActivityService;

  /**
   * Approves an account and records the current admin id.
   */
  public AccountDetail approveAccount(String accountId)
      throws InvalidRequestException, ResourceNotFoundException {
    log.info("Approving account with id {}", accountId);
    var account = getExistingOrThrow(accountId);
    var before = ModerationAccountSnapshot.from(account);
    var after = new ModerationAccountSnapshot(account.getRole(), AccountStatus.ACTIVE);
    var auditCommand = ModerationAuditCommand.create(
        "ACCOUNT_STATUS_CHANGED",
        "ACCOUNT",
        account.getId(),
        "@" + (account.getUsername() == null ? account.getId() : account.getUsername()),
        "Account approved through the admin approval endpoint.",
        "%s approved an account.",
        before.values(),
        after.values(),
        Map.of("source", "admin-approval", "accountId", account.getId()));
    account.setApprovedBy(PermissionService.getSelf());
    account.setIsApproved(true);
    account.setStatus(AccountStatus.ACTIVE);
    account.setLastUpdatedOn(Instant.now());
    accountRepository.save(account);
    adminActivityService.recordModeration(auditCommand);
    return accountMapper.toAccount(account);
  }

  /**
   * Applies admin account updates while preserving unique email and username constraints.
   */
  public AccountDetail updateAccount(AccountUpdateRequest request)
      throws InvalidRequestException, ResourceNotFoundException, ResourceExistsException {
    validateUpdateRequest(request);
    var existing = getExistingOrThrow(request.id());
    var before = ModerationAccountSnapshot.from(existing);
    var proposed = before.with(request);
    boolean moderated = !before.equals(proposed);
    if (moderated && (request.moderationReason() == null
        || request.moderationReason().isBlank())) {
      throw new InvalidRequestException("Moderation reason is required.");
    }
    var auditCommand = moderated
        ? ModerationAuditCommand.create(
            moderationAction(before, proposed),
            "ACCOUNT",
            existing.getId(),
            "@" + (existing.getUsername() == null ? existing.getId() : existing.getUsername()),
            request.moderationReason(),
            "%s changed account moderation state.",
            before.values(),
            proposed.values(),
            Map.of("source", "back-office", "accountId", existing.getId()))
        : null;
    applyUpdates(existing, request);
    var saved = accountRepository.save(existing);
    if (moderated) {
      adminActivityService.recordModeration(auditCommand);
    }
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

  private String moderationAction(
      ModerationAccountSnapshot before,
      ModerationAccountSnapshot after) {
    if (before.role() != after.role() && before.status() != after.status()) {
      return "ACCOUNT_MODERATION_CHANGED";
    }
    return before.role() != after.role() ? "ACCOUNT_ROLE_CHANGED" : "ACCOUNT_STATUS_CHANGED";
  }

  private record ModerationAccountSnapshot(
      dev.christopherbell.account.model.Role role,
      AccountStatus status) {

    private static ModerationAccountSnapshot from(Account account) {
      return new ModerationAccountSnapshot(account.getRole(), account.getStatus());
    }

    private ModerationAccountSnapshot with(AccountUpdateRequest request) {
      return new ModerationAccountSnapshot(
          request.role() == null ? role : request.role(),
          request.status() == null ? status : request.status());
    }

    private Map<String, String> values() {
      return Map.of(
          "role", role == null ? "" : role.name(),
          "status", status == null ? "" : status.name());
    }
  }
}
