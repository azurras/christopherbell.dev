package dev.christopherbell.whatsforlunch.restaurant.importing;

import dev.christopherbell.configuration.persistence.PostgresPersistence;
import dev.christopherbell.configuration.persistence.PostgresqlSchemaNames;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;

/** PostgreSQL atomic import-preview claim adapter. */
@PostgresPersistence
public class PostgresRestaurantImportPreviewStore implements RestaurantImportPreviewPort {
  private final JdbcClient database;
  private final String table;

  public PostgresRestaurantImportPreviewStore(
      JdbcClient database, PostgresqlSchemaNames schemas) {
    this.database = database;
    table = schemas.qualifiedTable("lunch", "restaurant_import_preview");
  }

  @Override
  public RestaurantImportPreviewDocument save(RestaurantImportPreviewDocument preview) {
    var counts = java.util.Objects.requireNonNull(preview.getCounts(), "preview counts");
    return database.sql("""
            insert into %s
              (import_preview_id, actor_account_id, checksum, consumed_on, created_count,
               created_on, deleted_count, expires_on, fetched_count, invalid_count,
               unchanged_count, updated_count)
            values
              (:id, :actor, :checksum, :consumedOn, :created, :createdOn, :deleted,
               :expiresOn, :fetched, :invalid, :unchanged, :updated)
            on conflict (import_preview_id) do update set
              actor_account_id = excluded.actor_account_id,
              checksum = excluded.checksum,
              consumed_on = excluded.consumed_on,
              created_count = excluded.created_count,
              created_on = excluded.created_on,
              deleted_count = excluded.deleted_count,
              expires_on = excluded.expires_on,
              fetched_count = excluded.fetched_count,
              invalid_count = excluded.invalid_count,
              unchanged_count = excluded.unchanged_count,
              updated_count = excluded.updated_count
            returning *
            """.formatted(table))
        .param("id", preview.getId())
        .param("actor", preview.getActorAccountId())
        .param("checksum", preview.getChecksum())
        .param("consumedOn", offset(preview.getConsumedOn()), Types.TIMESTAMP_WITH_TIMEZONE)
        .param("created", counts.created())
        .param("createdOn", offset(preview.getCreatedOn()))
        .param("deleted", counts.deleted())
        .param("expiresOn", offset(preview.getExpiresOn()))
        .param("fetched", counts.fetched())
        .param("invalid", counts.invalid())
        .param("unchanged", counts.unchanged())
        .param("updated", counts.updated())
        .query(PostgresRestaurantImportPreviewStore::map)
        .single();
  }

  @Override
  public Optional<RestaurantImportPreviewDocument> claim(
      String token, String actorAccountId, Instant now) {
    return database.sql("""
            update %s set consumed_on = :now
            where import_preview_id = :token
              and actor_account_id = :actor
              and consumed_on is null
              and expires_on > :now
            returning *
            """.formatted(table))
        .param("now", offset(now))
        .param("token", token)
        .param("actor", actorAccountId)
        .query(PostgresRestaurantImportPreviewStore::map)
        .optional();
  }

  private static RestaurantImportPreviewDocument map(java.sql.ResultSet row, int rowNumber)
      throws SQLException {
    var consumedOn = row.getObject("consumed_on", OffsetDateTime.class);
    return RestaurantImportPreviewDocument.builder()
        .id(row.getString("import_preview_id"))
        .actorAccountId(row.getString("actor_account_id"))
        .checksum(row.getString("checksum"))
        .createdOn(row.getObject("created_on", OffsetDateTime.class).toInstant())
        .expiresOn(row.getObject("expires_on", OffsetDateTime.class).toInstant())
        .consumedOn(consumedOn == null ? null : consumedOn.toInstant())
        .counts(new RestaurantImportPreviewCounts(
            row.getInt("fetched_count"), row.getInt("created_count"),
            row.getInt("updated_count"), row.getInt("deleted_count"),
            row.getInt("unchanged_count"), row.getInt("invalid_count")))
        .build();
  }

  private static OffsetDateTime offset(Instant value) {
    return value == null ? null : value.atOffset(ZoneOffset.UTC);
  }
}
