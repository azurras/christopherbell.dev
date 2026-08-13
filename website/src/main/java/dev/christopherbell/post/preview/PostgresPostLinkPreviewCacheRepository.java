package dev.christopherbell.post.preview;

import static dev.christopherbell.persistence.jooq.social.Tables.POST_LINK_PREVIEW_CACHE;

import dev.christopherbell.configuration.persistence.PostgresPersistence;
import dev.christopherbell.post.model.PostLinkPreview;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.jooq.DSLContext;

/** PostgreSQL persistence for bounded link-preview success and failure cache entries. */
@PostgresPersistence
public final class PostgresPostLinkPreviewCacheRepository
    implements PostLinkPreviewCacheRepository {
  private final DSLContext database;

  public PostgresPostLinkPreviewCacheRepository(DSLContext database) {
    this.database = database;
  }

  @Override
  public Optional<PostLinkPreviewCacheEntry> findById(String id) {
    return database.selectFrom(POST_LINK_PREVIEW_CACHE)
        .where(POST_LINK_PREVIEW_CACHE.URL.eq(id))
        .fetchOptional(PostgresPostLinkPreviewCacheRepository::map);
  }

  @Override
  public PostLinkPreviewCacheEntry save(PostLinkPreviewCacheEntry entry) {
    var preview = entry.getPreview();
    database.insertInto(POST_LINK_PREVIEW_CACHE)
        .set(POST_LINK_PREVIEW_CACHE.URL, entry.getUrl())
        .set(POST_LINK_PREVIEW_CACHE.STATUS, entry.getStatus())
        .set(POST_LINK_PREVIEW_CACHE.PREVIEW_URL, preview == null ? null : preview.url())
        .set(POST_LINK_PREVIEW_CACHE.PREVIEW_DOMAIN, preview == null ? null : preview.domain())
        .set(POST_LINK_PREVIEW_CACHE.PREVIEW_TITLE, preview == null ? null : preview.title())
        .set(POST_LINK_PREVIEW_CACHE.PREVIEW_DESCRIPTION,
            preview == null ? null : preview.description())
        .set(POST_LINK_PREVIEW_CACHE.PREVIEW_IMAGE_URL,
            preview == null ? null : preview.imageUrl())
        .set(POST_LINK_PREVIEW_CACHE.FAILURE_CATEGORY, entry.getFailureCategory())
        .set(POST_LINK_PREVIEW_CACHE.COMPLETED_ON,
            entry.getCompletedOn().atOffset(ZoneOffset.UTC))
        .set(POST_LINK_PREVIEW_CACHE.EXPIRES_ON, entry.getExpiresOn().atOffset(ZoneOffset.UTC))
        .onConflict(POST_LINK_PREVIEW_CACHE.URL)
        .doUpdate()
        .set(POST_LINK_PREVIEW_CACHE.STATUS, entry.getStatus())
        .set(POST_LINK_PREVIEW_CACHE.PREVIEW_URL, preview == null ? null : preview.url())
        .set(POST_LINK_PREVIEW_CACHE.PREVIEW_DOMAIN, preview == null ? null : preview.domain())
        .set(POST_LINK_PREVIEW_CACHE.PREVIEW_TITLE, preview == null ? null : preview.title())
        .set(POST_LINK_PREVIEW_CACHE.PREVIEW_DESCRIPTION,
            preview == null ? null : preview.description())
        .set(POST_LINK_PREVIEW_CACHE.PREVIEW_IMAGE_URL,
            preview == null ? null : preview.imageUrl())
        .set(POST_LINK_PREVIEW_CACHE.FAILURE_CATEGORY, entry.getFailureCategory())
        .set(POST_LINK_PREVIEW_CACHE.COMPLETED_ON,
            entry.getCompletedOn().atOffset(ZoneOffset.UTC))
        .set(POST_LINK_PREVIEW_CACHE.EXPIRES_ON, entry.getExpiresOn().atOffset(ZoneOffset.UTC))
        .set(POST_LINK_PREVIEW_CACHE.VERSION, POST_LINK_PREVIEW_CACHE.VERSION.plus(1L))
        .execute();
    return findById(entry.getUrl()).orElseThrow();
  }

  @Override
  public int deleteExpired(Instant cutoff, int batchLimit) {
    if (batchLimit < 1) throw new IllegalArgumentException("Cleanup batch limit must be positive");
    var candidates = database.select(POST_LINK_PREVIEW_CACHE.URL)
        .from(POST_LINK_PREVIEW_CACHE)
        .where(POST_LINK_PREVIEW_CACHE.EXPIRES_ON.le(cutoff.atOffset(ZoneOffset.UTC)))
        .orderBy(POST_LINK_PREVIEW_CACHE.EXPIRES_ON.asc(), POST_LINK_PREVIEW_CACHE.URL.asc())
        .limit(batchLimit);
    return database.deleteFrom(POST_LINK_PREVIEW_CACHE)
        .where(POST_LINK_PREVIEW_CACHE.URL.in(candidates))
        .execute();
  }

  private static PostLinkPreviewCacheEntry map(
      dev.christopherbell.persistence.jooq.social.tables.records.PostLinkPreviewCacheRecord record) {
    PostLinkPreview preview = record.getPreviewUrl() == null ? null : PostLinkPreview.builder()
        .url(record.getPreviewUrl())
        .domain(record.getPreviewDomain())
        .title(record.getPreviewTitle())
        .description(record.getPreviewDescription())
        .imageUrl(record.getPreviewImageUrl())
        .build();
    return PostLinkPreviewCacheEntry.builder()
        .url(record.getUrl())
        .status(record.getStatus())
        .preview(preview)
        .failureCategory(record.getFailureCategory())
        .completedOn(record.getCompletedOn().toInstant())
        .expiresOn(record.getExpiresOn().toInstant())
        .build();
  }
}
