package dev.christopherbell.vehicle.nhtsa.decode;

import dev.christopherbell.configuration.persistence.PostgresPersistence;
import dev.christopherbell.configuration.persistence.PostgresqlSchemaNames;
import dev.christopherbell.vehicle.model.VehicleVinDecodeCache;
import dev.christopherbell.vehicle.model.VehicleVinDecodeResponse;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionOperations;

/** PostgreSQL VIN decode cache adapter preserving nullable upstream responses. */
@PostgresPersistence
public class PostgresVehicleVinDecodeCacheRepository implements VehicleVinDecodeCacheRepository {
  private final JdbcClient database;
  private final TransactionOperations transactions;
  private final String cacheTable;
  private final String rawTable;

  public PostgresVehicleVinDecodeCacheRepository(
      JdbcClient database, PostgresqlSchemaNames schemas, TransactionOperations transactions) {
    this.database = database;
    this.transactions = transactions;
    cacheTable = schemas.qualifiedTable("mobility", "vin_decode_cache");
    rawTable = schemas.qualifiedTable("mobility", "vin_decode_raw_value");
  }

  @Override
  public Optional<VehicleVinDecodeCache> findById(String id) {
    return database.sql("select * from %s where vin = :vin".formatted(cacheTable))
        .param("vin", id).query(this::map).optional();
  }

  @Override
  public VehicleVinDecodeCache save(VehicleVinDecodeCache cache) {
    return transactions.execute(status -> {
      var response = cache.getResponse();
      database.sql("""
              insert into %s
                (vin, body, created_on, decoder_version, error_code, error_text, expires_on,
                 last_updated_on, make, model, model_year, plant_city, plant_country,
                 plant_state, raw_decoded_values_present, refreshed_on, response_present,
                 response_vin)
              values
                (:vin, :body, :createdOn, :decoderVersion, :errorCode, :errorText, :expiresOn,
                 :lastUpdatedOn, :make, :model, :modelYear, :plantCity, :plantCountry,
                 :plantState, :rawPresent, :refreshedOn, :responsePresent, :responseVin)
              on conflict (vin) do update set
                body = excluded.body, created_on = excluded.created_on,
                decoder_version = excluded.decoder_version, error_code = excluded.error_code,
                error_text = excluded.error_text, expires_on = excluded.expires_on,
                last_updated_on = excluded.last_updated_on, make = excluded.make,
                model = excluded.model, model_year = excluded.model_year,
                plant_city = excluded.plant_city, plant_country = excluded.plant_country,
                plant_state = excluded.plant_state,
                raw_decoded_values_present = excluded.raw_decoded_values_present,
                refreshed_on = excluded.refreshed_on,
                response_present = excluded.response_present,
                response_vin = excluded.response_vin
              """.formatted(cacheTable))
          .param("vin", cache.getVin())
          .param("body", response == null ? null : response.body(), Types.VARCHAR)
          .param("createdOn", offset(cache.getCreatedOn()), Types.TIMESTAMP_WITH_TIMEZONE)
          .param("decoderVersion", cache.getDecoderVersion(), Types.VARCHAR)
          .param("errorCode", response == null ? null : response.errorCode(), Types.VARCHAR)
          .param("errorText", response == null ? null : response.errorText(), Types.VARCHAR)
          .param("expiresOn", offset(cache.getExpiresOn()), Types.TIMESTAMP_WITH_TIMEZONE)
          .param("lastUpdatedOn", offset(cache.getLastUpdatedOn()), Types.TIMESTAMP_WITH_TIMEZONE)
          .param("make", response == null ? null : response.make(), Types.VARCHAR)
          .param("model", response == null ? null : response.model(), Types.VARCHAR)
          .param("modelYear", response == null ? null : response.year(), Types.INTEGER)
          .param("plantCity", response == null ? null : response.plantCity(), Types.VARCHAR)
          .param("plantCountry", response == null ? null : response.plantCountry(), Types.VARCHAR)
          .param("plantState", response == null ? null : response.plantState(), Types.VARCHAR)
          .param("rawPresent", response != null && response.rawDecodedValues() != null)
          .param("refreshedOn", offset(cache.getRefreshedOn()), Types.TIMESTAMP_WITH_TIMEZONE)
          .param("responsePresent", response != null)
          .param("responseVin", response == null ? null : response.vin(), Types.VARCHAR)
          .update();
      database.sql("delete from %s where vin = :vin".formatted(rawTable))
          .param("vin", cache.getVin()).update();
      if (response != null && response.rawDecodedValues() != null) {
        response.rawDecodedValues().entrySet().stream().sorted(Map.Entry.comparingByKey())
            .forEach(entry -> database.sql("""
                    insert into %s (vin, field_name, field_value)
                    values (:vin, :name, :value)
                    """.formatted(rawTable))
                .param("vin", cache.getVin()).param("name", entry.getKey())
                .param("value", entry.getValue()).update());
      }
      return findById(cache.getVin()).orElseThrow();
    });
  }

  private VehicleVinDecodeCache map(ResultSet row, int rowNumber) throws SQLException {
    var raw = new LinkedHashMap<String, String>();
    database.sql("""
            select field_name, field_value from %s where vin = :vin order by field_name
            """.formatted(rawTable))
        .param("vin", row.getString("vin"))
        .query((value, ignored) -> Map.entry(
            value.getString("field_name"), value.getString("field_value")))
        .list().forEach(value -> raw.put(value.getKey(), value.getValue()));
    var hasResponse = row.getBoolean("response_present");
    var rawValues = row.getBoolean("raw_decoded_values_present") ? Map.copyOf(raw) : null;
    var response = hasResponse ? new VehicleVinDecodeResponse(
        row.getString("response_vin"), row.getString("make"), row.getString("model"),
        row.getObject("model_year", Integer.class), row.getString("body"),
        row.getString("plant_city"), row.getString("plant_state"),
        row.getString("plant_country"), row.getString("error_code"),
        row.getString("error_text"), rawValues) : null;
    return VehicleVinDecodeCache.builder().vin(row.getString("vin")).response(response)
        .decoderVersion(row.getString("decoder_version"))
        .refreshedOn(instant(row.getObject("refreshed_on", OffsetDateTime.class)))
        .expiresOn(instant(row.getObject("expires_on", OffsetDateTime.class)))
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
