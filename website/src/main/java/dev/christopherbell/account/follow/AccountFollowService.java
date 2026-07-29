package dev.christopherbell.account.follow;

import dev.christopherbell.account.AccountRepository;
import dev.christopherbell.account.model.dto.AccountProfile;
import dev.christopherbell.account.profile.AccountProfileService;
import dev.christopherbell.libs.api.exception.InvalidRequestException;
import dev.christopherbell.libs.api.exception.ResourceNotFoundException;
import dev.christopherbell.post.abuse.NewAccountVoidMutationLimiter;
import dev.christopherbell.post.abuse.VoidMutationKind;
import java.time.Clock;
import java.util.HashSet;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Owns follow graph updates for account profiles.
 */
@RequiredArgsConstructor
@Service
public class AccountFollowService {
  private final AccountRepository accountRepository;
  private final AccountProfileService accountProfileService;
  private final NewAccountVoidMutationLimiter mutationLimiter;
  private final Clock clock;

  /**
   * Follows the account identified by username for the current user.
   */
  public AccountProfile followAccount(String username)
      throws ResourceNotFoundException, InvalidRequestException {
    var self = accountProfileService.getSelfEntity();
    var target = accountProfileService.findBySanitizedUsername(username);
    if (self.getId().equals(target.getId())) {
      throw new InvalidRequestException("You cannot follow yourself.");
    }
    if (self.getFollowingIds() == null) {
      self.setFollowingIds(new HashSet<>());
    }
    if (!self.getFollowingIds().contains(target.getId())) {
      mutationLimiter.require(self, VoidMutationKind.FOLLOW);
      self.getFollowingIds().add(target.getId());
    }
    self.setLastUpdatedOn(clock.instant());
    accountRepository.save(self);
    return accountProfileService.toPublicProfile(target, Optional.of(self));
  }

  /**
   * Unfollows the account identified by username for the current user.
   */
  public AccountProfile unfollowAccount(String username) throws ResourceNotFoundException {
    var self = accountProfileService.getSelfEntity();
    var target = accountProfileService.findBySanitizedUsername(username);
    if (self.getFollowingIds() == null) {
      self.setFollowingIds(new HashSet<>());
    } else {
      self.getFollowingIds().remove(target.getId());
    }
    self.setLastUpdatedOn(clock.instant());
    accountRepository.save(self);
    return accountProfileService.toPublicProfile(target, Optional.of(self));
  }
}
