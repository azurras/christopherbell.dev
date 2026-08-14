package dev.christopherbell.sharedfolder.upload;

import java.time.Duration;
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

  /** Issues the initial APPENDING deadline after the durable intent is persisted. */
  Optional<Instant> acquireAppendLease(
      String id, String appendLeaseToken, long appendOffset, Duration duration);

  /** Issues the initial FINALIZING deadline after the durable intent is persisted. */
  Optional<Instant> acquireFinalizationLease(
      String id,
      String finalizationLeaseToken,
      SharedFolderUploadFinalizationState finalizationState,
      Duration duration);

  /** Relinquishes the exact live APPENDING writer at database time for retry reconciliation. */
  boolean relinquishAppendLease(String id, String appendLeaseToken, long appendOffset);

  /** Relinquishes the exact live FINALIZING writer at database time for retry reconciliation. */
  boolean relinquishFinalizationLease(
      String id,
      String finalizationLeaseToken,
      SharedFolderUploadFinalizationState finalizationState);

  /** Extends only the exact FINALIZING writer and phase, advancing its optimistic-lock version. */
  Optional<Instant> renewFinalizationLease(
      String id,
      String finalizationLeaseToken,
      SharedFolderUploadFinalizationState finalizationState,
      Duration duration);

  /** Atomically transfers one exact expired FINALIZING lease to a single reconciler. */
  Optional<Instant> claimExpiredFinalizationLease(
      String id,
      String expiredFinalizationLeaseToken,
      SharedFolderUploadFinalizationState finalizationState,
      String recoveryFinalizationLeaseToken,
      Duration duration);

  /** Extends only the exact APPENDING writer and offset, advancing its optimistic-lock version. */
  Optional<Instant> renewAppendLease(
      String id,
      String appendLeaseToken,
      long appendOffset,
      Duration duration);

  /** Atomically transfers one exact expired APPENDING lease to a single reconciler. */
  Optional<Instant> claimExpiredAppendLease(
      String id,
      String expiredAppendLeaseToken,
      long appendOffset,
      String recoveryAppendLeaseToken,
      Duration duration);
}
