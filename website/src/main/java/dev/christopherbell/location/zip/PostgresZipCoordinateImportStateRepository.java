package dev.christopherbell.location.zip;

import static dev.christopherbell.persistence.jooq.mobility.Tables.ZIP_IMPORT_STATE;

import dev.christopherbell.configuration.persistence.PostgresPersistence;
import dev.christopherbell.location.model.ZipCoordinateImportResult;
import dev.christopherbell.location.model.ZipCoordinateImportState;
import java.time.ZoneOffset;
import java.util.Optional;
import org.jooq.DSLContext;

/** PostgreSQL ZIP import checkpoint adapter. */
@PostgresPersistence
public class PostgresZipCoordinateImportStateRepository implements ZipCoordinateImportStateRepository {
  private final DSLContext database;

  public PostgresZipCoordinateImportStateRepository(DSLContext database) { this.database = database; }

  @Override public Optional<ZipCoordinateImportState> findById(String id) {
    return database.selectFrom(ZIP_IMPORT_STATE).where(ZIP_IMPORT_STATE.IMPORT_STATE_ID.eq(id))
        .fetchOptional(row -> ZipCoordinateImportState.builder().id(row.getImportStateId())
            .checksum(row.getChecksum()).source(row.getSource()).sourceYear(row.getSourceYear())
            .importedOn(row.getImportedOn().toInstant())
            .result(new ZipCoordinateImportResult(row.getResultProcessed(), row.getResultCreated(),
                row.getResultUpdated(), row.getResultUnchanged(), row.getResultDeleted(),
                row.getResultSource(), row.getResultSourceYear(), row.getResultChecksum(),
                row.getResultImportedOn().toInstant(), row.getResultNoOp())).build());
  }

  @Override public ZipCoordinateImportState save(ZipCoordinateImportState state) {
    var result = java.util.Objects.requireNonNull(state.getResult(), "ZIP import result");
    database.insertInto(ZIP_IMPORT_STATE)
        .set(ZIP_IMPORT_STATE.IMPORT_STATE_ID, state.getId()).set(ZIP_IMPORT_STATE.CHECKSUM, state.getChecksum())
        .set(ZIP_IMPORT_STATE.IMPORTED_ON, state.getImportedOn().atOffset(ZoneOffset.UTC))
        .set(ZIP_IMPORT_STATE.SOURCE, state.getSource()).set(ZIP_IMPORT_STATE.SOURCE_YEAR, state.getSourceYear())
        .set(ZIP_IMPORT_STATE.RESULT_CHECKSUM, result.checksum())
        .set(ZIP_IMPORT_STATE.RESULT_CREATED, result.created()).set(ZIP_IMPORT_STATE.RESULT_DELETED, result.deleted())
        .set(ZIP_IMPORT_STATE.RESULT_IMPORTED_ON, result.importedOn().atOffset(ZoneOffset.UTC))
        .set(ZIP_IMPORT_STATE.RESULT_NO_OP, result.noOp()).set(ZIP_IMPORT_STATE.RESULT_PROCESSED, result.processed())
        .set(ZIP_IMPORT_STATE.RESULT_SOURCE, result.source()).set(ZIP_IMPORT_STATE.RESULT_SOURCE_YEAR, result.sourceYear())
        .set(ZIP_IMPORT_STATE.RESULT_UNCHANGED, result.unchanged()).set(ZIP_IMPORT_STATE.RESULT_UPDATED, result.updated())
        .onConflict(ZIP_IMPORT_STATE.IMPORT_STATE_ID).doUpdate()
        .set(ZIP_IMPORT_STATE.CHECKSUM, state.getChecksum())
        .set(ZIP_IMPORT_STATE.IMPORTED_ON, state.getImportedOn().atOffset(ZoneOffset.UTC))
        .set(ZIP_IMPORT_STATE.SOURCE, state.getSource()).set(ZIP_IMPORT_STATE.SOURCE_YEAR, state.getSourceYear())
        .set(ZIP_IMPORT_STATE.RESULT_CHECKSUM, result.checksum())
        .set(ZIP_IMPORT_STATE.RESULT_CREATED, result.created()).set(ZIP_IMPORT_STATE.RESULT_DELETED, result.deleted())
        .set(ZIP_IMPORT_STATE.RESULT_IMPORTED_ON, result.importedOn().atOffset(ZoneOffset.UTC))
        .set(ZIP_IMPORT_STATE.RESULT_NO_OP, result.noOp()).set(ZIP_IMPORT_STATE.RESULT_PROCESSED, result.processed())
        .set(ZIP_IMPORT_STATE.RESULT_SOURCE, result.source()).set(ZIP_IMPORT_STATE.RESULT_SOURCE_YEAR, result.sourceYear())
        .set(ZIP_IMPORT_STATE.RESULT_UNCHANGED, result.unchanged()).set(ZIP_IMPORT_STATE.RESULT_UPDATED, result.updated()).execute();
    return findById(state.getId()).orElseThrow();
  }
}
