package dev.christopherbell.post.discovery;

import dev.christopherbell.libs.pagination.StableCursor;
import dev.christopherbell.post.model.Post;
import java.time.Instant;
import java.util.Optional;

/** Persistence-neutral anonymous Void discovery boundary. */
public interface VoidDiscoveryQueryPort {
  VoidDiscoveryPage<Post> newArrivals(Optional<StableCursor> cursor, int size, Instant now);

  VoidDiscoveryPage<Post> fadingSoon(Optional<StableCursor> cursor, int size, Instant now);

  VoidDiscoveryPage<Post> recentlyRevived(Optional<StableCursor> cursor, int size, Instant now);

  VoidDiscoveryPage<Post> topic(
      String canonical, Optional<StableCursor> cursor, int size, Instant now);

  VoidDiscoveryPage<VoidTopicSummary> topics(
      Optional<StableCursor> cursor, int size, Instant now);
}
