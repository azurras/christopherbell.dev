package dev.christopherbell.post.expiration;

import dev.christopherbell.post.model.Post;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Atomic persistence effects used by post expiration calculations. */
public interface PostExpirationStore {
  void synchronizeReplies(String rootId, String rootPostId, Instant expiresOn);

  Optional<Post> incrementCounter(
      String postId, String field, int delta, Instant changedOn, boolean extended);

  long deletePosts(List<String> postIds);

  Optional<Post> decrementFloorZero(
      String postId, String field, int delta, Instant changedOn);

  void updateExpiration(String postId, Instant expiresOn);
}
