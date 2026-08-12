package dev.christopherbell.sharedfolder.upload;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;

/** Repository for owned resumable-upload metadata; payload bytes remain on private disk staging. */
public interface SharedFolderUploadSessionRepository {
  SharedFolderUploadSession save(SharedFolderUploadSession session);
  Optional<SharedFolderUploadSession> findById(String id);
  void deleteById(String id);

  long countByOwnerIdAndStateIn(
      String ownerId, Collection<SharedFolderUploadState> states);

  Slice<SharedFolderUploadSession> findByOwnerIdOrderByIdAsc(String ownerId, Pageable pageable);

  /** Returns only expired ACTIVE work or EXPIRED cleanup whose durable retry is due. */
  Slice<SharedFolderUploadSession> findDueForMaintenance(
      Instant dueAtOrBefore, Pageable pageable);

  /** Atomically closes only an untouched ACTIVE session whose deadline has elapsed. */
  long expireActive(String id, Instant expiresAtOrBefore, Instant updatedAt);

  /** Defers one exact EXPIRED cleanup attempt without allowing stale writers to overwrite it. */
  long deferExpiredMaintenance(
      String id,
      int expectedAttempts,
      Instant retryAt,
      int newAttempts,
      Instant updatedAt);

  /** Extends only the exact FINALIZING writer and phase, advancing its optimistic-lock version. */
  long renewFinalizationLease(
      String id,
      String finalizationLeaseToken,
      SharedFolderUploadFinalizationState finalizationState,
      java.time.Instant finalizationLeaseExpiresAt,
      java.time.Instant updatedAt);

  /** Atomically transfers one exact expired FINALIZING lease to a single reconciler. */
  long claimExpiredFinalizationLease(
      String id,
      String expiredFinalizationLeaseToken,
      SharedFolderUploadFinalizationState finalizationState,
      java.time.Instant expiredAtOrBefore,
      String recoveryFinalizationLeaseToken,
      java.time.Instant recoveryFinalizationLeaseExpiresAt,
      java.time.Instant updatedAt);

  /** Extends only the exact APPENDING writer and offset, advancing its optimistic-lock version. */
  long renewAppendLease(
      String id,
      String appendLeaseToken,
      long appendOffset,
      java.time.Instant appendLeaseExpiresAt,
      java.time.Instant updatedAt);

  /** Atomically transfers one exact expired APPENDING lease to a single reconciler. */
  long claimExpiredAppendLease(
      String id,
      String expiredAppendLeaseToken,
      long appendOffset,
      java.time.Instant expiredAtOrBefore,
      String recoveryAppendLeaseToken,
      java.time.Instant recoveryAppendLeaseExpiresAt,
      java.time.Instant updatedAt);
}
