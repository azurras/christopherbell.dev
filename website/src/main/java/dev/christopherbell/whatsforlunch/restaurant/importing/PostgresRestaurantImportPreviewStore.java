package dev.christopherbell.whatsforlunch.restaurant.importing;

import static dev.christopherbell.persistence.jooq.lunch.Tables.RESTAURANT_IMPORT_PREVIEW;

import dev.christopherbell.configuration.persistence.PostgresPersistence;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.jooq.DSLContext;

/** PostgreSQL atomic import-preview claim adapter. */
@PostgresPersistence
public class PostgresRestaurantImportPreviewStore implements RestaurantImportPreviewPort {
  private final DSLContext database;
  public PostgresRestaurantImportPreviewStore(DSLContext database) { this.database = database; }
  @Override public RestaurantImportPreviewDocument save(RestaurantImportPreviewDocument preview) {
    var counts = java.util.Objects.requireNonNull(preview.getCounts(), "preview counts");
    database.insertInto(RESTAURANT_IMPORT_PREVIEW)
        .set(RESTAURANT_IMPORT_PREVIEW.IMPORT_PREVIEW_ID, preview.getId())
        .set(RESTAURANT_IMPORT_PREVIEW.ACTOR_ACCOUNT_ID, preview.getActorAccountId())
        .set(RESTAURANT_IMPORT_PREVIEW.CHECKSUM, preview.getChecksum())
        .set(RESTAURANT_IMPORT_PREVIEW.CONSUMED_ON, offset(preview.getConsumedOn()))
        .set(RESTAURANT_IMPORT_PREVIEW.CREATED_COUNT, counts.created())
        .set(RESTAURANT_IMPORT_PREVIEW.CREATED_ON, offset(preview.getCreatedOn()))
        .set(RESTAURANT_IMPORT_PREVIEW.DELETED_COUNT, counts.deleted())
        .set(RESTAURANT_IMPORT_PREVIEW.EXPIRES_ON, offset(preview.getExpiresOn()))
        .set(RESTAURANT_IMPORT_PREVIEW.FETCHED_COUNT, counts.fetched())
        .set(RESTAURANT_IMPORT_PREVIEW.INVALID_COUNT, counts.invalid())
        .set(RESTAURANT_IMPORT_PREVIEW.UNCHANGED_COUNT, counts.unchanged())
        .set(RESTAURANT_IMPORT_PREVIEW.UPDATED_COUNT, counts.updated())
        .onConflict(RESTAURANT_IMPORT_PREVIEW.IMPORT_PREVIEW_ID).doUpdate()
        .set(RESTAURANT_IMPORT_PREVIEW.ACTOR_ACCOUNT_ID, preview.getActorAccountId())
        .set(RESTAURANT_IMPORT_PREVIEW.CHECKSUM, preview.getChecksum())
        .set(RESTAURANT_IMPORT_PREVIEW.CONSUMED_ON, offset(preview.getConsumedOn()))
        .set(RESTAURANT_IMPORT_PREVIEW.CREATED_COUNT, counts.created())
        .set(RESTAURANT_IMPORT_PREVIEW.CREATED_ON, offset(preview.getCreatedOn()))
        .set(RESTAURANT_IMPORT_PREVIEW.DELETED_COUNT, counts.deleted())
        .set(RESTAURANT_IMPORT_PREVIEW.EXPIRES_ON, offset(preview.getExpiresOn()))
        .set(RESTAURANT_IMPORT_PREVIEW.FETCHED_COUNT, counts.fetched())
        .set(RESTAURANT_IMPORT_PREVIEW.INVALID_COUNT, counts.invalid())
        .set(RESTAURANT_IMPORT_PREVIEW.UNCHANGED_COUNT, counts.unchanged())
        .set(RESTAURANT_IMPORT_PREVIEW.UPDATED_COUNT, counts.updated()).execute();
    return preview;
  }
  @Override public Optional<RestaurantImportPreviewDocument> claim(String token, String actorAccountId, Instant now) {
    return database.update(RESTAURANT_IMPORT_PREVIEW).set(RESTAURANT_IMPORT_PREVIEW.CONSUMED_ON, offset(now))
        .where(RESTAURANT_IMPORT_PREVIEW.IMPORT_PREVIEW_ID.eq(token)
            .and(RESTAURANT_IMPORT_PREVIEW.ACTOR_ACCOUNT_ID.eq(actorAccountId))
            .and(RESTAURANT_IMPORT_PREVIEW.CONSUMED_ON.isNull())
            .and(RESTAURANT_IMPORT_PREVIEW.EXPIRES_ON.gt(offset(now))))
        .returning().fetchOptional(PostgresRestaurantImportPreviewStore::map);
  }
  private static RestaurantImportPreviewDocument map(
      dev.christopherbell.persistence.jooq.lunch.tables.records.RestaurantImportPreviewRecord row) {
    return RestaurantImportPreviewDocument.builder().id(row.getImportPreviewId())
        .actorAccountId(row.getActorAccountId()).checksum(row.getChecksum())
        .createdOn(row.getCreatedOn().toInstant()).expiresOn(row.getExpiresOn().toInstant())
        .consumedOn(row.getConsumedOn() == null ? null : row.getConsumedOn().toInstant())
        .counts(new RestaurantImportPreviewCounts(row.getFetchedCount(), row.getCreatedCount(),
            row.getUpdatedCount(), row.getDeletedCount(), row.getUnchangedCount(), row.getInvalidCount())).build();
  }
  private static java.time.OffsetDateTime offset(Instant value) { return value == null ? null : value.atOffset(ZoneOffset.UTC); }
}
