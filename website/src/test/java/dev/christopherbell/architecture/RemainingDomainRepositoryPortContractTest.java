package dev.christopherbell.architecture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.withSettings;
import static org.mockito.Mockito.when;

import com.mongodb.client.result.DeleteResult;
import com.mongodb.client.result.UpdateResult;
import dev.christopherbell.canesboxtracker.CanesBoxPriceSnapshotRepository;
import dev.christopherbell.canesboxtracker.MongoCanesBoxPriceSnapshotRepository;
import dev.christopherbell.canesboxtracker.model.CanesBoxPriceSnapshot;
import dev.christopherbell.configuration.mongo.domain.DomainMongoOperationsFactory;
import dev.christopherbell.configuration.mongo.domain.KindScopedMongoOperations;
import dev.christopherbell.configuration.mongo.domain.MalformedDomainDocumentException;
import dev.christopherbell.location.model.ZipCoordinate;
import dev.christopherbell.location.model.ZipCoordinateImportState;
import dev.christopherbell.location.zip.MongoZipCoordinateImportStateRepository;
import dev.christopherbell.location.zip.MongoZipCoordinateRepository;
import dev.christopherbell.location.zip.ZipCoordinateImportStateRepository;
import dev.christopherbell.location.zip.ZipCoordinateRepository;
import dev.christopherbell.sharedfolder.audit.MongoSharedFolderAuditRepository;
import dev.christopherbell.sharedfolder.audit.SharedFolderAuditEvent;
import dev.christopherbell.sharedfolder.audit.SharedFolderAuditRepository;
import dev.christopherbell.sharedfolder.media.MediaJob;
import dev.christopherbell.sharedfolder.media.MediaJobRepository;
import dev.christopherbell.sharedfolder.media.MongoMediaJobRepository;
import dev.christopherbell.sharedfolder.radio.MongoSharedFolderRadioRepository;
import dev.christopherbell.sharedfolder.radio.SharedFolderRadioDocument;
import dev.christopherbell.sharedfolder.radio.SharedFolderRadioRepository;
import dev.christopherbell.sharedfolder.recycle.MongoSharedFolderRecycleRepository;
import dev.christopherbell.sharedfolder.recycle.SharedFolderRecycleItem;
import dev.christopherbell.sharedfolder.recycle.SharedFolderRecycleRepository;
import dev.christopherbell.sharedfolder.service.MongoSharedFolderMutationRecoveryRepository;
import dev.christopherbell.sharedfolder.service.SharedFolderMutationRecovery;
import dev.christopherbell.sharedfolder.service.SharedFolderMutationRecoveryRepository;
import dev.christopherbell.sharedfolder.service.SharedFolderMutationRecoveryState;
import dev.christopherbell.sharedfolder.upload.MongoSharedFolderUploadSessionRepository;
import dev.christopherbell.sharedfolder.upload.SharedFolderUploadSession;
import dev.christopherbell.sharedfolder.upload.SharedFolderUploadSessionRepository;
import dev.christopherbell.vehicle.core.MongoVehicleRepository;
import dev.christopherbell.vehicle.core.VehicleRepository;
import dev.christopherbell.vehicle.model.Vehicle;
import dev.christopherbell.vehicle.model.VehicleVinDecodeCache;
import dev.christopherbell.vehicle.nhtsa.decode.MongoVehicleVinDecodeCacheRepository;
import dev.christopherbell.vehicle.nhtsa.decode.VehicleVinDecodeCacheRepository;
import dev.christopherbell.vehicle.nhtsa.enrichment.MongoNhtsaVinImportStateRepository;
import dev.christopherbell.vehicle.nhtsa.enrichment.NhtsaVinImportStateRepository;
import dev.christopherbell.vehicle.nhtsa.model.NhtsaVinImportState;
import dev.christopherbell.vehicle.randomvin.importing.MongoRandomVinImportStateRepository;
import dev.christopherbell.vehicle.randomvin.importing.RandomVinImportStateRepository;
import dev.christopherbell.vehicle.randomvin.model.RandomVinImportState;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.time.Instant;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.mockito.invocation.Invocation;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

