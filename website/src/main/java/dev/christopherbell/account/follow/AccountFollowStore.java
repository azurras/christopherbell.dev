package dev.christopherbell.account.follow;

import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.Pageable;

/** Atomic persistence and bounded-query boundary for account-follow edges. */
public interface AccountFollowStore {
  FollowTransition follow(String followerId, String followedId, Instant createdOn);

  FollowTransition unfollow(String followerId, String followedId);

  boolean exists(String followerId, String followedId);

  long countFollowing(String accountId);

  long countFollowers(String accountId);

  List<String> followedAccountIds(String accountId, Pageable page);

  List<String> followerAccountIds(String accountId, Pageable page);

  void deleteForAccount(String accountId);

  static String edgeId(String followerId, String followedId) {
    return followerId + ":" + followedId;
  }

  record FollowTransition(boolean created, boolean removed) {}
}
