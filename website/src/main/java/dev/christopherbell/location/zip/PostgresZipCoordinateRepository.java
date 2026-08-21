package dev.christopherbell.location.zip;

import static dev.christopherbell.persistence.jooq.mobility.Tables.ZIP_COORDINATE;

import dev.christopherbell.configuration.persistence.PostgresPersistence;
import dev.christopherbell.location.model.ZipCoordinate;
import java.math.BigDecimal;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.jooq.DSLContext;

/** PostgreSQL ZIP-coordinate adapter with exact decimal coordinate storage. */
@PostgresPersistence
public class PostgresZipCoordinateRepository implements ZipCoordinateRepository {
  private final DSLContext database;

  public PostgresZipCoordinateRepository(DSLContext database) { this.database = database; }

  @Override public List<ZipCoordinate> saveAll(Iterable<ZipCoordinate> coordinates) {
    var saved = new ArrayList<ZipCoordinate>();
    coordinates.forEach(value -> {
      database.insertInto(ZIP_COORDINATE)
          .set(ZIP_COORDINATE.ZIP_CODE, value.getZipCode())
          .set(ZIP_COORDINATE.LATITUDE, BigDecimal.valueOf(value.getLatitude()))
          .set(ZIP_COORDINATE.LONGITUDE, BigDecimal.valueOf(value.getLongitude()))
          .set(ZIP_COORDINATE.SOURCE, value.getSource()).set(ZIP_COORDINATE.SOURCE_YEAR, value.getSourceYear())
          .set(ZIP_COORDINATE.CREATED_ON, offset(value.getCreatedOn()))
          .set(ZIP_COORDINATE.LAST_UPDATED_ON, offset(value.getLastUpdatedOn()))
          .onConflict(ZIP_COORDINATE.ZIP_CODE).doUpdate()
          .set(ZIP_COORDINATE.LATITUDE, BigDecimal.valueOf(value.getLatitude()))
          .set(ZIP_COORDINATE.LONGITUDE, BigDecimal.valueOf(value.getLongitude()))
          .set(ZIP_COORDINATE.SOURCE, value.getSource()).set(ZIP_COORDINATE.SOURCE_YEAR, value.getSourceYear())
          .set(ZIP_COORDINATE.CREATED_ON, offset(value.getCreatedOn()))
          .set(ZIP_COORDINATE.LAST_UPDATED_ON, offset(value.getLastUpdatedOn())).execute();
      saved.add(findById(value.getZipCode()).orElseThrow());
    });
    return List.copyOf(saved);
  }

  @Override public void deleteAll(Iterable<ZipCoordinate> coordinates) {
    var ids = new ArrayList<String>();
    coordinates.forEach(value -> ids.add(value.getZipCode()));
    if (!ids.isEmpty()) database.deleteFrom(ZIP_COORDINATE).where(ZIP_COORDINATE.ZIP_CODE.in(ids)).execute();
  }

  @Override public Optional<ZipCoordinate> findById(String id) {
    return database.selectFrom(ZIP_COORDINATE).where(ZIP_COORDINATE.ZIP_CODE.eq(id))
        .fetchOptional(row -> ZipCoordinate.builder().zipCode(row.getZipCode())
            .latitude(row.getLatitude().doubleValue()).longitude(row.getLongitude().doubleValue())
            .source(row.getSource()).sourceYear(row.getSourceYear())
            .createdOn(instant(row.getCreatedOn())).lastUpdatedOn(instant(row.getLastUpdatedOn()))
            .build());
  }

  @Override public List<ZipCoordinate> findAllBySource(String source) {
    return database.selectFrom(ZIP_COORDINATE).where(ZIP_COORDINATE.SOURCE.eq(source))
        .orderBy(ZIP_COORDINATE.ZIP_CODE).fetch(row -> ZipCoordinate.builder().zipCode(row.getZipCode())
            .latitude(row.getLatitude().doubleValue()).longitude(row.getLongitude().doubleValue())
            .source(row.getSource()).sourceYear(row.getSourceYear())
            .createdOn(instant(row.getCreatedOn())).lastUpdatedOn(instant(row.getLastUpdatedOn()))
            .build());
  }

  private static java.time.OffsetDateTime offset(java.time.Instant value) {
    return value == null ? null : value.atOffset(ZoneOffset.UTC);
  }

  private static java.time.Instant instant(java.time.OffsetDateTime value) {
    return value == null ? null : value.toInstant();
  }
}
