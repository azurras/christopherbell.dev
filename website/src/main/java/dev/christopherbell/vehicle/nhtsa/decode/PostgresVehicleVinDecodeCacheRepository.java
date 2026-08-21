package dev.christopherbell.vehicle.nhtsa.decode;

import static dev.christopherbell.persistence.jooq.mobility.Tables.VIN_DECODE_CACHE;
import static dev.christopherbell.persistence.jooq.mobility.Tables.VIN_DECODE_RAW_VALUE;

import dev.christopherbell.configuration.persistence.PostgresPersistence;
import dev.christopherbell.persistence.jooq.mobility.tables.records.VinDecodeCacheRecord;
import dev.christopherbell.vehicle.model.VehicleVinDecodeCache;
import dev.christopherbell.vehicle.model.VehicleVinDecodeResponse;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;

/** PostgreSQL VIN decode cache adapter preserving nullable upstream responses. */
@PostgresPersistence
public class PostgresVehicleVinDecodeCacheRepository implements VehicleVinDecodeCacheRepository {
  private final DSLContext database;

  public PostgresVehicleVinDecodeCacheRepository(DSLContext database) {
    this.database = database;
  }

  @Override
  public Optional<VehicleVinDecodeCache> findById(String id) {
    return database.selectFrom(VIN_DECODE_CACHE).where(VIN_DECODE_CACHE.VIN.eq(id))
        .fetchOptional(row -> map(database, row));
  }

  @Override
  public VehicleVinDecodeCache save(VehicleVinDecodeCache cache) {
    return database.transactionResult(configuration -> {
      var transaction = DSL.using(configuration);
      var response = cache.getResponse();
      transaction.insertInto(VIN_DECODE_CACHE)
          .set(VIN_DECODE_CACHE.VIN, cache.getVin())
          .set(VIN_DECODE_CACHE.BODY, response == null ? null : response.body())
          .set(VIN_DECODE_CACHE.CREATED_ON, offset(cache.getCreatedOn()))
          .set(VIN_DECODE_CACHE.DECODER_VERSION, cache.getDecoderVersion())
          .set(VIN_DECODE_CACHE.ERROR_CODE, response == null ? null : response.errorCode())
          .set(VIN_DECODE_CACHE.ERROR_TEXT, response == null ? null : response.errorText())
          .set(VIN_DECODE_CACHE.EXPIRES_ON, offset(cache.getExpiresOn()))
          .set(VIN_DECODE_CACHE.LAST_UPDATED_ON, offset(cache.getLastUpdatedOn()))
          .set(VIN_DECODE_CACHE.MAKE, response == null ? null : response.make())
          .set(VIN_DECODE_CACHE.MODEL, response == null ? null : response.model())
          .set(VIN_DECODE_CACHE.MODEL_YEAR, response == null ? null : response.year())
          .set(VIN_DECODE_CACHE.PLANT_CITY, response == null ? null : response.plantCity())
          .set(VIN_DECODE_CACHE.PLANT_COUNTRY, response == null ? null : response.plantCountry())
          .set(VIN_DECODE_CACHE.PLANT_STATE, response == null ? null : response.plantState())
          .set(VIN_DECODE_CACHE.RAW_DECODED_VALUES_PRESENT,
              response != null && response.rawDecodedValues() != null)
          .set(VIN_DECODE_CACHE.REFRESHED_ON, offset(cache.getRefreshedOn()))
          .set(VIN_DECODE_CACHE.RESPONSE_PRESENT, response != null)
          .set(VIN_DECODE_CACHE.RESPONSE_VIN, response == null ? null : response.vin())
          .onConflict(VIN_DECODE_CACHE.VIN).doUpdate()
          .set(VIN_DECODE_CACHE.BODY, response == null ? null : response.body())
          .set(VIN_DECODE_CACHE.CREATED_ON, offset(cache.getCreatedOn()))
          .set(VIN_DECODE_CACHE.DECODER_VERSION, cache.getDecoderVersion())
          .set(VIN_DECODE_CACHE.ERROR_CODE, response == null ? null : response.errorCode())
          .set(VIN_DECODE_CACHE.ERROR_TEXT, response == null ? null : response.errorText())
          .set(VIN_DECODE_CACHE.EXPIRES_ON, offset(cache.getExpiresOn()))
          .set(VIN_DECODE_CACHE.LAST_UPDATED_ON, offset(cache.getLastUpdatedOn()))
          .set(VIN_DECODE_CACHE.MAKE, response == null ? null : response.make())
          .set(VIN_DECODE_CACHE.MODEL, response == null ? null : response.model())
          .set(VIN_DECODE_CACHE.MODEL_YEAR, response == null ? null : response.year())
          .set(VIN_DECODE_CACHE.PLANT_CITY, response == null ? null : response.plantCity())
          .set(VIN_DECODE_CACHE.PLANT_COUNTRY, response == null ? null : response.plantCountry())
          .set(VIN_DECODE_CACHE.PLANT_STATE, response == null ? null : response.plantState())
          .set(VIN_DECODE_CACHE.RAW_DECODED_VALUES_PRESENT,
              response != null && response.rawDecodedValues() != null)
          .set(VIN_DECODE_CACHE.REFRESHED_ON, offset(cache.getRefreshedOn()))
          .set(VIN_DECODE_CACHE.RESPONSE_PRESENT, response != null)
          .set(VIN_DECODE_CACHE.RESPONSE_VIN, response == null ? null : response.vin())
          .execute();
      transaction.deleteFrom(VIN_DECODE_RAW_VALUE)
          .where(VIN_DECODE_RAW_VALUE.VIN.eq(cache.getVin())).execute();
      if (response != null && response.rawDecodedValues() != null) {
        response.rawDecodedValues().forEach((name, value) -> transaction
            .insertInto(VIN_DECODE_RAW_VALUE)
            .set(VIN_DECODE_RAW_VALUE.VIN, cache.getVin())
            .set(VIN_DECODE_RAW_VALUE.FIELD_NAME, name)
            .set(VIN_DECODE_RAW_VALUE.FIELD_VALUE, value)
            .execute());
      }
      return map(transaction, transaction.selectFrom(VIN_DECODE_CACHE)
          .where(VIN_DECODE_CACHE.VIN.eq(cache.getVin())).fetchSingle());
    });
  }

