package dev.christopherbell.post.preview;

import dev.christopherbell.configuration.persistence.PostgresPersistence;
import dev.christopherbell.configuration.persistence.PostgresqlSchemaNames;
import dev.christopherbell.post.model.PostLinkPreview;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.simple.JdbcClient;

/** PostgreSQL persistence for bounded link-preview success and failure cache entries. */
@PostgresPersistence
public class PostgresPostLinkPreviewCacheRepository implements PostLinkPreviewCacheRepository {
  private final JdbcClient database;
  private final String table;

  public PostgresPostLinkPreviewCacheRepository(
      JdbcClient database, PostgresqlSchemaNames schemas) {
    this.database = database;
    table = schemas.qualifiedTable("social", "post_link_preview_cache");
  }

  @Override
  public Optional<PostLinkPreviewCacheEntry> findById(String id) {
    return database.sql("select * from %s where url = :id".formatted(table))
        .param("id", id).query(PostgresPostLinkPreviewCacheRepository::map).optional();
  }

  @Override
  public PostLinkPreviewCacheEntry save(PostLinkPreviewCacheEntry entry) {
    var preview = entry.getPreview();
    database.sql("""
            insert into %s (
              url, status, preview_url, preview_domain, preview_title,
              preview_description, preview_image_url, failure_category,
              completed_on, expires_on)
            values (
              :url, :status, :previewUrl, :domain, :title,
              :description, :imageUrl, :failureCategory, :completedOn, :expiresOn)
            on conflict (url) do update set
              status = excluded.status,
              preview_url = excluded.preview_url,
              preview_domain = excluded.preview_domain,
              preview_title = excluded.preview_title,
              preview_description = excluded.preview_description,
              preview_image_url = excluded.preview_image_url,
              failure_category = excluded.failure_category,
              completed_on = excluded.completed_on,
              expires_on = excluded.expires_on,
              version = %s.version + 1
            """.formatted(table, table))
        .paramSource(new MapSqlParameterSource()
            .addValue("url", entry.getUrl()).addValue("status", entry.getStatus())
            .addValue("previewUrl", preview == null ? null : preview.url(), Types.VARCHAR)
            .addValue("domain", preview == null ? null : preview.domain(), Types.VARCHAR)
            .addValue("title", preview == null ? null : preview.title(), Types.VARCHAR)
            .addValue("description", preview == null ? null : preview.description(), Types.VARCHAR)
            .addValue("imageUrl", preview == null ? null : preview.imageUrl(), Types.VARCHAR)
            .addValue("failureCategory", entry.getFailureCategory(), Types.VARCHAR)
            .addValue("completedOn", entry.getCompletedOn().atOffset(ZoneOffset.UTC))
            .addValue("expiresOn", entry.getExpiresOn().atOffset(ZoneOffset.UTC)))
        .update();
    return findById(entry.getUrl()).orElseThrow();
  }

  @Override
  public int deleteExpired(Instant cutoff, int batchLimit) {
    if (batchLimit < 1) throw new IllegalArgumentException("Cleanup batch limit must be positive");
    return database.sql("""
            with candidates as (
              select url from %s where expires_on <= :cutoff
              order by expires_on asc, url asc limit :limit for update
            )
            delete from %s target using candidates
            where target.url = candidates.url and target.expires_on <= :cutoff
            """.formatted(table, table))
        .param("cutoff", cutoff.atOffset(ZoneOffset.UTC)).param("limit", batchLimit).update();
  }

  private static PostLinkPreviewCacheEntry map(java.sql.ResultSet row, int rowNumber)
      throws SQLException {
    PostLinkPreview preview = row.getString("preview_url") == null ? null
        : PostLinkPreview.builder().url(row.getString("preview_url"))
            .domain(row.getString("preview_domain")).title(row.getString("preview_title"))
            .description(row.getString("preview_description"))
            .imageUrl(row.getString("preview_image_url")).build();
    return PostLinkPreviewCacheEntry.builder()
        .url(row.getString("url")).status(row.getString("status")).preview(preview)
        .failureCategory(row.getString("failure_category"))
        .completedOn(row.getObject("completed_on", OffsetDateTime.class).toInstant())
        .expiresOn(row.getObject("expires_on", OffsetDateTime.class).toInstant()).build();
  }
}
