package dev.christopherbell.post.preview;

import java.util.Optional;

public interface PostLinkPreviewCacheRepository {
  Optional<PostLinkPreviewCacheEntry> findById(String id);
  PostLinkPreviewCacheEntry save(PostLinkPreviewCacheEntry entry);
}