/** Freezes every Task 5 repository method and proves that each routes through its fixed kind. */
class RemainingDomainRepositoryPortContractTest {
  private static final Map<String, List<String>> FROZEN_INVOCATIONS = parseContracts("""
      CanesBoxPriceSnapshotRepository.findById(String) => findById(value)
      CanesBoxPriceSnapshotRepository.findTop60ByOrderByWeekStartDateDesc() => find(query=Document{{}};sort=Document{{}};skip=0;limit=0, page=0/60;offset=0;sort=weekStartDate: DESC)
      CanesBoxPriceSnapshotRepository.save(CanesBoxPriceSnapshot) => save(entity:CanesBoxPriceSnapshot)
      ZipCoordinateRepository.deleteAll(Iterable) => remove(query=Document{{id=Document{{$in=[75001, 75002]}}}};sort=Document{{}};skip=0;limit=0)
      ZipCoordinateRepository.findAllBySource(String) => find(query=Document{{source=value}};sort=Document{{}};skip=0;limit=0, page=unpaged)
      ZipCoordinateRepository.findById(String) => findById(value)
      ZipCoordinateRepository.saveAll(Iterable) => save(entity:ZipCoordinate#first) && save(entity:ZipCoordinate#second)
      ZipCoordinateImportStateRepository.findById(String) => findById(value)
      ZipCoordinateImportStateRepository.save(ZipCoordinateImportState) => save(entity:ZipCoordinateImportState)
      SharedFolderAuditRepository.save(SharedFolderAuditEvent) => save(entity:SharedFolderAuditEvent)
      SharedFolderAuditRepository.search(String,String,String,String,Instant,Instant,int) => find(query=Document{{$and=[Document{{accountId=value}}, Document{{action=value}}, Document{{outcome=value}}, Document{{relativePath=value}}, Document{{occurredAt=Document{{$gte=2026-08-11T00:00:00Z, $lte=2026-08-11T00:00:00Z}}}}]}};sort=Document{{}};skip=0;limit=0, page=0/1;offset=0;sort=occurredAt: DESC)
      MediaJobRepository.cancelActive(String,String,Instant,Instant) => updateFirst(query=Document{{id=value, ownerId=value, status=Document{{$in=[QUEUED, INSPECTING, TRANSCODING, BUFFERING]}}}};sort=Document{{}};skip=0;limit=0, update=Document{{$set=Document{{status=CANCELED, updatedAt=2026-08-11T00:00:00Z, cleanupAfter=2026-08-11T00:00:00Z, artifactsCleaned=false, descriptorPublished=false}}, $unset=Document{{activeCacheKey=1, deleteAt=1}}}})
      MediaJobRepository.countByOwnerIdAndStatusIn(String,Collection) => count(query=Document{{ownerId=value, status=Document{{$in=[QUEUED, INSPECTING]}}}};sort=Document{{}};skip=0;limit=0)
      MediaJobRepository.countByStatusIn(Collection) => count(query=Document{{status=Document{{$in=[QUEUED, INSPECTING]}}}};sort=Document{{}};skip=0;limit=0)
      MediaJobRepository.deleteById(String) => remove(query=Document{{id=value}};sort=Document{{}};skip=0;limit=0)
      MediaJobRepository.findById(String) => findById(value)
      MediaJobRepository.findByOwnerIdOrderByIdAsc(String,Pageable) => find(query=Document{{ownerId=value}};sort=Document{{id=1}};skip=0;limit=11, page=unpaged)
      MediaJobRepository.findByStatusIn(Collection) => find(query=Document{{status=Document{{$in=[QUEUED, INSPECTING]}}}};sort=Document{{}};skip=0;limit=0, page=unpaged)
      MediaJobRepository.findByStatusInAndCleanupAfterLessThanEqualAndArtifactsCleanedFalseOrderByCleanupAfterAscIdAsc(Collection,Instant,Pageable) => find(query=Document{{status=Document{{$in=[QUEUED, INSPECTING]}}, cleanupAfter=Document{{$lte=2026-08-11T00:00:00Z}}, artifactsCleaned=false}};sort=Document{{cleanupAfter=1, id=1}};skip=0;limit=11, page=unpaged)
      MediaJobRepository.findByStatusOrderByLastAccessedAtAscIdAsc(MediaJobStatus,Pageable) => find(query=Document{{status=QUEUED}};sort=Document{{lastAccessedAt=1, id=1}};skip=0;limit=11, page=unpaged)
      MediaJobRepository.findFirstByCacheKeyAndStatusInOrderByCreatedAtAsc(String,Collection) => findOne(query=Document{{cacheKey=value, status=Document{{$in=[QUEUED, INSPECTING]}}}};sort=Document{{createdAt=1}};skip=0;limit=1)
      MediaJobRepository.findFirstByCacheKeyAndStatusOrderByUpdatedAtDesc(String,MediaJobStatus) => findOne(query=Document{{cacheKey=value, status=QUEUED}};sort=Document{{updatedAt=-1}};skip=0;limit=1)
      MediaJobRepository.findFirstByDescriptorPublishedTrueAndStatusInOrderByCreatedAtAsc(Collection) => findOne(query=Document{{descriptorPublished=true, status=Document{{$in=[QUEUED, INSPECTING]}}}};sort=Document{{createdAt=1}};skip=0;limit=1)
      MediaJobRepository.findFirstByStatusAndDescriptorPublishedFalseOrderByCreatedAtAsc(MediaJobStatus) => findOne(query=Document{{status=QUEUED, descriptorPublished=false}};sort=Document{{createdAt=1}};skip=0;limit=1)
      MediaJobRepository.save(MediaJob) => save(entity:MediaJob)
      SharedFolderRadioRepository.findById(String) => findById(value)
      SharedFolderRadioRepository.save(SharedFolderRadioDocument) => save(entity:SharedFolderRadioDocument)
      SharedFolderRecycleRepository.deleteById(String) => remove(query=Document{{id=value}};sort=Document{{}};skip=0;limit=0)
      SharedFolderRecycleRepository.findById(String) => findById(value)
      SharedFolderRecycleRepository.findByStateAndExpiresAtBeforeAndRetryAfterLessThanEqualOrderByExpiresAtAscIdAsc(SharedFolderRecycleState,Instant,Instant,Pageable) => find(query=Document{{state=PREPARING, expiresAt=Document{{$lt=2026-08-11T00:00:00Z}}, retryAfter=Document{{$lte=2026-08-11T00:00:00Z}}}};sort=Document{{expiresAt=1, id=1}};skip=0;limit=0, page=0/10;offset=0;sort=UNSORTED)
      SharedFolderRecycleRepository.findByStateInAndRetryAfterLessThanEqualOrderByDeletedAtAscIdAsc(List,Instant,Pageable) => find(query=Document{{state=Document{{$in=[PREPARING, RECYCLED]}}, retryAfter=Document{{$lte=2026-08-11T00:00:00Z}}}};sort=Document{{deletedAt=1, id=1}};skip=0;limit=0, page=0/10;offset=0;sort=UNSORTED)
      SharedFolderRecycleRepository.findByStateOrderByDeletedAtDescIdDesc(SharedFolderRecycleState,Pageable) => find(query=Document{{state=PREPARING}};sort=Document{{deletedAt=-1, id=-1}};skip=0;limit=11, page=unpaged)
      SharedFolderRecycleRepository.save(SharedFolderRecycleItem) => save(entity:SharedFolderRecycleItem)
      SharedFolderMutationRecoveryRepository.claimExpiredOperationLease(String,String,SharedFolderMutationRecoveryState,Instant,String,Instant,Instant) => updateFirst(query=Document{{id=value, operationLeaseToken=value, state=PREPARED, $and=[Document{{$or=[Document{{operationLeaseExpiresAt=Document{{$lte=2026-08-11T00:00:00Z}}}}, Document{{operationLeaseExpiresAt=null}}]}}]}};sort=Document{{}};skip=0;limit=0, update=Document{{$set=Document{{operationLeaseToken=value, operationLeaseExpiresAt=2026-08-11T00:00:00Z, updatedAt=2026-08-11T00:00:00Z}}}})
      SharedFolderMutationRecoveryRepository.deleteById(String) => remove(query=Document{{id=value}};sort=Document{{}};skip=0;limit=0)
      SharedFolderMutationRecoveryRepository.findById(String) => findById(value)
      SharedFolderMutationRecoveryRepository.findTop100ByOrderByUpdatedAtAsc() => find(query=Document{{}};sort=Document{{}};skip=0;limit=0, page=0/100;offset=0;sort=updatedAt: ASC)
      SharedFolderMutationRecoveryRepository.findTop100ByOwnerIdOrderByUpdatedAtAsc(String) => find(query=Document{{ownerId=value}};sort=Document{{}};skip=0;limit=0, page=0/100;offset=0;sort=updatedAt: ASC)
      SharedFolderMutationRecoveryRepository.renewOperationLease(String,String,SharedFolderMutationRecoveryState,Instant,Instant) => updateHeartbeatPreservingVersion(query=Document{{id=value, operationLeaseToken=value, state=PREPARED}};sort=Document{{}};skip=0;limit=0, update=Document{{$set=Document{{operationLeaseExpiresAt=2026-08-11T00:00:00Z, updatedAt=2026-08-11T00:00:00Z}}}})
      SharedFolderMutationRecoveryRepository.save(SharedFolderMutationRecovery) => save(entity:SharedFolderMutationRecovery)
      SharedFolderUploadSessionRepository.claimExpiredAppendLease(String,String,long,Instant,String,Instant,Instant) => updateFirst(query=Document{{id=value, state=APPENDING, appendLeaseToken=value, appendOffset=1, appendLeaseExpiresAt=Document{{$lte=2026-08-11T00:00:00Z}}}};sort=Document{{}};skip=0;limit=0, update=Document{{$set=Document{{appendLeaseToken=value, appendLeaseExpiresAt=2026-08-11T00:00:00Z, updatedAt=2026-08-11T00:00:00Z}}}})
      SharedFolderUploadSessionRepository.claimExpiredFinalizationLease(String,String,SharedFolderUploadFinalizationState,Instant,String,Instant,Instant) => updateFirst(query=Document{{id=value, state=FINALIZING, finalizationLeaseToken=value, finalizationState=PREPARED, $and=[Document{{$or=[Document{{finalizationLeaseExpiresAt=Document{{$lte=2026-08-11T00:00:00Z}}}}, Document{{finalizationLeaseExpiresAt=null}}]}}]}};sort=Document{{}};skip=0;limit=0, update=Document{{$set=Document{{finalizationLeaseToken=value, finalizationLeaseExpiresAt=2026-08-11T00:00:00Z, updatedAt=2026-08-11T00:00:00Z}}}})
      SharedFolderUploadSessionRepository.countByOwnerIdAndStateIn(String,Collection) => count(query=Document{{ownerId=value, state=Document{{$in=[ACTIVE, APPENDING]}}}};sort=Document{{}};skip=0;limit=0)
      SharedFolderUploadSessionRepository.deferExpiredMaintenance(String,int,Instant,int,Instant) => updateFirst(query=Document{{id=value, state=EXPIRED, $and=[Document{{$or=[Document{{maintenanceAttempts=1}}, Document{{maintenanceAttempts=Document{{$exists=false}}}}]}}]}};sort=Document{{}};skip=0;limit=0, update=Document{{$set=Document{{maintenanceRetryAt=2026-08-11T00:00:00Z, maintenanceAttempts=1, updatedAt=2026-08-11T00:00:00Z}}}})
      SharedFolderUploadSessionRepository.deleteById(String) => remove(query=Document{{id=value}};sort=Document{{}};skip=0;limit=0)
      SharedFolderUploadSessionRepository.expireActive(String,Instant,Instant) => updateFirst(query=Document{{id=value, state=ACTIVE, expiresAt=Document{{$lte=2026-08-11T00:00:00Z}}}};sort=Document{{}};skip=0;limit=0, update=Document{{$set=Document{{state=EXPIRED, maintenanceRetryAt=2026-08-11T00:00:00Z, maintenanceAttempts=0, updatedAt=2026-08-11T00:00:00Z}}}})
      SharedFolderUploadSessionRepository.findById(String) => findById(value)
      SharedFolderUploadSessionRepository.findByOwnerIdOrderByIdAsc(String,Pageable) => find(query=Document{{ownerId=value}};sort=Document{{id=1}};skip=0;limit=11, page=unpaged)
      SharedFolderUploadSessionRepository.findDueForMaintenance(Instant,Pageable) => find(query=Document{{$or=[Document{{state=ACTIVE, expiresAt=Document{{$lte=2026-08-11T00:00:00Z}}}}, Document{{state=EXPIRED, $and=[Document{{$or=[Document{{maintenanceRetryAt=Document{{$lte=2026-08-11T00:00:00Z}}}}, Document{{maintenanceRetryAt=null}}]}}]}}]}};sort=Document{{}};skip=0;limit=11, page=unpaged)
      SharedFolderUploadSessionRepository.renewAppendLease(String,String,long,Instant,Instant) => updateFirst(query=Document{{id=value, state=APPENDING, appendLeaseToken=value, appendOffset=1}};sort=Document{{}};skip=0;limit=0, update=Document{{$set=Document{{appendLeaseExpiresAt=2026-08-11T00:00:00Z, updatedAt=2026-08-11T00:00:00Z}}}})
      SharedFolderUploadSessionRepository.renewFinalizationLease(String,String,SharedFolderUploadFinalizationState,Instant,Instant) => updateFirst(query=Document{{id=value, state=FINALIZING, finalizationLeaseToken=value, finalizationState=PREPARED}};sort=Document{{}};skip=0;limit=0, update=Document{{$set=Document{{finalizationLeaseExpiresAt=2026-08-11T00:00:00Z, updatedAt=2026-08-11T00:00:00Z}}}})
      SharedFolderUploadSessionRepository.save(SharedFolderUploadSession) => save(entity:SharedFolderUploadSession)
      VehicleRepository.delete(Vehicle) => remove(query=Document{{id=vehicle-a}};sort=Document{{}};skip=0;limit=0)
      VehicleRepository.existsByVin(String) => exists(query=Document{{vin=value}};sort=Document{{}};skip=0;limit=0)
      VehicleRepository.findAllByOrderByMakeAscModelAscYearDesc() => find(query=Document{{}};sort=Document{{make=1, model=1, year=-1}};skip=0;limit=0, page=unpaged)
      VehicleRepository.findById(String) => findById(value)
      VehicleRepository.findByMakeIgnoreCase(String) => find(query=Document{{make=^\\Qvalue\\E$}};sort=Document{{}};skip=0;limit=0, page=unpaged)
      VehicleRepository.findByNotes(String) => find(query=Document{{notes=value}};sort=Document{{}};skip=0;limit=0, page=unpaged)
      VehicleRepository.findByVinIsNotNull() => find(query=Document{{vin=Document{{$ne=null}}}};sort=Document{{}};skip=0;limit=0, page=unpaged)
      VehicleRepository.save(Vehicle) => save(entity:Vehicle)
      VehicleRepository.saveAll(Iterable) => save(entity:Vehicle#first) && save(entity:Vehicle#second)
      VehicleVinDecodeCacheRepository.findById(String) => findById(value)
      VehicleVinDecodeCacheRepository.save(VehicleVinDecodeCache) => save(entity:VehicleVinDecodeCache)
      NhtsaVinImportStateRepository.findById(String) => findById(value)
      NhtsaVinImportStateRepository.save(NhtsaVinImportState) => save(entity:NhtsaVinImportState)
      RandomVinImportStateRepository.findById(String) => findById(value)
      RandomVinImportStateRepository.save(RandomVinImportState) => save(entity:RandomVinImportState)
      """);
  private static final Map<String, List<String>> EMPTY_COLLECTION_INVOCATIONS = parseContracts("""
      MediaJobRepository.countByOwnerIdAndStatusIn(String,Collection) => count(query=Document{{ownerId=value, status=Document{{$in=[]}}}};sort=Document{{}};skip=0;limit=0)
      MediaJobRepository.countByStatusIn(Collection) => count(query=Document{{status=Document{{$in=[]}}}};sort=Document{{}};skip=0;limit=0)
      MediaJobRepository.findByStatusIn(Collection) => find(query=Document{{status=Document{{$in=[]}}}};sort=Document{{}};skip=0;limit=0, page=unpaged)
      MediaJobRepository.findByStatusInAndCleanupAfterLessThanEqualAndArtifactsCleanedFalseOrderByCleanupAfterAscIdAsc(Collection,Instant,Pageable) => find(query=Document{{status=Document{{$in=[]}}, cleanupAfter=Document{{$lte=2026-08-11T00:00:00Z}}, artifactsCleaned=false}};sort=Document{{cleanupAfter=1, id=1}};skip=0;limit=11, page=unpaged)
      MediaJobRepository.findFirstByCacheKeyAndStatusInOrderByCreatedAtAsc(String,Collection) => findOne(query=Document{{cacheKey=value, status=Document{{$in=[]}}}};sort=Document{{createdAt=1}};skip=0;limit=1)
      MediaJobRepository.findFirstByDescriptorPublishedTrueAndStatusInOrderByCreatedAtAsc(Collection) => findOne(query=Document{{descriptorPublished=true, status=Document{{$in=[]}}}};sort=Document{{createdAt=1}};skip=0;limit=1)
      SharedFolderRecycleRepository.findByStateInAndRetryAfterLessThanEqualOrderByDeletedAtAscIdAsc(List,Instant,Pageable) => find(query=Document{{state=Document{{$in=[]}}, retryAfter=Document{{$lte=2026-08-11T00:00:00Z}}}};sort=Document{{deletedAt=1, id=1}};skip=0;limit=0, page=0/10;offset=0;sort=UNSORTED)
      SharedFolderUploadSessionRepository.countByOwnerIdAndStateIn(String,Collection) => count(query=Document{{ownerId=value, state=Document{{$in=[]}}}};sort=Document{{}};skip=0;limit=0)
      """);

