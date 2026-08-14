package dev.christopherbell.sharedfolder.service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Bounded owner-scoped access to unfinished conditional replacements. */
public interface SharedFolderMutationRecoveryRepository {
  SharedFolderMutationRecovery save(SharedFolderMutationRecovery recovery);
  Optional<SharedFolderMutationRecovery> findById(String id);
  void deleteById(String id);
  List<SharedFolderMutationRecovery> findTop100ByOwnerIdOrderByUpdatedAtAsc(String ownerId);
  List<SharedFolderMutationRecovery> findTop100ByOrderByUpdatedAtAsc();

  /** Issues the initial deadline for one persisted, not-yet-owned mutation intent. */
  Optional<Instant> acquireOperationLease(
      String id,
      String operationLeaseToken,
      SharedFolderMutationRecoveryState state,
      Duration duration);

  /** Extends only the exact current writer's lease without advancing the document version. */
  Optional<Instant> renewOperationLease(
      String id,
      String operationLeaseToken,
      SharedFolderMutationRecoveryState state,
      Duration duration);

  /** Atomically transfers one exact expired mutation lease to a single reconciler. */
  Optional<Instant> claimExpiredOperationLease(
      String id,
      String expiredOperationLeaseToken,
      SharedFolderMutationRecoveryState state,
      String recoveryOperationLeaseToken,
      Duration duration);
}
