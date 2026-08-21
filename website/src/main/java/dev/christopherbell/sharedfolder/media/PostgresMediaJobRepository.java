package dev.christopherbell.sharedfolder.media;

import static dev.christopherbell.persistence.jooq.shared_folder.Tables.MEDIA_JOB;

import dev.christopherbell.configuration.persistence.PostgresPersistence;
import dev.christopherbell.configuration.persistence.PostgresqlRelativePath;
import dev.christopherbell.persistence.jooq.shared_folder.tables.records.MediaJobRecord;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.domain.SliceImpl;

/** PostgreSQL implementation of durable media-job admission and ownership queries. */
@PostgresPersistence
public class PostgresMediaJobRepository implements MediaJobRepository {
  private final DSLContext database;

  public PostgresMediaJobRepository(DSLContext database) {
    this.database = database;
  }

  @Override public MediaJob save(MediaJob job) {
    String sourcePath = PostgresqlRelativePath.require(job.getSourcePath(), "Media source path");
    if (job.getVersion() == null) {
      database.insertInto(MEDIA_JOB).set(MEDIA_JOB.MEDIA_JOB_ID, job.getId())
          .set(MEDIA_JOB.VERSION, 0L).set(MEDIA_JOB.OWNER_ID, job.getOwnerId())
          .set(MEDIA_JOB.SOURCE_PATH, sourcePath).set(MEDIA_JOB.SOURCE_SIZE, job.getSourceSize())
          .set(MEDIA_JOB.SOURCE_MODIFIED_AT, offset(job.getSourceModifiedAt()))
          .set(MEDIA_JOB.OUTPUT_PROFILE, job.getProfile().name())
          .set(MEDIA_JOB.PROFILE_VERSION, job.getProfileVersion()).set(MEDIA_JOB.CACHE_KEY, job.getCacheKey())
          .set(MEDIA_JOB.ACTIVE_CACHE_KEY, job.getActiveCacheKey()).set(MEDIA_JOB.STATUS, job.getStatus().name())
          .set(MEDIA_JOB.FAILURE_CATEGORY, job.getFailureCategory()).set(MEDIA_JOB.OUTPUT_BYTES, job.getOutputBytes())
          .set(MEDIA_JOB.RESERVED_BYTES, job.getReservedBytes())
          .set(MEDIA_JOB.DESCRIPTOR_PUBLISHED, job.isDescriptorPublished()).set(MEDIA_JOB.DEADLINE, offset(job.getDeadline()))
          .set(MEDIA_JOB.CREATED_AT, offset(job.getCreatedAt())).set(MEDIA_JOB.UPDATED_AT, offset(job.getUpdatedAt()))
          .set(MEDIA_JOB.LAST_ACCESSED_AT, offset(job.getLastAccessedAt()))
          .set(MEDIA_JOB.CLEANUP_AFTER, offset(job.getCleanupAfter()))
          .set(MEDIA_JOB.ARTIFACTS_CLEANED, job.isArtifactsCleaned()).set(MEDIA_JOB.DELETE_AT, offset(job.getDeleteAt()))
          .execute();
    } else {
      long nextVersion = Math.incrementExact(job.getVersion());
      int changed = database.update(MEDIA_JOB).set(MEDIA_JOB.VERSION, nextVersion)
          .set(MEDIA_JOB.OWNER_ID, job.getOwnerId()).set(MEDIA_JOB.SOURCE_PATH, sourcePath)
          .set(MEDIA_JOB.SOURCE_SIZE, job.getSourceSize()).set(MEDIA_JOB.SOURCE_MODIFIED_AT, offset(job.getSourceModifiedAt()))
          .set(MEDIA_JOB.OUTPUT_PROFILE, job.getProfile().name()).set(MEDIA_JOB.PROFILE_VERSION, job.getProfileVersion())
          .set(MEDIA_JOB.CACHE_KEY, job.getCacheKey()).set(MEDIA_JOB.ACTIVE_CACHE_KEY, job.getActiveCacheKey())
          .set(MEDIA_JOB.STATUS, job.getStatus().name()).set(MEDIA_JOB.FAILURE_CATEGORY, job.getFailureCategory())
          .set(MEDIA_JOB.OUTPUT_BYTES, job.getOutputBytes()).set(MEDIA_JOB.RESERVED_BYTES, job.getReservedBytes())
          .set(MEDIA_JOB.DESCRIPTOR_PUBLISHED, job.isDescriptorPublished()).set(MEDIA_JOB.DEADLINE, offset(job.getDeadline()))
          .set(MEDIA_JOB.CREATED_AT, offset(job.getCreatedAt())).set(MEDIA_JOB.UPDATED_AT, offset(job.getUpdatedAt()))
          .set(MEDIA_JOB.LAST_ACCESSED_AT, offset(job.getLastAccessedAt()))
          .set(MEDIA_JOB.CLEANUP_AFTER, offset(job.getCleanupAfter()))
          .set(MEDIA_JOB.ARTIFACTS_CLEANED, job.isArtifactsCleaned()).set(MEDIA_JOB.DELETE_AT, offset(job.getDeleteAt()))
          .where(MEDIA_JOB.MEDIA_JOB_ID.eq(job.getId()).and(MEDIA_JOB.VERSION.eq(job.getVersion())))
          .execute();
      if (changed != 1) throw new OptimisticLockingFailureException("Media job changed during save.");
    }
    return findById(job.getId()).orElseThrow();
  }

