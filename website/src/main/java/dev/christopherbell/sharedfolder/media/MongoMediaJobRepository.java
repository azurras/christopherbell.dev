package dev.christopherbell.sharedfolder.media;

import dev.christopherbell.configuration.mongo.domain.DomainMongoOperationsFactory;
import dev.christopherbell.configuration.mongo.domain.KindScopedRepositorySupport;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Repository;

@Repository
public final class MongoMediaJobRepository extends KindScopedRepositorySupport<MediaJob>
    implements MediaJobRepository {
  public MongoMediaJobRepository(DomainMongoOperationsFactory factory) { super(factory, MediaJob.class); }
  @Override public MediaJob save(MediaJob value) { return saveValue(value); }
  @Override public Optional<MediaJob> findById(String id) { return findValueById(id); }
  @Override public void deleteById(String id) { super.deleteById(id); }
  @Override public Optional<MediaJob> findFirstByCacheKeyAndStatusOrderByUpdatedAtDesc(String key, MediaJobStatus status) {
    return first(Query.query(Criteria.where("cacheKey").is(key).and("status").is(status)), Sort.by(Sort.Direction.DESC, "updatedAt"));
  }
  @Override public Optional<MediaJob> findFirstByCacheKeyAndStatusInOrderByCreatedAtAsc(String key, Collection<MediaJobStatus> statuses) {
    return first(Query.query(Criteria.where("cacheKey").is(key).and("status").in(statuses)), Sort.by("createdAt"));
  }
  @Override public long countByStatusIn(Collection<MediaJobStatus> statuses) { return mongo.count(Query.query(Criteria.where("status").in(statuses))); }
  @Override public long countByOwnerIdAndStatusIn(String owner, Collection<MediaJobStatus> statuses) {
    return mongo.count(Query.query(Criteria.where("ownerId").is(owner).and("status").in(statuses)));
  }
  @Override public Slice<MediaJob> findByOwnerIdOrderByIdAsc(String owner, Pageable page) {
    return slice(Query.query(Criteria.where("ownerId").is(owner)).with(Sort.by("id")), page);
  }
  @Override public List<MediaJob> findByStatusIn(Collection<MediaJobStatus> statuses) { return find(Query.query(Criteria.where("status").in(statuses))); }
  @Override public Optional<MediaJob> findFirstByStatusAndDescriptorPublishedFalseOrderByCreatedAtAsc(MediaJobStatus status) {
    return first(Query.query(Criteria.where("status").is(status).and("descriptorPublished").is(false)), Sort.by("createdAt"));
  }
  @Override public Optional<MediaJob> findFirstByDescriptorPublishedTrueAndStatusInOrderByCreatedAtAsc(Collection<MediaJobStatus> statuses) {
    return first(Query.query(Criteria.where("descriptorPublished").is(true).and("status").in(statuses)), Sort.by("createdAt"));
  }
  @Override public Slice<MediaJob> findByStatusOrderByLastAccessedAtAscIdAsc(MediaJobStatus status, Pageable page) {
    return slice(Query.query(Criteria.where("status").is(status)).with(Sort.by("lastAccessedAt", "id")), page);
  }
  @Override public Slice<MediaJob> findByStatusInAndCleanupAfterLessThanEqualAndArtifactsCleanedFalseOrderByCleanupAfterAscIdAsc(
      Collection<MediaJobStatus> statuses, Instant cleanupAfter, Pageable page) {
    return slice(Query.query(Criteria.where("status").in(statuses).and("cleanupAfter").lte(cleanupAfter)
        .and("artifactsCleaned").is(false)).with(Sort.by("cleanupAfter", "id")), page);
  }
  @Override public long cancelActive(String id, String owner, Instant updatedAt, Instant cleanupAfter) {
    return mongo.updateFirst(Query.query(Criteria.where("id").is(id).and("ownerId").is(owner)
        .and("status").in(MediaJobStatus.active())), new Update().set("status", MediaJobStatus.CANCELED)
        .set("updatedAt", updatedAt).set("cleanupAfter", cleanupAfter).set("artifactsCleaned", false)
        .set("descriptorPublished", false).unset("activeCacheKey").unset("deleteAt")).getMatchedCount();
  }
  private Optional<MediaJob> first(Query query, Sort sort) { query.with(sort).limit(1); return findOne(query); }
}
