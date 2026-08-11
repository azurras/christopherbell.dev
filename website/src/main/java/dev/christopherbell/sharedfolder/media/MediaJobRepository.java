package dev.christopherbell.sharedfolder.media;

import java.time.Instant;
import java.util.Collection;
import java.util.Optional;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;

/** Durable admission and ownership queries for media jobs. */
public interface MediaJobRepository {
  MediaJob save(MediaJob job);
  Optional<MediaJob> findById(String id);
  void deleteById(String id);
  Optional<MediaJob> findFirstByCacheKeyAndStatusOrderByUpdatedAtDesc(
      String cacheKey, MediaJobStatus status);

  Optional<MediaJob> findFirstByCacheKeyAndStatusInOrderByCreatedAtAsc(
      String cacheKey, Collection<MediaJobStatus> statuses);

  long countByStatusIn(Collection<MediaJobStatus> statuses);

  long countByOwnerIdAndStatusIn(String ownerId, Collection<MediaJobStatus> statuses);

  Slice<MediaJob> findByOwnerIdOrderByIdAsc(String ownerId, Pageable pageable);

  List<MediaJob> findByStatusIn(Collection<MediaJobStatus> statuses);

  Optional<MediaJob> findFirstByStatusAndDescriptorPublishedFalseOrderByCreatedAtAsc(
      MediaJobStatus status);

  Optional<MediaJob> findFirstByDescriptorPublishedTrueAndStatusInOrderByCreatedAtAsc(
      Collection<MediaJobStatus> statuses);

  Slice<MediaJob> findByStatusOrderByLastAccessedAtAscIdAsc(
      MediaJobStatus status, Pageable pageable);

  Slice<MediaJob>
      findByStatusInAndCleanupAfterLessThanEqualAndArtifactsCleanedFalseOrderByCleanupAfterAscIdAsc(
          Collection<MediaJobStatus> statuses, Instant cleanupAfter, Pageable pageable);

  long cancelActive(String id, String ownerId, Instant updatedAt, Instant cleanupAfter);
}