  @Override public Optional<MediaJob> findById(String id) {
    return database.selectFrom(MEDIA_JOB).where(MEDIA_JOB.MEDIA_JOB_ID.eq(id))
        .fetchOptional(PostgresMediaJobRepository::map);
  }

  @Override public void deleteById(String id) {
    database.deleteFrom(MEDIA_JOB).where(MEDIA_JOB.MEDIA_JOB_ID.eq(id)).execute();
  }

  @Override public Optional<MediaJob> findFirstByCacheKeyAndStatusOrderByUpdatedAtDesc(
      String cacheKey, MediaJobStatus status) {
    return database.selectFrom(MEDIA_JOB)
        .where(MEDIA_JOB.CACHE_KEY.eq(cacheKey).and(MEDIA_JOB.STATUS.eq(status.name())))
        .orderBy(MEDIA_JOB.UPDATED_AT.desc(), MEDIA_JOB.MEDIA_JOB_ID.desc()).limit(1)
        .fetchOptional(PostgresMediaJobRepository::map);
  }

  @Override public Optional<MediaJob> findFirstByCacheKeyAndStatusInOrderByCreatedAtAsc(
      String cacheKey, Collection<MediaJobStatus> statuses) {
    return database.selectFrom(MEDIA_JOB)
        .where(MEDIA_JOB.CACHE_KEY.eq(cacheKey).and(MEDIA_JOB.STATUS.in(names(statuses))))
        .orderBy(MEDIA_JOB.CREATED_AT.asc(), MEDIA_JOB.MEDIA_JOB_ID.asc()).limit(1)
        .fetchOptional(PostgresMediaJobRepository::map);
  }

  @Override public long countByStatusIn(Collection<MediaJobStatus> statuses) {
    return database.fetchCount(MEDIA_JOB, MEDIA_JOB.STATUS.in(names(statuses)));
  }

  @Override public long countByOwnerIdAndStatusIn(String ownerId, Collection<MediaJobStatus> statuses) {
    return database.fetchCount(MEDIA_JOB,
        MEDIA_JOB.OWNER_ID.eq(ownerId).and(MEDIA_JOB.STATUS.in(names(statuses))));
  }

  @Override public Slice<MediaJob> findByOwnerIdOrderByIdAsc(String ownerId, Pageable pageable) {
    return slice(MEDIA_JOB.OWNER_ID.eq(ownerId), pageable, MEDIA_JOB.MEDIA_JOB_ID.asc());
  }

  @Override public List<MediaJob> findByStatusIn(Collection<MediaJobStatus> statuses) {
    return database.selectFrom(MEDIA_JOB).where(MEDIA_JOB.STATUS.in(names(statuses)))
        .fetch(PostgresMediaJobRepository::map);
  }

  @Override public Optional<MediaJob> findFirstByStatusAndDescriptorPublishedFalseOrderByCreatedAtAsc(
      MediaJobStatus status) {
    return database.selectFrom(MEDIA_JOB)
        .where(MEDIA_JOB.STATUS.eq(status.name()).and(MEDIA_JOB.DESCRIPTOR_PUBLISHED.isFalse()))
        .orderBy(MEDIA_JOB.CREATED_AT.asc(), MEDIA_JOB.MEDIA_JOB_ID.asc()).limit(1)
        .fetchOptional(PostgresMediaJobRepository::map);
  }

  @Override public Optional<MediaJob> findFirstByDescriptorPublishedTrueAndStatusInOrderByCreatedAtAsc(
      Collection<MediaJobStatus> statuses) {
    return database.selectFrom(MEDIA_JOB)
        .where(MEDIA_JOB.DESCRIPTOR_PUBLISHED.isTrue().and(MEDIA_JOB.STATUS.in(names(statuses))))
        .orderBy(MEDIA_JOB.CREATED_AT.asc(), MEDIA_JOB.MEDIA_JOB_ID.asc()).limit(1)
        .fetchOptional(PostgresMediaJobRepository::map);
  }