  private static final List<AdapterDefinition> ADAPTERS = List.of(
      adapter(CanesBoxPriceSnapshotRepository.class, MongoCanesBoxPriceSnapshotRepository.class,
          CanesBoxPriceSnapshot.class, "findById(String)", "findTop60ByOrderByWeekStartDateDesc()",
          "save(CanesBoxPriceSnapshot)"),
      adapter(ZipCoordinateRepository.class, MongoZipCoordinateRepository.class,
          ZipCoordinate.class, "deleteAll(Iterable)", "findAllBySource(String)",
          "findById(String)", "saveAll(Iterable)"),
      adapter(ZipCoordinateImportStateRepository.class,
          MongoZipCoordinateImportStateRepository.class, ZipCoordinateImportState.class,
          "findById(String)", "save(ZipCoordinateImportState)"),
      adapter(SharedFolderAuditRepository.class, MongoSharedFolderAuditRepository.class,
          SharedFolderAuditEvent.class, "save(SharedFolderAuditEvent)",
          "search(String,String,String,String,Instant,Instant,int)"),
      adapter(MediaJobRepository.class, MongoMediaJobRepository.class, MediaJob.class,
          "cancelActive(String,String,Instant,Instant)", "countByOwnerIdAndStatusIn(String,Collection)",
          "countByStatusIn(Collection)", "deleteById(String)", "findById(String)",
          "findByOwnerIdOrderByIdAsc(String,Pageable)", "findByStatusIn(Collection)",
          "findByStatusInAndCleanupAfterLessThanEqualAndArtifactsCleanedFalseOrderByCleanupAfterAscIdAsc(Collection,Instant,Pageable)",
          "findByStatusOrderByLastAccessedAtAscIdAsc(MediaJobStatus,Pageable)",
          "findFirstByCacheKeyAndStatusInOrderByCreatedAtAsc(String,Collection)",
          "findFirstByCacheKeyAndStatusOrderByUpdatedAtDesc(String,MediaJobStatus)",
          "findFirstByDescriptorPublishedTrueAndStatusInOrderByCreatedAtAsc(Collection)",
          "findFirstByStatusAndDescriptorPublishedFalseOrderByCreatedAtAsc(MediaJobStatus)",
          "save(MediaJob)"),
      adapter(SharedFolderRadioRepository.class, MongoSharedFolderRadioRepository.class,
          SharedFolderRadioDocument.class, "findById(String)", "save(SharedFolderRadioDocument)"),
      adapter(SharedFolderRecycleRepository.class, MongoSharedFolderRecycleRepository.class,
          SharedFolderRecycleItem.class, "deleteById(String)", "findById(String)",
          "findByStateAndExpiresAtBeforeAndRetryAfterLessThanEqualOrderByExpiresAtAscIdAsc(SharedFolderRecycleState,Instant,Instant,Pageable)",
          "findByStateInAndRetryAfterLessThanEqualOrderByDeletedAtAscIdAsc(List,Instant,Pageable)",
          "findByStateOrderByDeletedAtDescIdDesc(SharedFolderRecycleState,Pageable)",
          "save(SharedFolderRecycleItem)"),
      adapter(SharedFolderMutationRecoveryRepository.class,
          MongoSharedFolderMutationRecoveryRepository.class, SharedFolderMutationRecovery.class,
          "claimExpiredOperationLease(String,String,SharedFolderMutationRecoveryState,Instant,String,Instant,Instant)",
          "deleteById(String)", "findById(String)", "findTop100ByOrderByUpdatedAtAsc()",
          "findTop100ByOwnerIdOrderByUpdatedAtAsc(String)",
          "renewOperationLease(String,String,SharedFolderMutationRecoveryState,Instant,Instant)",
          "save(SharedFolderMutationRecovery)"),
      adapter(SharedFolderUploadSessionRepository.class,
          MongoSharedFolderUploadSessionRepository.class, SharedFolderUploadSession.class,
          "claimExpiredAppendLease(String,String,long,Instant,String,Instant,Instant)",
          "claimExpiredFinalizationLease(String,String,SharedFolderUploadFinalizationState,Instant,String,Instant,Instant)",
          "countByOwnerIdAndStateIn(String,Collection)",
          "deferExpiredMaintenance(String,int,Instant,int,Instant)", "deleteById(String)",
          "expireActive(String,Instant,Instant)", "findById(String)",
          "findByOwnerIdOrderByIdAsc(String,Pageable)", "findDueForMaintenance(Instant,Pageable)",
          "renewAppendLease(String,String,long,Instant,Instant)",
          "renewFinalizationLease(String,String,SharedFolderUploadFinalizationState,Instant,Instant)",
          "save(SharedFolderUploadSession)"),
      adapter(VehicleRepository.class, MongoVehicleRepository.class, Vehicle.class,
          "delete(Vehicle)", "existsByVin(String)",
          "findAllByOrderByMakeAscModelAscYearDesc()", "findById(String)",
          "findByMakeIgnoreCase(String)", "findByNotes(String)", "findByVinIsNotNull()",
          "save(Vehicle)", "saveAll(Iterable)"),
      adapter(VehicleVinDecodeCacheRepository.class,
          MongoVehicleVinDecodeCacheRepository.class, VehicleVinDecodeCache.class,
          "findById(String)", "save(VehicleVinDecodeCache)"),
      adapter(NhtsaVinImportStateRepository.class, MongoNhtsaVinImportStateRepository.class,
          NhtsaVinImportState.class, "findById(String)", "save(NhtsaVinImportState)"),
      adapter(RandomVinImportStateRepository.class, MongoRandomVinImportStateRepository.class,
          RandomVinImportState.class, "findById(String)", "save(RandomVinImportState)"));

