package dev.christopherbell.location.zip;

import dev.christopherbell.configuration.persistence.PostgresPersistence;
import dev.christopherbell.configuration.persistence.PostgresqlSchemaNames;
import dev.christopherbell.location.model.ZipCoordinate;
import java.math.BigDecimal;
import java.sql.SQLException;
import java.sql.Types;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;

/** PostgreSQL ZIP-coordinate adapter with exact decimal coordinate storage. */
@PostgresPersistence
public class PostgresZipCoordinateRepository implements ZipCoordinateRepository {
  private final JdbcClient database;
  private final String table;

  public PostgresZipCoordinateRepository(JdbcClient database, PostgresqlSchemaNames schemas) {
    this.database = database;
    table = schemas.qualifiedTable("mobility", "zip_coordinate");
  }

  @Override public List<ZipCoordinate> saveAll(Iterable<ZipCoordinate> coordinates) {
    var saved = new ArrayList<ZipCoordinate>();
    coordinates.forEach(value -> {
      saved.add(database.sql("""
              insert into %s
                (zip_code, latitude, longitude, source, source_year, created_on, last_updated_on)
              values (:zipCode, :latitude, :longitude, :source, :sourceYear, :createdOn, :updatedOn)
              on conflict (zip_code) do update set
                latitude = excluded.latitude, longitude = excluded.longitude,
                source = excluded.source, source_year = excluded.source_year,
                created_on = excluded.created_on, last_updated_on = excluded.last_updated_on
              returning *
              """.formatted(table))
          .param("zipCode", value.getZipCode())
          .param("latitude", BigDecimal.valueOf(value.getLatitude()))
          .param("longitude", BigDecimal.valueOf(value.getLongitude()))
          .param("source", value.getSource()).param("sourceYear", value.getSourceYear())
          .param("createdOn", offset(value.getCreatedOn()), Types.TIMESTAMP_WITH_TIMEZONE)
          .param("updatedOn", offset(value.getLastUpdatedOn()), Types.TIMESTAMP_WITH_TIMEZONE)
          .query(PostgresZipCoordinateRepository::map).single());
    });
    return List.copyOf(saved);
  }

  @Override public void deleteAll(Iterable<ZipCoordinate> coordinates) {
    var ids = new ArrayList<String>();
    coordinates.forEach(value -> ids.add(value.getZipCode()));
    if (!ids.isEmpty()) {
      database.sql("delete from %s where zip_code in (:ids)".formatted(table))
          .param("ids", ids).update();
    }
  }

  @Override public Optional<ZipCoordinate> findById(String id) {
    return database.sql("select * from %s where zip_code = :id".formatted(table))
        .param("id", id).query(PostgresZipCoordinateRepository::map).optional();
  }

  @Override public List<ZipCoordinate> findAllBySource(String source) {
    return database.sql("select * from %s where source = :source order by zip_code".formatted(table))
        .param("source", source).query(PostgresZipCoordinateRepository::map).list();
  }

  private static ZipCoordinate map(java.sql.ResultSet row, int rowNumber) throws SQLException {
    return ZipCoordinate.builder().zipCode(row.getString("zip_code"))
        .latitude(row.getBigDecimal("latitude").doubleValue())
        .longitude(row.getBigDecimal("longitude").doubleValue())
        .source(row.getString("source")).sourceYear(row.getInt("source_year"))
        .createdOn(instant(row.getObject("created_on", OffsetDateTime.class)))
        .lastUpdatedOn(instant(row.getObject("last_updated_on", OffsetDateTime.class))).build();
  }

  private static OffsetDateTime offset(java.time.Instant value) {
    return value == null ? null : value.atOffset(ZoneOffset.UTC);
  }

  private static java.time.Instant instant(OffsetDateTime value) {
    return value == null ? null : value.toInstant();
  }
}