  @Override public Slice<MediaJob> findByStatusOrderByLastAccessedAtAscIdAsc(
      MediaJobStatus status, Pageable pageable) {
    return slice(MEDIA_JOB.STATUS.eq(status.name()), pageable,
        MEDIA_JOB.LAST_ACCESSED_AT.asc().nullsFirst(), MEDIA_JOB.MEDIA_JOB_ID.asc());
  }

  @Override public Slice<MediaJob>
      findByStatusInAndCleanupAfterLessThanEqualAndArtifactsCleanedFalseOrderByCleanupAfterAscIdAsc(
          Collection<MediaJobStatus> statuses, Instant cleanupAfter, Pageable pageable) {
    return slice(MEDIA_JOB.STATUS.in(names(statuses))
            .and(MEDIA_JOB.CLEANUP_AFTER.le(offset(cleanupAfter)))
            .and(MEDIA_JOB.ARTIFACTS_CLEANED.isFalse()), pageable,
        MEDIA_JOB.CLEANUP_AFTER.asc(), MEDIA_JOB.MEDIA_JOB_ID.asc());
  }

  @Override public long cancelActive(String id, String ownerId, Instant updatedAt, Instant cleanupAfter) {
    return database.update(MEDIA_JOB).set(MEDIA_JOB.STATUS, MediaJobStatus.CANCELED.name())
        .set(MEDIA_JOB.UPDATED_AT, offset(updatedAt)).set(MEDIA_JOB.CLEANUP_AFTER, offset(cleanupAfter))
        .set(MEDIA_JOB.ARTIFACTS_CLEANED, false).set(MEDIA_JOB.DESCRIPTOR_PUBLISHED, false)
        .setNull(MEDIA_JOB.ACTIVE_CACHE_KEY).setNull(MEDIA_JOB.DELETE_AT)
        .set(MEDIA_JOB.VERSION, MEDIA_JOB.VERSION.plus(1L))
        .where(MEDIA_JOB.MEDIA_JOB_ID.eq(id).and(MEDIA_JOB.OWNER_ID.eq(ownerId))
            .and(MEDIA_JOB.STATUS.in(names(MediaJobStatus.active())))).execute();
  }

  private Slice<MediaJob> slice(Condition condition, Pageable pageable, org.jooq.SortField<?>... order) {
    int size = pageable.isPaged() ? pageable.getPageSize() : Integer.MAX_VALUE - 1;
    int offset = pageable.isPaged() ? Math.toIntExact(pageable.getOffset()) : 0;
    List<MediaJob> rows = database.selectFrom(MEDIA_JOB).where(condition).orderBy(order)
        .limit(size == Integer.MAX_VALUE - 1 ? size : size + 1).offset(offset)
        .fetch(PostgresMediaJobRepository::map);
    boolean hasNext = pageable.isPaged() && rows.size() > size;
    List<MediaJob> content = hasNext ? List.copyOf(rows.subList(0, size)) : List.copyOf(rows);
    return new SliceImpl<>(content, pageable, hasNext);
  }

  private static List<String> names(Collection<MediaJobStatus> statuses) {
    return statuses.stream().map(Enum::name).toList();
  }

  private static MediaJob map(MediaJobRecord row) {
    MediaJob job = new MediaJob();
    job.setId(row.getMediaJobId()); job.setVersion(row.getVersion()); job.setOwnerId(row.getOwnerId());
    job.setSourcePath(row.getSourcePath()); job.setSourceSize(row.getSourceSize());
    job.setSourceModifiedAt(instant(row.getSourceModifiedAt()));
    job.setProfile(MediaOutputProfile.valueOf(row.getOutputProfile())); job.setProfileVersion(row.getProfileVersion());
    job.setCacheKey(row.getCacheKey()); job.setActiveCacheKey(row.getActiveCacheKey());
    job.setStatus(MediaJobStatus.valueOf(row.getStatus())); job.setFailureCategory(row.getFailureCategory());
    job.setOutputBytes(row.getOutputBytes()); job.setReservedBytes(row.getReservedBytes());
    job.setDescriptorPublished(row.getDescriptorPublished()); job.setDeadline(instant(row.getDeadline()));
    job.setCreatedAt(instant(row.getCreatedAt())); job.setUpdatedAt(instant(row.getUpdatedAt()));
    job.setLastAccessedAt(instant(row.getLastAccessedAt())); job.setCleanupAfter(instant(row.getCleanupAfter()));
    job.setArtifactsCleaned(row.getArtifactsCleaned()); job.setDeleteAt(instant(row.getDeleteAt()));
    return job;
  }

  private static OffsetDateTime offset(Instant value) {
    return value == null ? null : value.atOffset(ZoneOffset.UTC);
  }

  private static Instant instant(OffsetDateTime value) {
    return value == null ? null : value.toInstant();
  }
}
