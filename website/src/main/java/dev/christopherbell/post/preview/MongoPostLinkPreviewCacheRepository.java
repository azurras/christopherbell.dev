package dev.christopherbell.post.preview;

import dev.christopherbell.configuration.mongo.domain.DomainMongoOperationsFactory;
import dev.christopherbell.configuration.mongo.domain.KindScopedRepositorySupport;
import java.util.Optional;
import org.springframework.stereotype.Repository;

@Repository
final class MongoPostLinkPreviewCacheRepository
    extends KindScopedRepositorySupport<PostLinkPreviewCacheEntry>
    implements PostLinkPreviewCacheRepository {
  MongoPostLinkPreviewCacheRepository(DomainMongoOperationsFactory factory) {
    super(factory, PostLinkPreviewCacheEntry.class);
  }
  @Override public Optional<PostLinkPreviewCacheEntry> findById(String id) {
    return findValueById(id);
  }
  @Override public PostLinkPreviewCacheEntry save(PostLinkPreviewCacheEntry value) {
    return saveValue(value);
  }
}
