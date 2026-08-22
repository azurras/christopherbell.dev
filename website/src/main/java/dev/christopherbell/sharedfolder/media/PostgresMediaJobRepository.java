package dev.christopherbell.sharedfolder.media;

import dev.christopherbell.configuration.persistence.PostgresPersistence;
import dev.christopherbell.configuration.persistence.PostgresqlRelativePath;
import dev.christopherbell.configuration.persistence.PostgresqlSchemaNames;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.domain.SliceImpl;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.simple.JdbcClient;

/** PostgreSQL implementation of durable media-job admission and ownership queries. */
@PostgresPersistence
public class PostgresMediaJobRepository implements MediaJobRepository {
  private final JdbcClient database;
  private final String table;

  public PostgresMediaJobRepository(JdbcClient database, PostgresqlSchemaNames schemas) {
    this.database = database;
    table = schemas.qualifiedTable("shared_folder", "media_job");
  }

  @Override
  public MediaJob save(MediaJob job) {
    String path = PostgresqlRelativePath.require(job.getSourcePath(), "Media source path");
    var parameters = parameters(job, path);
    if (job.getVersion() == null) {
      database.sql("""
              insert into %s (
                media_job_id, version, owner_id, source_path, source_size, source_modified_at,
                output_profile, profile_version, cache_key, active_cache_key, status,
                failure_category, output_bytes, reserved_bytes, descriptor_published, deadline,
                created_at, updated_at, last_accessed_at, cleanup_after, artifacts_cleaned, delete_at)
              values (:id, 0, :ownerId, :sourcePath, :sourceSize, :sourceModifiedAt,
                :profile, :profileVersion, :cacheKey, :activeCacheKey, :status,
                :failureCategory, :outputBytes, :reservedBytes, :published, :deadline,
                :createdAt, :updatedAt, :lastAccessedAt, :cleanupAfter, :cleaned, :deleteAt)
              """.formatted(table)).paramSource(parameters).update();
    } else {
      int changed = database.sql("""
              update %s set version = :nextVersion, owner_id = :ownerId, source_path = :sourcePath,
                source_size = :sourceSize, source_modified_at = :sourceModifiedAt,
                output_profile = :profile, profile_version = :profileVersion,
                cache_key = :cacheKey, active_cache_key = :activeCacheKey, status = :status,
                failure_category = :failureCategory, output_bytes = :outputBytes,
                reserved_bytes = :reservedBytes, descriptor_published = :published,
                deadline = :deadline, created_at = :createdAt, updated_at = :updatedAt,
                last_accessed_at = :lastAccessedAt, cleanup_after = :cleanupAfter,
                artifacts_cleaned = :cleaned, delete_at = :deleteAt
              where media_job_id = :id and version = :expectedVersion
              """.formatted(table)).paramSource(parameters
                  .addValue("nextVersion", Math.incrementExact(job.getVersion()))
                  .addValue("expectedVersion", job.getVersion())).update();
      if (changed != 1) throw new OptimisticLockingFailureException("Media job changed during save.");
    }
    return findById(job.getId()).orElseThrow();
  }

  @Override public Optional<MediaJob> findById(String id) {
    return database.sql("select * from %s where media_job_id = :id".formatted(table))
        .param("id", id).query(PostgresMediaJobRepository::map).optional();
  }

  @Override public void deleteById(String id) {
    database.sql("delete from %s where media_job_id = :id".formatted(table)).param("id", id).update();
  }

  @Override
  public Optional<MediaJob> findFirstByCacheKeyAndStatusOrderByUpdatedAtDesc(
      String cacheKey, MediaJobStatus status) {
    return first("cache_key = :cacheKey and status = :status",
        java.util.Map.of("cacheKey", cacheKey, "status", status.name()),
        "updated_at desc, media_job_id desc");
  }

  @Override
  public Optional<MediaJob> findFirstByCacheKeyAndStatusInOrderByCreatedAtAsc(
      String cacheKey, Collection<MediaJobStatus> statuses) {
    if (statuses.isEmpty()) return Optional.empty();
    return first("cache_key = :cacheKey and status in (:statuses)",
        java.util.Map.of("cacheKey", cacheKey, "statuses", names(statuses)),
        "created_at asc, media_job_id asc");
  }