  private static VehicleVinDecodeCache map(DSLContext context, VinDecodeCacheRecord row) {
    var raw = new LinkedHashMap<String, String>();
    context.selectFrom(VIN_DECODE_RAW_VALUE).where(VIN_DECODE_RAW_VALUE.VIN.eq(row.getVin()))
        .orderBy(VIN_DECODE_RAW_VALUE.FIELD_NAME)
        .forEach(value -> raw.put(value.getFieldName(), value.getFieldValue()));
    boolean hasResponse = Boolean.TRUE.equals(row.getResponsePresent());
    var rawValues = Boolean.TRUE.equals(row.getRawDecodedValuesPresent())
        ? Map.copyOf(raw)
        : null;
    var response = hasResponse ? new VehicleVinDecodeResponse(
        row.getResponseVin(), row.getMake(), row.getModel(), row.getModelYear(), row.getBody(),
        row.getPlantCity(), row.getPlantState(), row.getPlantCountry(), row.getErrorCode(),
        row.getErrorText(), rawValues) : null;
    return VehicleVinDecodeCache.builder().vin(row.getVin()).response(response)
        .decoderVersion(row.getDecoderVersion()).refreshedOn(instant(row.getRefreshedOn()))
        .expiresOn(instant(row.getExpiresOn())).createdOn(instant(row.getCreatedOn()))
        .lastUpdatedOn(instant(row.getLastUpdatedOn())).build();
  }

  private static java.time.OffsetDateTime offset(java.time.Instant value) {
    return value == null ? null : value.atOffset(ZoneOffset.UTC);
  }

  private static java.time.Instant instant(java.time.OffsetDateTime value) {
    return value == null ? null : value.toInstant();
  }
}