  @Test
  void everyRemainingPortHasExactlyOneExplicitKindScopedAdapterAndFrozenInventory() {
    assertThat(ADAPTERS).hasSize(13);
    ADAPTERS.forEach(definition -> {
      assertThat(definition.port().isInterface()).isTrue();
      assertThat(definition.adapter().getInterfaces()).contains(definition.port());
      assertThat(signatures(definition.port())).isEqualTo(definition.expectedMethods());
      assertThat(signatures(definition.adapter())).containsAll(definition.expectedMethods());
    });
  }

  @Test
  void everyFrozenMethodCrossesTheKindScopedOperationsBoundary() throws Exception {
    assertThat(FROZEN_INVOCATIONS).hasSize(
        ADAPTERS.stream().mapToInt(definition -> definition.expectedMethods().size()).sum());
    for (var definition : ADAPTERS) {
      var returnedEntities = List.of(
          entityArgument(definition.entity(), "result-first"),
          entityArgument(definition.entity(), "result-second"));
      var operations = operationsMock(returnedEntities);
      var factory = mock(DomainMongoOperationsFactory.class);
      bind(factory, definition.entity(), operations);
      var repository = definition.adapter()
          .getConstructor(DomainMongoOperationsFactory.class)
          .newInstance(factory);

      var methods = List.of(definition.port().getDeclaredMethods()).stream()
          .sorted(Comparator.comparing(RemainingDomainRepositoryPortContractTest::signature))
          .toList();
      for (var method : methods) {
        int before = mockingDetails(operations).getInvocations().size();
        var methodArguments = arguments(method);
        var result = method.invoke(repository, methodArguments);
        var invocationSnapshots = mockingDetails(operations).getInvocations().stream()
            .skip(before)
            .map(RemainingDomainRepositoryPortContractTest::snapshot)
            .toList();
        var contractKey = definition.port().getSimpleName() + "." + signature(method);
        assertThat(invocationSnapshots)
            .as(contractKey)
            .containsExactlyElementsOf(FROZEN_INVOCATIONS.get(contractKey));
        assertResultContract(method, methodArguments, result, returnedEntities, contractKey);
        if (invocationSnapshots.getFirst().startsWith("update")) {
          assertStaleMutationIsReportedAsNoMatch(
              definition, method, contractKey, returnedEntities);
        }
      }
    }
  }

