package dev.christopherbell.sharedfolder.upload;

import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;
import org.springframework.data.mongodb.repository.Update;

/** Repository for owned resumable-upload metadata; payload bytes remain on private disk staging. */
public interface SharedFolderUploadSessionRepository
    extends MongoRepository<SharedFolderUploadSession, String> {

  /** Extends only the exact FINALIZING writer and phase without advancing document version. */
  @Query("{ '_id': ?0, 'state': 'FINALIZING', 'finalizationLeaseToken': ?1, 'finalizationState': ?2 }")
  @Update("{ '$set': { 'finalizationLeaseExpiresAt': ?3, 'updatedAt': ?4 } }")
  long renewFinalizationLease(
      String id,
      String finalizationLeaseToken,
      SharedFolderUploadFinalizationState finalizationState,
      java.time.Instant finalizationLeaseExpiresAt,
      java.time.Instant updatedAt);

  /** Atomically transfers one exact expired FINALIZING lease to a single reconciler. */
  @Query("{ '_id': ?0, 'state': 'FINALIZING', 'finalizationLeaseToken': ?1, "
      + "'finalizationState': ?2, '$or': ["
      + "{ 'finalizationLeaseExpiresAt': { '$lte': ?3 } }, "
      + "{ 'finalizationLeaseExpiresAt': null }] }")
  @Update("{ '$set': { 'finalizationLeaseToken': ?4, 'finalizationLeaseExpiresAt': ?5, "
      + "'updatedAt': ?6 }, '$inc': { 'version': 1 } }")
  long claimExpiredFinalizationLease(
      String id,
      String expiredFinalizationLeaseToken,
      SharedFolderUploadFinalizationState finalizationState,
      java.time.Instant expiredAtOrBefore,
      String recoveryFinalizationLeaseToken,
      java.time.Instant recoveryFinalizationLeaseExpiresAt,
      java.time.Instant updatedAt);

  /** Extends only the exact APPENDING writer and offset without advancing document version. */
  @Query("{ '_id': ?0, 'state': 'APPENDING', 'appendLeaseToken': ?1, 'appendOffset': ?2 }")
  @Update("{ '$set': { 'appendLeaseExpiresAt': ?3, 'updatedAt': ?4 } }")
  long renewAppendLease(
      String id,
      String appendLeaseToken,
      long appendOffset,
      java.time.Instant appendLeaseExpiresAt,
      java.time.Instant updatedAt);

  /** Atomically transfers one exact expired APPENDING lease to a single reconciler. */
  @Query("{ '_id': ?0, 'state': 'APPENDING', 'appendLeaseToken': ?1, 'appendOffset': ?2, "
      + "'appendLeaseExpiresAt': { '$lte': ?3 } }")
  @Update("{ '$set': { 'appendLeaseToken': ?4, 'appendLeaseExpiresAt': ?5, 'updatedAt': ?6 }, "
      + "'$inc': { 'version': 1 } }")
  long claimExpiredAppendLease(
      String id,
      String expiredAppendLeaseToken,
      long appendOffset,
      java.time.Instant expiredAtOrBefore,
      String recoveryAppendLeaseToken,
      java.time.Instant recoveryAppendLeaseExpiresAt,
      java.time.Instant updatedAt);
}
