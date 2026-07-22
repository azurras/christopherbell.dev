package dev.christopherbell.sharedfolder.service;

import java.util.List;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;
import org.springframework.data.mongodb.repository.Update;

/** Bounded owner-scoped access to unfinished conditional replacements. */
public interface SharedFolderMutationRecoveryRepository
    extends MongoRepository<SharedFolderMutationRecovery, String> {
  List<SharedFolderMutationRecovery> findTop100ByOwnerIdOrderByUpdatedAtAsc(String ownerId);
  List<SharedFolderMutationRecovery> findTop100ByOrderByUpdatedAtAsc();

  /** Extends only the exact current writer's lease without advancing the document version. */
  @Query("{ '_id': ?0, 'operationLeaseToken': ?1, 'state': ?2 }")
  @Update("{ '$set': { 'operationLeaseExpiresAt': ?3, 'updatedAt': ?4 } }")
  long renewOperationLease(
      String id,
      String operationLeaseToken,
      SharedFolderMutationRecoveryState state,
      java.time.Instant operationLeaseExpiresAt,
      java.time.Instant updatedAt);

  /** Atomically transfers one exact expired mutation lease to a single reconciler. */
  @Query("{ '_id': ?0, 'operationLeaseToken': ?1, 'state': ?2, '$or': ["
      + "{ 'operationLeaseExpiresAt': { '$lte': ?3 } }, { 'operationLeaseExpiresAt': null }] }")
  @Update("{ '$set': { 'operationLeaseToken': ?4, 'operationLeaseExpiresAt': ?5, "
      + "'updatedAt': ?6 }, '$inc': { 'version': 1 } }")
  long claimExpiredOperationLease(
      String id,
      String expiredOperationLeaseToken,
      SharedFolderMutationRecoveryState state,
      java.time.Instant expiredAtOrBefore,
      String recoveryOperationLeaseToken,
      java.time.Instant recoveryOperationLeaseExpiresAt,
      java.time.Instant updatedAt);
}
