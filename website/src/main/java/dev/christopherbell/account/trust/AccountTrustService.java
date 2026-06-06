package dev.christopherbell.account.trust;

import dev.christopherbell.account.AccountRepository;
import dev.christopherbell.account.trust.model.AccountTrustDetail;
import dev.christopherbell.account.trust.model.AccountTrustType;
import dev.christopherbell.libs.api.exception.InvalidRequestException;
import dev.christopherbell.libs.api.exception.ResourceNotFoundException;
import dev.christopherbell.libs.security.UsernameSanitizer;
import dev.christopherbell.permission.PermissionService;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** Owns user-level mute and block relationships. */
@RequiredArgsConstructor
@Service
public class AccountTrustService {
  private final AccountTrustRepository accountTrustRepository;
  private final AccountRepository accountRepository;
  private final PermissionService permissionService;

  /** Creates or returns a mute/block relationship for the current user. */
  public AccountTrustDetail setTrust(String username, AccountTrustType type)
      throws InvalidRequestException, ResourceNotFoundException {
    if (type == null) {
      throw new InvalidRequestException("Trust type is required.");
    }
    var ownerId = permissionService.getSelfId();
    var target = accountRepository.findByUsername(UsernameSanitizer.sanitize(username))
        .orElseThrow(() -> new ResourceNotFoundException("Account not found."));
    if (ownerId.equals(target.getId())) {
      throw new InvalidRequestException("You cannot mute or block yourself.");
    }

    var relationship = accountTrustRepository.findByOwnerAccountIdAndTargetAccountIdAndType(
            ownerId,
            target.getId(),
            type)
        .orElseGet(() -> accountTrustRepository.save(AccountTrustRelationship.builder()
            .ownerAccountId(ownerId)
            .targetAccountId(target.getId())
            .type(type)
            .build()));
    return toDetail(relationship, target.getUsername());
  }

  /** Clears a mute/block relationship for the current user. */
  public void clearTrust(String username, AccountTrustType type)
      throws InvalidRequestException, ResourceNotFoundException {
    if (type == null) {
      throw new InvalidRequestException("Trust type is required.");
    }
    var ownerId = permissionService.getSelfId();
    var target = accountRepository.findByUsername(UsernameSanitizer.sanitize(username))
        .orElseThrow(() -> new ResourceNotFoundException("Account not found."));
    accountTrustRepository.deleteByOwnerAccountIdAndTargetAccountIdAndType(ownerId, target.getId(), type);
  }

  /** Accounts hidden from the current user's feeds through mute or block. */
  public Set<String> hiddenAccountIdsForSelf() {
    var ownerId = permissionService.getSelfId();
    return accountTrustRepository.findByOwnerAccountIdAndTypeIn(
            ownerId,
            Set.of(AccountTrustType.MUTE, AccountTrustType.BLOCK))
        .stream()
        .map(AccountTrustRelationship::getTargetAccountId)
        .collect(Collectors.toSet());
  }

  /** True when either account has blocked the other account. */
  public boolean isBlockedEitherDirection(String firstAccountId, String secondAccountId) {
    if (firstAccountId == null || secondAccountId == null) {
      return false;
    }
    return accountTrustRepository.existsByOwnerAccountIdAndTargetAccountIdAndType(
        firstAccountId,
        secondAccountId,
        AccountTrustType.BLOCK)
        || accountTrustRepository.existsByOwnerAccountIdAndTargetAccountIdAndType(
            secondAccountId,
            firstAccountId,
            AccountTrustType.BLOCK);
  }

  private AccountTrustDetail toDetail(AccountTrustRelationship relationship, String targetUsername) {
    return AccountTrustDetail.builder()
        .ownerAccountId(relationship.getOwnerAccountId())
        .targetAccountId(relationship.getTargetAccountId())
        .targetUsername(targetUsername)
        .type(relationship.getType())
        .build();
  }
}
