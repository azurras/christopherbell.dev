package dev.christopherbell.sharedfolder.service;

import java.util.List;
import java.util.Optional;

/** Bounded owner-scoped access to unfinished conditional replacements. */
public interface SharedFolderMutationRecoveryRepository {
  SharedFolderMutationRecovery save(SharedFolderMutationRecovery recovery);
  Optional<SharedFolderMutationRecovery> findById(String id);
  void deleteById(String id);
  List<SharedFolderMutationRecovery> findTop100ByOwnerIdOrderByUpdatedAtAsc(String ownerId);
  List<SharedFolderMutationRecovery> findTop100ByOrderByUpdatedAtAsc();

  /** Extends only the exact current writer's lease without advancing the document version. */
  long renewOperationLease(
      String id,
      String operationLeaseToken,
      SharedFolderMutationRecoveryState state,
      java.time.Instant operationLeaseExpiresAt,
      java.time.Instant updatedAt);

  /** Atomically transfers one exact expired mutation lease to a single reconciler. */
  long claimExpiredOperationLease(
      String id,
      String expiredOperationLeaseToken,
      SharedFolderMutationRecoveryState state,
      java.time.Instant expiredAtOrBefore,
      String recoveryOperationLeaseToken,
      java.time.Instant recoveryOperationLeaseExpiresAt,
      java.time.Instant updatedAt);
}