  @Override public long countByStatusIn(Collection<MediaJobStatus> statuses) {
    return statuses.isEmpty() ? 0 : count("status in (:statuses)",
        java.util.Map.of("statuses", names(statuses)));
  }

  @Override
  public long countByOwnerIdAndStatusIn(String ownerId, Collection<MediaJobStatus> statuses) {
    return statuses.isEmpty() ? 0 : count("owner_id = :ownerId and status in (:statuses)",
        java.util.Map.of("ownerId", ownerId, "statuses", names(statuses)));
  }

  @Override public Slice<MediaJob> findByOwnerIdOrderByIdAsc(String ownerId, Pageable pageable) {
    return slice("owner_id = :ownerId", java.util.Map.of("ownerId", ownerId),
        "media_job_id asc", pageable);
  }

  @Override public List<MediaJob> findByStatusIn(Collection<MediaJobStatus> statuses) {
    return statuses.isEmpty() ? List.of() : list("status in (:statuses)",
        java.util.Map.of("statuses", names(statuses)), "media_job_id asc", null, null);
  }

  @Override
  public Optional<MediaJob> findFirstByStatusAndDescriptorPublishedFalseOrderByCreatedAtAsc(
      MediaJobStatus status) {
    return first("status = :status and descriptor_published = false",
        java.util.Map.of("status", status.name()), "created_at asc, media_job_id asc");
  }

  @Override
  public Optional<MediaJob> findFirstByDescriptorPublishedTrueAndStatusInOrderByCreatedAtAsc(
      Collection<MediaJobStatus> statuses) {
    if (statuses.isEmpty()) return Optional.empty();
    return first("descriptor_published = true and status in (:statuses)",
        java.util.Map.of("statuses", names(statuses)), "created_at asc, media_job_id asc");
  }

  @Override
  public Slice<MediaJob> findByStatusOrderByLastAccessedAtAscIdAsc(
      MediaJobStatus status, Pageable pageable) {
    return slice("status = :status", java.util.Map.of("status", status.name()),
        "last_accessed_at asc nulls first, media_job_id asc", pageable);
  }

  @Override
  public Slice<MediaJob>
      findByStatusInAndCleanupAfterLessThanEqualAndArtifactsCleanedFalseOrderByCleanupAfterAscIdAsc(
          Collection<MediaJobStatus> statuses, Instant cleanupAfter, Pageable pageable) {
    if (statuses.isEmpty()) return new SliceImpl<>(List.of(), pageable, false);
    return slice("status in (:statuses) and cleanup_after <= :cutoff and artifacts_cleaned = false",
        java.util.Map.of("statuses", names(statuses), "cutoff", offset(cleanupAfter)),
        "cleanup_after asc, media_job_id asc", pageable);
  }

  @Override
  public long cancelActive(String id, String ownerId, Instant updatedAt, Instant cleanupAfter) {
    return database.sql("""
            update %s set status = 'CANCELED', updated_at = :updatedAt,
              cleanup_after = :cleanupAfter, artifacts_cleaned = false,
              descriptor_published = false, active_cache_key = null, delete_at = null,
              version = version + 1
            where media_job_id = :id and owner_id = :ownerId and status in (:statuses)
            """.formatted(table)).param("updatedAt", offset(updatedAt))
        .param("cleanupAfter", offset(cleanupAfter)).param("id", id).param("ownerId", ownerId)
        .param("statuses", names(MediaJobStatus.active())).update();
  }

  private Optional<MediaJob> first(String where, java.util.Map<String, ?> params, String order) {
    return list(where, params, order, 0, 1).stream().findFirst();
  }

  private Slice<MediaJob> slice(
      String where, java.util.Map<String, ?> params, String order, Pageable pageable) {
    int size = pageable.isPaged() ? pageable.getPageSize() : Integer.MAX_VALUE - 1;
    int offset = pageable.isPaged() ? Math.toIntExact(pageable.getOffset()) : 0;
    var rows = list(where, params, order, offset, pageable.isPaged() ? size + 1 : size);
    boolean hasNext = pageable.isPaged() && rows.size() > size;
    var content = hasNext ? List.copyOf(rows.subList(0, size)) : List.copyOf(rows);
    return new SliceImpl<>(content, pageable, hasNext);
  }

