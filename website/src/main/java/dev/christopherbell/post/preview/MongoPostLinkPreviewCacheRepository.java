package dev.christopherbell.post.preview;

import dev.christopherbell.configuration.persistence.MongoPersistence;

import dev.christopherbell.configuration.mongo.domain.DomainMongoOperationsFactory;
import dev.christopherbell.configuration.mongo.domain.KindScopedRepositorySupport;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Repository;

@MongoPersistence
@Repository
class MongoPostLinkPreviewCacheRepository
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

  @Override
  public int deleteExpired(Instant cutoff, int batchLimit) {
    requireBatchLimit(batchLimit);
    var candidates = find(Query.query(Criteria.where("expiresOn").lte(cutoff))
        .with(Sort.by("expiresOn").ascending())
        .limit(batchLimit));
    if (candidates.isEmpty()) return 0;
    var urls = candidates.stream().map(PostLinkPreviewCacheEntry::getUrl).toList();
    return Math.toIntExact(mongo.remove(Query.query(Criteria.where("url").in(urls)))
        .getDeletedCount());
  }

  private static void requireBatchLimit(int batchLimit) {
    if (batchLimit < 1) throw new IllegalArgumentException("Cleanup batch limit must be positive");
  }
}
