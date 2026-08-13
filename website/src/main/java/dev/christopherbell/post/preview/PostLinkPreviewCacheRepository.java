package dev.christopherbell.post.preview;

import java.time.Instant;
import java.util.Optional;

public interface PostLinkPreviewCacheRepository {
  Optional<PostLinkPreviewCacheEntry> findById(String id);
  PostLinkPreviewCacheEntry save(PostLinkPreviewCacheEntry entry);
  int deleteExpired(Instant cutoff, int batchLimit);
}