  private List<MediaJob> list(
      String where, java.util.Map<String, ?> params, String order, Integer offset, Integer limit) {
    var statement = database.sql("select * from %s where %s order by %s".formatted(table, where, order)
        + (limit == null ? "" : " limit :limit offset :offset"));
    for (var entry : params.entrySet()) statement.param(entry.getKey(), entry.getValue());
    if (limit != null) statement.param("limit", limit).param("offset", offset);
    return statement.query(PostgresMediaJobRepository::map).list();
  }

  private long count(String where, java.util.Map<String, ?> params) {
    var statement = database.sql("select count(*) from %s where %s".formatted(table, where));
    for (var entry : params.entrySet()) statement.param(entry.getKey(), entry.getValue());
    return statement.query(Long.class).single();
  }

  private static List<String> names(Collection<MediaJobStatus> statuses) {
    return statuses.stream().map(Enum::name).toList();
  }

  private static MediaJob map(ResultSet row, int ignored) throws SQLException {
    var job = new MediaJob();
    job.setId(row.getString("media_job_id")); job.setVersion(row.getLong("version"));
    job.setOwnerId(row.getString("owner_id")); job.setSourcePath(row.getString("source_path"));
    job.setSourceSize(row.getLong("source_size")); job.setSourceModifiedAt(instant(row, "source_modified_at"));
    job.setProfile(MediaOutputProfile.valueOf(row.getString("output_profile")));
    job.setProfileVersion(row.getInt("profile_version")); job.setCacheKey(row.getString("cache_key"));
    job.setActiveCacheKey(row.getString("active_cache_key"));
    job.setStatus(MediaJobStatus.valueOf(row.getString("status")));
    job.setFailureCategory(row.getString("failure_category"));
    job.setOutputBytes((Long) row.getObject("output_bytes"));
    job.setReservedBytes(row.getLong("reserved_bytes"));
    job.setDescriptorPublished(row.getBoolean("descriptor_published"));
    job.setDeadline(instant(row, "deadline")); job.setCreatedAt(instant(row, "created_at"));
    job.setUpdatedAt(instant(row, "updated_at")); job.setLastAccessedAt(instant(row, "last_accessed_at"));
    job.setCleanupAfter(instant(row, "cleanup_after")); job.setArtifactsCleaned(row.getBoolean("artifacts_cleaned"));
    job.setDeleteAt(instant(row, "delete_at"));
    return job;
  }

  private static MapSqlParameterSource parameters(MediaJob job, String path) {
    return new MapSqlParameterSource().addValue("id", job.getId()).addValue("ownerId", job.getOwnerId())
        .addValue("sourcePath", path).addValue("sourceSize", job.getSourceSize())
        .addValue("sourceModifiedAt", offset(job.getSourceModifiedAt()))
        .addValue("profile", job.getProfile().name()).addValue("profileVersion", job.getProfileVersion())
        .addValue("cacheKey", job.getCacheKey()).addValue("activeCacheKey", job.getActiveCacheKey(), Types.VARCHAR)
        .addValue("status", job.getStatus().name()).addValue("failureCategory", job.getFailureCategory(), Types.VARCHAR)
        .addValue("outputBytes", job.getOutputBytes(), Types.BIGINT).addValue("reservedBytes", job.getReservedBytes())
        .addValue("published", job.isDescriptorPublished()).addValue("deadline", offset(job.getDeadline()), Types.TIMESTAMP_WITH_TIMEZONE)
        .addValue("createdAt", offset(job.getCreatedAt())).addValue("updatedAt", offset(job.getUpdatedAt()))
        .addValue("lastAccessedAt", offset(job.getLastAccessedAt()), Types.TIMESTAMP_WITH_TIMEZONE)
        .addValue("cleanupAfter", offset(job.getCleanupAfter()), Types.TIMESTAMP_WITH_TIMEZONE)
        .addValue("cleaned", job.isArtifactsCleaned()).addValue("deleteAt", offset(job.getDeleteAt()), Types.TIMESTAMP_WITH_TIMEZONE);
  }

  private static OffsetDateTime offset(Instant value) {
    return value == null ? null : value.atOffset(ZoneOffset.UTC);
  }

  private static Instant instant(ResultSet row, String column) throws SQLException {
    var value = row.getObject(column, OffsetDateTime.class);
    return value == null ? null : value.toInstant();
  }
}
