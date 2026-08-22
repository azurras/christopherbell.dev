package dev.christopherbell.sharedfolder.recycle;

import dev.christopherbell.configuration.persistence.PostgresPersistence;
import dev.christopherbell.configuration.persistence.PostgresqlRelativePath;
import dev.christopherbell.configuration.persistence.PostgresqlSchemaNames;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.domain.SliceImpl;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.simple.JdbcClient;

/** PostgreSQL implementation of recoverable shared-folder recycle metadata. */
@PostgresPersistence
public class PostgresSharedFolderRecycleRepository implements SharedFolderRecycleRepository {
  private final JdbcClient database;
  private final String table;

  public PostgresSharedFolderRecycleRepository(JdbcClient database, PostgresqlSchemaNames schemas) {
    this.database = database;
    table = schemas.qualifiedTable("shared_folder", "recycle_item");
  }

  @Override
  public SharedFolderRecycleItem save(SharedFolderRecycleItem item) {
    String originalPath = PostgresqlRelativePath.require(item.originalPath(), "Recycle original path");
    String payloadKey = PostgresqlRelativePath.require(item.payloadKey(), "Recycle payload key");
    String replacementKey = item.replacementKey() == null ? null
        : PostgresqlRelativePath.require(item.replacementKey(), "Recycle replacement key");
    database.sql("""
            insert into %s (
              recycle_item_id, original_path, deleted_by_account_id, deleted_at, expires_at,
              payload_key, size_bytes, is_directory, source_fingerprint, state,
              replacement_key, replacement_fingerprint, source_identity, retry_after)
            values (
              :id, :originalPath, :deletedBy, :deletedAt, :expiresAt,
              :payloadKey, :size, :directory, :sourceFingerprint, :state,
              :replacementKey, :replacementFingerprint, :sourceIdentity, :retryAfter)
            on conflict (recycle_item_id) do update set
              original_path = excluded.original_path,
              deleted_by_account_id = excluded.deleted_by_account_id,
              deleted_at = excluded.deleted_at,
              expires_at = excluded.expires_at,
              payload_key = excluded.payload_key,
              size_bytes = excluded.size_bytes,
              is_directory = excluded.is_directory,
              source_fingerprint = excluded.source_fingerprint,
              state = excluded.state,
              replacement_key = excluded.replacement_key,
              replacement_fingerprint = excluded.replacement_fingerprint,
              source_identity = excluded.source_identity,
              retry_after = excluded.retry_after
            """.formatted(table))
        .paramSource(new MapSqlParameterSource()
            .addValue("id", item.id()).addValue("originalPath", originalPath)
            .addValue("deletedBy", item.deletedByAccountId())
            .addValue("deletedAt", item.deletedAt().atOffset(ZoneOffset.UTC))
            .addValue("expiresAt", item.expiresAt().atOffset(ZoneOffset.UTC))
            .addValue("payloadKey", payloadKey).addValue("size", item.size())
            .addValue("directory", item.directory()).addValue("sourceFingerprint", item.sourceFingerprint())
            .addValue("state", item.state().name())
            .addValue("replacementKey", replacementKey, Types.VARCHAR)
            .addValue("replacementFingerprint", item.replacementFingerprint(), Types.VARCHAR)
            .addValue("sourceIdentity", item.sourceIdentity(), Types.VARCHAR)
            .addValue("retryAfter", item.retryAfter().atOffset(ZoneOffset.UTC)))
        .update();
    return item;
  }

  @Override
  public Optional<SharedFolderRecycleItem> findById(String id) {
    return database.sql("select * from %s where recycle_item_id = :id".formatted(table))
        .param("id", id).query(PostgresSharedFolderRecycleRepository::map).optional();
  }

  @Override
  public void deleteById(String id) {
    database.sql("delete from %s where recycle_item_id = :id".formatted(table))
        .param("id", id).update();
  }

  @Override
  public Slice<SharedFolderRecycleItem> findByStateOrderByDeletedAtDescIdDesc(
      SharedFolderRecycleState state, Pageable page) {
    int size = page.isPaged() ? page.getPageSize() : Integer.MAX_VALUE - 1;
    var rows = query(
        "state = :state", Map.of("state", state.name()),
        "order by deleted_at desc, recycle_item_id desc limit :limit offset :offset",
        Map.of("limit", page.isPaged() ? size + 1 : size,
            "offset", page.isPaged() ? Math.toIntExact(page.getOffset()) : 0));
    boolean next = page.isPaged() && rows.size() > size;
    return new SliceImpl<>(
        next ? List.copyOf(rows.subList(0, size)) : List.copyOf(rows), page, next);
  }

  @Override
  public List<SharedFolderRecycleItem>
      findByStateAndExpiresAtBeforeAndRetryAfterLessThanEqualOrderByExpiresAtAscIdAsc(
          SharedFolderRecycleState state, Instant cutoff, Instant retryDue, Pageable page) {
    return limited(
        "state = :state and expires_at < :cutoff and retry_after <= :retryDue",
        Map.of("state", state.name(), "cutoff", cutoff.atOffset(ZoneOffset.UTC),
            "retryDue", retryDue.atOffset(ZoneOffset.UTC)),
        "expires_at asc, recycle_item_id asc", page);
  }

  @Override
  public List<SharedFolderRecycleItem>
      findByStateInAndRetryAfterLessThanEqualOrderByDeletedAtAscIdAsc(
          List<SharedFolderRecycleState> states, Instant retryDue, Pageable page) {
    if (states.isEmpty()) return List.of();
    return limited(
        "state in (:states) and retry_after <= :retryDue",
        Map.of("states", states.stream().map(Enum::name).toList(),
            "retryDue", retryDue.atOffset(ZoneOffset.UTC)),
        "deleted_at asc, recycle_item_id asc", page);
  }

  private List<SharedFolderRecycleItem> limited(
      String where, Map<String, ?> parameters, String order, Pageable page) {
    var suffix = "order by " + order
        + (page.isPaged() ? " limit :limit offset :offset" : "");
    return query(where, parameters, suffix, page.isPaged()
        ? Map.of("limit", page.getPageSize(), "offset", Math.toIntExact(page.getOffset()))
        : Map.of());
  }

  private List<SharedFolderRecycleItem> query(
      String where, Map<String, ?> parameters, String suffix, Map<String, ?> suffixParameters) {
    var statement = database.sql("select * from %s where %s %s".formatted(table, where, suffix));
    for (var entry : parameters.entrySet()) statement.param(entry.getKey(), entry.getValue());
    for (var entry : suffixParameters.entrySet()) statement.param(entry.getKey(), entry.getValue());
    return statement.query(PostgresSharedFolderRecycleRepository::map).list();
  }

  private static SharedFolderRecycleItem map(java.sql.ResultSet row, int rowNumber)
      throws SQLException {
    return new SharedFolderRecycleItem(
        row.getString("recycle_item_id"), row.getString("original_path"),
        row.getString("deleted_by_account_id"),
        row.getObject("deleted_at", OffsetDateTime.class).toInstant(),
        row.getObject("expires_at", OffsetDateTime.class).toInstant(),
        row.getString("payload_key"), row.getLong("size_bytes"), row.getBoolean("is_directory"),
        row.getString("source_fingerprint"),
        SharedFolderRecycleState.valueOf(row.getString("state")),
        row.getString("replacement_key"), row.getString("replacement_fingerprint"),
        row.getString("source_identity"),
        row.getObject("retry_after", OffsetDateTime.class).toInstant());
  }
}
