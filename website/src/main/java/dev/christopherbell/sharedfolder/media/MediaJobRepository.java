package dev.christopherbell.sharedfolder.media;

import java.time.Instant;
import java.util.Collection;
import java.util.Optional;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;
import org.springframework.data.mongodb.repository.Update;

/** Durable admission and ownership queries for media jobs. */
public interface MediaJobRepository extends MongoRepository<MediaJob, String> {
  Optional<MediaJob> findFirstByCacheKeyAndStatusOrderByUpdatedAtDesc(
      String cacheKey, MediaJobStatus status);

  Optional<MediaJob> findFirstByCacheKeyAndStatusInOrderByCreatedAtAsc(
      String cacheKey, Collection<MediaJobStatus> statuses);

  long countByStatusIn(Collection<MediaJobStatus> statuses);

  long countByOwnerIdAndStatusIn(String ownerId, Collection<MediaJobStatus> statuses);

  List<MediaJob> findByStatusIn(Collection<MediaJobStatus> statuses);

  Optional<MediaJob> findFirstByStatusAndDescriptorPublishedFalseOrderByCreatedAtAsc(
      MediaJobStatus status);

  Optional<MediaJob> findFirstByDescriptorPublishedTrueAndStatusInOrderByCreatedAtAsc(
      Collection<MediaJobStatus> statuses);

  Slice<MediaJob> findByStatusOrderByLastAccessedAtAscIdAsc(
      MediaJobStatus status, Pageable pageable);

  @Query("{ '_id': ?0, 'ownerId': ?1, 'status': { '$in': ['QUEUED', 'INSPECTING', "
      + "'TRANSCODING', 'BUFFERING'] } }")
  @Update("{ '$set': { 'status': 'CANCELED', 'updatedAt': ?2, 'descriptorPublished': false }, "
      + "'$unset': { 'activeCacheKey': '' }, '$inc': { 'version': 1 } }")
  long cancelActive(String id, String ownerId, Instant updatedAt);
}