  @Test
  void everyEnumCollectionQueryPreservesTwoValuesAndAcceptsEmptyInput() throws Exception {
    var applicable = ADAPTERS.stream()
        .flatMap(definition -> java.util.Arrays.stream(definition.port().getDeclaredMethods())
            .filter(RemainingDomainRepositoryPortContractTest::hasEnumCollectionParameter)
            .map(method -> new BulkMethod(definition, method)))
        .toList();
    assertThat(applicable.stream().map(item ->
        item.definition().port().getSimpleName() + "." + signature(item.method())))
        .containsExactlyInAnyOrderElementsOf(EMPTY_COLLECTION_INVOCATIONS.keySet());

    for (var item : applicable) {
      var returnedEntities = List.of(
          entityArgument(item.definition().entity(), "result-first"),
          entityArgument(item.definition().entity(), "result-second"));
      var operations = operationsMock(returnedEntities);
      var repository = repository(item.definition(), operations);
      var methodArguments = arguments(item.method());
      for (int index = 0; index < item.method().getParameterCount(); index++) {
        if (Collection.class.isAssignableFrom(item.method().getParameterTypes()[index])) {
          methodArguments[index] = List.of();
        }
      }

      var result = item.method().invoke(repository, methodArguments);
      var contractKey = item.definition().port().getSimpleName() + "." + signature(item.method());
      assertThat(mockingDetails(operations).getInvocations().stream()
          .map(RemainingDomainRepositoryPortContractTest::snapshot))
          .as(contractKey)
          .containsExactlyElementsOf(EMPTY_COLLECTION_INVOCATIONS.get(contractKey));
      assertResultContract(item.method(), methodArguments, result, returnedEntities, contractKey);
    }
  }

