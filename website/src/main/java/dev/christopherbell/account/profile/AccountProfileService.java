package dev.christopherbell.account.profile;

import dev.christopherbell.account.AccountMapper;
import dev.christopherbell.account.AccountRepository;
import dev.christopherbell.account.model.Account;
import dev.christopherbell.account.model.dto.AccountDetail;
import dev.christopherbell.account.model.dto.AccountProfile;
import dev.christopherbell.libs.api.exception.ResourceNotFoundException;
import dev.christopherbell.libs.security.UsernameSanitizer;
import dev.christopherbell.permission.PermissionService;
import dev.christopherbell.post.PostRepository;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Builds public and self account profiles while keeping private fields out of public responses.
 */
@RequiredArgsConstructor
@Service
public class AccountProfileService {
  private final AccountRepository accountRepository;
  private final AccountMapper accountMapper;
  private final PostRepository postRepository;

  /**
   * Returns public profile metadata for a username.
   */
  public AccountProfile getPublicProfile(String username) throws ResourceNotFoundException {
    var account = findBySanitizedUsername(username);
    return toPublicProfile(account, getOptionalSelfAccount());
  }

  /**
   * Returns the authenticated account detail payload.
   */
  public AccountDetail getSelfAccount() throws ResourceNotFoundException {
    return accountMapper.toAccount(getSelfEntity());
  }

  public Account findBySanitizedUsername(String username) throws ResourceNotFoundException {
    var sanitizedUsername = UsernameSanitizer.sanitize(username);
    return accountRepository
        .findByUsername(sanitizedUsername)
        .orElseThrow(
            () -> new ResourceNotFoundException(
                String.format("Account with username %s not found.", sanitizedUsername)));
  }

  public Account getSelfEntity() throws ResourceNotFoundException {
    var selfId = PermissionService.getSelf();
    return accountRepository
        .findById(selfId)
        .orElseThrow(
            () -> new ResourceNotFoundException(
                String.format("Account with id %s not found.", selfId)));
  }

  public AccountProfile toPublicProfile(Account account, Optional<Account> selfAccount) {
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
        .status(account.getStatus())
        .followerCount(followerCount)
        .followingCount(following)
        .postCount(postRepository.countByAccountIdAndParentIdIsNull(account.getId()))
        .replyCount(postRepository.countByAccountIdAndParentIdIsNotNull(account.getId()))
        .followedByMe(followedByMe)
        .self(isSelf)
        .build();
  }

  private Optional<Account> getOptionalSelfAccount() {
    try {
      return Optional.of(getSelfEntity());
    } catch (Exception ignored) {
      return Optional.empty();
    }
  }
}
