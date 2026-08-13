package dev.christopherbell.post.like;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Atomic persistence and bounded aggregate-query boundary for post-like edges. */
public interface PostLikeStore {
  LikeTransition like(String postId, String accountId, Instant createdOn);

  LikeTransition unlike(String postId, String accountId);

  boolean exists(String postId, String accountId);

  Map<String, Integer> counts(Collection<String> postIds);

  Set<String> likedPostIds(String accountId, Collection<String> postIds);

  List<String> recentLikedPostIds(String accountId);

  void deleteForAccount(String accountId);

  void deleteForPosts(Collection<String> postIds);

  static String edgeId(String postId, String accountId) {
    return postId + ":" + accountId;
  }

  record LikeTransition(boolean created, boolean removed) {
    public int delta() {
      return created ? 1 : removed ? -1 : 0;
    }
  }
}