  @Test
  void everyLookupResultPreservesExactStorageAbsence() throws Exception {
    var lookups = ADAPTERS.stream()
        .flatMap(definition -> java.util.Arrays.stream(definition.port().getDeclaredMethods())
            .filter(RemainingDomainRepositoryPortContractTest::returnsLookupResult)
            .filter(method -> FROZEN_INVOCATIONS.get(
                definition.port().getSimpleName() + "." + signature(method)).getFirst()
                .startsWith("find"))
            .map(method -> new BulkMethod(definition, method)))
        .toList();
    assertThat(lookups).hasSize(34);

    for (var item : lookups) {
      var operations = operationsMock(List.of());
      var repository = repository(item.definition(), operations);
      var methodArguments = arguments(item.method());
      var result = item.method().invoke(repository, methodArguments);
      var contractKey = item.definition().port().getSimpleName() + "." + signature(item.method());

      assertThat(mockingDetails(operations).getInvocations().stream()
          .map(RemainingDomainRepositoryPortContractTest::snapshot))
          .as(contractKey)
          .containsExactlyElementsOf(FROZEN_INVOCATIONS.get(contractKey));
      if (result instanceof Optional<?> optional) {
        assertThat(optional).as(contractKey).isEmpty();
      } else if (result instanceof Slice<?> slice) {
        assertThat(slice.getContent()).as(contractKey).isEmpty();
        assertThat(slice.hasNext()).as(contractKey).isFalse();
      } else {
        assertThat(result).as(contractKey).isEqualTo(List.of());
      }
    }
  }

  @Test
  void everyBulkPortPreservesTwoValuesAndPerformsNoWorkForEmptyInput() throws Exception {
    var bulkMethods = ADAPTERS.stream()
        .flatMap(definition -> java.util.Arrays.stream(definition.port().getDeclaredMethods())
            .filter(method -> method.getName().equals("saveAll")
                || method.getName().equals("deleteAll"))
            .map(method -> new BulkMethod(definition, method)))
        .toList();
    assertThat(bulkMethods).extracting(item -> signature(item.method()))
        .containsExactlyInAnyOrder("deleteAll(Iterable)", "saveAll(Iterable)",
            "saveAll(Iterable)");

    for (var item : bulkMethods) {
      var operations = operationsMock(List.of(
          entityArgument(item.definition().entity(), "unused-first"),
          entityArgument(item.definition().entity(), "unused-second")));
      var factory = mock(DomainMongoOperationsFactory.class);
      bind(factory, item.definition().entity(), operations);
      var repository = item.definition().adapter()
          .getConstructor(DomainMongoOperationsFactory.class)
          .newInstance(factory);

      var result = item.method().invoke(repository, List.of());

      assertThat(mockingDetails(operations).getInvocations())
          .as(item.definition().port().getSimpleName() + "." + signature(item.method()))
          .isEmpty();
      if (item.method().getReturnType() == void.class) {
        assertThat(result).isNull();
      } else {
        assertThat(result).isEqualTo(List.of());
      }
    }
  }

