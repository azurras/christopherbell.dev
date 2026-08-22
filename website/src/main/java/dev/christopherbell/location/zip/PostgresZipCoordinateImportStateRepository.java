package dev.christopherbell.location.zip;

import dev.christopherbell.configuration.persistence.PostgresPersistence;
import dev.christopherbell.configuration.persistence.PostgresqlSchemaNames;
import dev.christopherbell.location.model.ZipCoordinateImportResult;
import dev.christopherbell.location.model.ZipCoordinateImportState;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;

/** PostgreSQL ZIP import checkpoint adapter. */
@PostgresPersistence
public class PostgresZipCoordinateImportStateRepository
    implements ZipCoordinateImportStateRepository {
  private final JdbcClient database;
  private final String table;

  public PostgresZipCoordinateImportStateRepository(
      JdbcClient database, PostgresqlSchemaNames schemas) {
    this.database = database;
    table = schemas.qualifiedTable("mobility", "zip_import_state");
  }

  @Override
  public Optional<ZipCoordinateImportState> findById(String id) {
    return database.sql("select * from %s where import_state_id = :id".formatted(table))
        .param("id", id)
        .query(PostgresZipCoordinateImportStateRepository::map)
        .optional();
  }

  @Override
  public ZipCoordinateImportState save(ZipCoordinateImportState state) {
    var result = java.util.Objects.requireNonNull(state.getResult(), "ZIP import result");
    return database.sql("""
            insert into %s
              (import_state_id, checksum, imported_on, source, source_year,
               result_checksum, result_created, result_deleted, result_imported_on,
               result_no_op, result_processed, result_source, result_source_year,
               result_unchanged, result_updated)
            values
              (:id, :checksum, :importedOn, :source, :sourceYear,
               :resultChecksum, :created, :deleted, :resultImportedOn,
               :noOp, :processed, :resultSource, :resultSourceYear, :unchanged, :updated)
            on conflict (import_state_id) do update set
              checksum = excluded.checksum,
              imported_on = excluded.imported_on,
              source = excluded.source,
              source_year = excluded.source_year,
              result_checksum = excluded.result_checksum,
              result_created = excluded.result_created,
              result_deleted = excluded.result_deleted,
              result_imported_on = excluded.result_imported_on,
              result_no_op = excluded.result_no_op,
              result_processed = excluded.result_processed,
              result_source = excluded.result_source,
              result_source_year = excluded.result_source_year,
              result_unchanged = excluded.result_unchanged,
              result_updated = excluded.result_updated
            returning *
            """.formatted(table))
        .param("id", state.getId())
        .param("checksum", state.getChecksum())
        .param("importedOn", state.getImportedOn().atOffset(ZoneOffset.UTC))
        .param("source", state.getSource())
        .param("sourceYear", state.getSourceYear())
        .param("resultChecksum", result.checksum())
        .param("created", result.created())
        .param("deleted", result.deleted())
        .param("resultImportedOn", result.importedOn().atOffset(ZoneOffset.UTC))
        .param("noOp", result.noOp())
        .param("processed", result.processed())
        .param("resultSource", result.source())
        .param("resultSourceYear", result.sourceYear())
        .param("unchanged", result.unchanged())
        .param("updated", result.updated())
        .query(PostgresZipCoordinateImportStateRepository::map)
        .single();
  }

  private static ZipCoordinateImportState map(java.sql.ResultSet row, int rowNumber)
      throws java.sql.SQLException {
    return ZipCoordinateImportState.builder()
        .id(row.getString("import_state_id"))
        .checksum(row.getString("checksum"))
        .source(row.getString("source"))
        .sourceYear(row.getInt("source_year"))
        .importedOn(row.getObject("imported_on", OffsetDateTime.class).toInstant())
        .result(new ZipCoordinateImportResult(
            row.getInt("result_processed"),
            row.getInt("result_created"),
            row.getInt("result_updated"),
            row.getInt("result_unchanged"),
            row.getInt("result_deleted"),
            row.getString("result_source"),
            row.getInt("result_source_year"),
            row.getString("result_checksum"),
            row.getObject("result_imported_on", OffsetDateTime.class).toInstant(),
            row.getBoolean("result_no_op")))
        .build();
  }
}
