package dev.christopherbell.account.profile;

import dev.christopherbell.account.AccountMapper;
import dev.christopherbell.account.AccountRepository;
import dev.christopherbell.account.follow.AccountFollowStore;
import dev.christopherbell.account.model.Account;
import dev.christopherbell.account.model.AccountStatus;
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
  private final AccountFollowStore follows;

  /**
   * Returns public profile metadata for a username.
   */
  public AccountProfile getPublicProfile(String username) throws ResourceNotFoundException {
    var account = findBySanitizedUsername(username);
    return getOptionalSelfAccount()
        .map(viewer -> toPublicProfile(account, viewer))
        .orElseGet(() -> profile(account, false, false));
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
        .findByUsernameAndStatus(sanitizedUsername, AccountStatus.ACTIVE)
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

  /** Public profile metadata for {@code account} as seen by the signed-in {@code viewer}. */
  public AccountProfile toPublicProfile(Account account, Account viewer) {
    return profile(
        account,
        follows.exists(viewer.getId(), account.getId()),
        viewer.getId().equals(account.getId()));
  }

  private AccountProfile profile(Account account, boolean followedByMe, boolean isSelf) {
    var following = follows.countFollowing(account.getId());
    var followerCount = follows.countFollowers(account.getId());
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
    } catch (IllegalStateException | ResourceNotFoundException missingViewer) {
      return Optional.empty();
    }
  }
}