  @Test
  void representativeAdaptersPreserveTypedBoundaryFailuresAndCauses() {
    var saved = mock(CanesBoxPriceSnapshot.class);
    var saveOperations = typedOperations(CanesBoxPriceSnapshot.class);
    var duplicate = new DuplicateKeyException("Mongo domain identity already exists.");
    when(saveOperations.save(saved)).thenThrow(duplicate);
    assertThatThrownBy(() -> new MongoCanesBoxPriceSnapshotRepository(
        factory(CanesBoxPriceSnapshot.class, saveOperations)).save(saved))
        .isSameAs(duplicate);

    var findOperations = typedOperations(CanesBoxPriceSnapshot.class);
    var malformed = new MalformedDomainDocumentException();
    when(findOperations.findById("sensitive-id")).thenThrow(malformed);
    assertThatThrownBy(() -> new MongoCanesBoxPriceSnapshotRepository(
        factory(CanesBoxPriceSnapshot.class, findOperations)).findById("sensitive-id"))
        .isSameAs(malformed)
        .hasMessage("Mongo domain document is malformed.")
        .hasMessageNotContaining("sensitive-id");

    var deleteOperations = typedOperations(MediaJob.class);
    var rootCause = new IllegalStateException("socket closed");
    var infrastructure = new DataAccessResourceFailureException("Mongo unavailable", rootCause);
    when(deleteOperations.remove(any(Query.class))).thenThrow(infrastructure);
    var thrown = catchThrowable(() -> new MongoMediaJobRepository(
        factory(MediaJob.class, deleteOperations)).deleteById("job-a"));
    assertThat(thrown).isSameAs(infrastructure);
    assertThat(thrown.getCause()).isSameAs(rootCause);

    var mutationOperations = typedOperations(SharedFolderMutationRecovery.class);
    var stale = new OptimisticLockingFailureException(
        "Mongo domain document was changed by another writer.");
    when(mutationOperations.updateHeartbeatPreservingVersion(any(Query.class), any(Update.class)))
        .thenThrow(stale);
    assertThatThrownBy(() -> new MongoSharedFolderMutationRecoveryRepository(
        factory(SharedFolderMutationRecovery.class, mutationOperations))
        .renewOperationLease("recovery-a", "owner-a",
            SharedFolderMutationRecoveryState.PREPARED,
            Instant.parse("2026-08-11T00:01:00Z"),
            Instant.parse("2026-08-11T00:00:00Z")))
        .isSameAs(stale);
  }

  private static void assertStaleMutationIsReportedAsNoMatch(
      AdapterDefinition definition,
      Method method,
      String contractKey,
      List<Object> returnedEntities) throws Exception {
    var operations = operationsMock(returnedEntities, 0);
    var factory = mock(DomainMongoOperationsFactory.class);
    bind(factory, definition.entity(), operations);
    var repository = definition.adapter()
        .getConstructor(DomainMongoOperationsFactory.class)
        .newInstance(factory);
    assertThat(method.invoke(repository, arguments(method)))
        .as(contractKey + " stale mutation")
        .isEqualTo(0L);
  }

  private static void assertResultContract(
      Method method,
      Object[] methodArguments,
      Object result,
      List<Object> returnedEntities,
      String contractKey) {
    if (method.getReturnType() == void.class) {
      assertThat(result).as(contractKey).isNull();
    } else if (method.getReturnType() == boolean.class) {
      assertThat(result).as(contractKey).isEqualTo(true);
    } else if (method.getReturnType() == long.class) {
      assertThat(result).as(contractKey).isEqualTo(1L);
    } else if (method.getReturnType() == Optional.class) {
      assertThat(result).as(contractKey).isEqualTo(Optional.of(returnedEntities.getFirst()));
    } else if (Slice.class.isAssignableFrom(method.getReturnType())) {
      var slice = (Slice<?>) result;
      assertThat(slice.getContent()).as(contractKey).isEqualTo(returnedEntities);
      assertThat(slice.hasNext()).as(contractKey).isFalse();
    } else if (Collection.class.isAssignableFrom(method.getReturnType())) {
      var expected = method.getName().equals("saveAll")
          ? methodArguments[0]
          : returnedEntities;
      assertThat(result).as(contractKey).isEqualTo(expected);
    } else {
      assertThat(result).as(contractKey).isSameAs(methodArguments[0]);
    }
  }

  private static String snapshot(Invocation invocation) {
    return invocation.getMethod().getName() + java.util.Arrays.stream(invocation.getArguments())
        .map(RemainingDomainRepositoryPortContractTest::snapshotArgument)
        .collect(Collectors.joining(", ", "(", ")"));
  }

  private static String snapshotArgument(Object argument) {
    if (argument instanceof Query query) {
      return "query=" + query.getQueryObject()
          + ";sort=" + query.getSortObject()
          + ";skip=" + query.getSkip()
          + ";limit=" + query.getLimit();
    }
    if (argument instanceof Update update) {
      return "update=" + update.getUpdateObject();
    }
    if (argument instanceof Pageable pageable) {
      if (pageable.isUnpaged()) {
        return "page=unpaged";
      }
      return "page=" + pageable.getPageNumber() + "/" + pageable.getPageSize()
          + ";offset=" + pageable.getOffset() + ";sort=" + pageable.getSort();
    }
    if (argument instanceof Collection<?> collection) {
      return "collection=" + collection.stream().map(RemainingDomainRepositoryPortContractTest::snapshotArgument)
          .collect(Collectors.joining(",", "[", "]"));
    }
    if (argument == null || argument instanceof String || argument instanceof Number
        || argument instanceof Instant || argument.getClass().isEnum()) {
      return String.valueOf(argument);
    }
    var settings = mockingDetails(argument).getMockCreationSettings();
    var label = settings.getMockName().toString();
    var suffix = label.equals("first") || label.equals("second") ? "#" + label : "";
    return "entity:" + settings.getTypeToMock().getSimpleName() + suffix;
  }

  private static Set<String> signatures(Class<?> type) {
    return java.util.Arrays.stream(type.getDeclaredMethods())
        .map(RemainingDomainRepositoryPortContractTest::signature)
        .collect(Collectors.toSet());
  }

  private static String signature(Method method) {
    return method.getName() + java.util.Arrays.stream(method.getParameterTypes())
        .map(Class::getSimpleName)
        .collect(Collectors.joining(",", "(", ")"));
  }

  private static boolean hasEnumCollectionParameter(Method method) {
    for (int index = 0; index < method.getParameterCount(); index++) {
      if (!Collection.class.isAssignableFrom(method.getParameterTypes()[index])) {
        continue;
      }
      var parameter = (ParameterizedType) method.getGenericParameterTypes()[index];
      if (((Class<?>) parameter.getActualTypeArguments()[0]).isEnum()) {
        return true;
      }
    }
    return false;
  }

  private static boolean returnsLookupResult(Method method) {
    return method.getReturnType() == Optional.class
        || method.getReturnType() == List.class
        || Slice.class.isAssignableFrom(method.getReturnType());
  }

  private static Map<String, List<String>> parseContracts(String contracts) {
    return contracts.lines()
        .filter(line -> !line.isBlank())
        .map(String::strip)
        .map(line -> line.split(" => ", 2))
        .collect(Collectors.toUnmodifiableMap(
            fields -> fields[0], fields -> List.of(fields[1].split(" && "))));
  }

  private static Object[] arguments(Method method) {
    var types = method.getGenericParameterTypes();
    var arguments = new Object[types.length];
    for (int index = 0; index < types.length; index++) {
      arguments[index] = argument(types[index], method.getParameterTypes()[index]);
    }
    return arguments;
  }

  private static Object argument(Type genericType, Class<?> rawType) {
    if (rawType == String.class) return "value";
    if (rawType == Instant.class) return Instant.parse("2026-08-11T00:00:00Z");
    if (rawType == Pageable.class) return PageRequest.of(0, 10);
    if (rawType == int.class) return 1;
    if (rawType == long.class) return 1L;
    if (rawType.isEnum()) return rawType.getEnumConstants()[0];
    if (Collection.class.isAssignableFrom(rawType) || Iterable.class == rawType) {
      var elementType = (Class<?>) ((ParameterizedType) genericType).getActualTypeArguments()[0];
      if (elementType.isEnum()) {
        var constants = elementType.getEnumConstants();
        return List.of(constants[0], constants[1]);
      }
      return List.of(
          entityArgument(elementType, "first"),
          entityArgument(elementType, "second"));
    }
    return entityArgument(rawType, "value");
  }

  private static Object entityArgument(Class<?> type, String label) {
    if (type.isEnum()) return type.getEnumConstants()[0];
    var value = mock(type, withSettings().name(label));
    if (value instanceof ZipCoordinate coordinate) {
      when(coordinate.getZipCode()).thenReturn(label.equals("second") ? "75002" : "75001");
    }
    if (value instanceof Vehicle vehicle) {
      when(vehicle.getId()).thenReturn(label.equals("second") ? "vehicle-b" : "vehicle-a");
    }
    return value;
  }

  @SuppressWarnings({"rawtypes", "unchecked"})
  private static KindScopedMongoOperations<?> operationsMock(List<Object> returnedEntities) {
    return operationsMock(returnedEntities, 1);
  }

  @SuppressWarnings({"rawtypes", "unchecked"})
  private static KindScopedMongoOperations<?> operationsMock(
      List<Object> returnedEntities, long matchedCount) {
    var update = UpdateResult.acknowledged(matchedCount, matchedCount, null);
    return mock(KindScopedMongoOperations.class, invocation -> {
      var name = invocation.getMethod().getName();
      if (name.equals("save") || name.equals("insert")) return invocation.getArgument(0);
      var returnType = invocation.getMethod().getReturnType();
      if (returnType == Optional.class) {
        return returnedEntities.isEmpty()
            ? Optional.empty()
            : Optional.of(returnedEntities.getFirst());
      }
      if (returnType == List.class) return returnedEntities;
      if (returnType == long.class) return 1L;
      if (returnType == boolean.class) return true;
      if (returnType == UpdateResult.class) return update;
      if (returnType == DeleteResult.class) return DeleteResult.acknowledged(1);
      if (returnType == String.class) return "test";
      return null;
    });
  }

  @SuppressWarnings({"rawtypes", "unchecked"})
  private static <T> KindScopedMongoOperations<T> typedOperations(Class<T> entity) {
    return mock(KindScopedMongoOperations.class,
        withSettings().name(entity.getSimpleName() + "Operations"));
  }

  private static <T> DomainMongoOperationsFactory factory(
      Class<T> entity, KindScopedMongoOperations<T> operations) {
    var factory = mock(DomainMongoOperationsFactory.class);
    when(factory.forType(entity)).thenReturn(operations);
    return factory;
  }

  @SuppressWarnings({"rawtypes", "unchecked"})
  private static void bind(
      DomainMongoOperationsFactory factory,
      Class<?> entity,
      KindScopedMongoOperations<?> operations) {
    when(factory.forType((Class) entity)).thenReturn((KindScopedMongoOperations) operations);
  }

  private static Object repository(
      AdapterDefinition definition, KindScopedMongoOperations<?> operations) throws Exception {
    var factory = mock(DomainMongoOperationsFactory.class);
    bind(factory, definition.entity(), operations);
    return definition.adapter().getConstructor(DomainMongoOperationsFactory.class)
        .newInstance(factory);
  }

  private static AdapterDefinition adapter(
      Class<?> port, Class<?> adapter, Class<?> entity, String... expectedMethods) {
    return new AdapterDefinition(port, adapter, entity, Set.of(expectedMethods));
  }

  private record AdapterDefinition(
      Class<?> port, Class<?> adapter, Class<?> entity, Set<String> expectedMethods) {}

  private record BulkMethod(AdapterDefinition definition, Method method) {}
}
