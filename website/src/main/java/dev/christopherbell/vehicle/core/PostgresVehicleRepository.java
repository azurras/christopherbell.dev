package dev.christopherbell.vehicle.core;

import static dev.christopherbell.persistence.jooq.mobility.Tables.VEHICLE;
import static dev.christopherbell.persistence.jooq.mobility.Tables.VEHICLE_DECODED_VALUE;

import dev.christopherbell.configuration.persistence.PostgresPersistence;
import dev.christopherbell.configuration.persistence.PostgresqlConstraintViolationCause;
import dev.christopherbell.persistence.jooq.mobility.tables.records.VehicleRecord;
import dev.christopherbell.vehicle.model.Vehicle;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;

/** PostgreSQL vehicle aggregate adapter with exact VIN ownership. */
@PostgresPersistence
public class PostgresVehicleRepository implements VehicleRepository {
  private final DSLContext database;

  public PostgresVehicleRepository(DSLContext database) {
    this.database = database;
  }

  @Override
  public Vehicle save(Vehicle vehicle) {
    try {
      return database.transactionResult(configuration -> save(DSL.using(configuration), vehicle));
    } catch (org.jooq.exception.IntegrityConstraintViolationException failure) {
      if ("23505".equals(failure.sqlState())) {
        throw new DuplicateKeyException("PostgreSQL rejected a duplicate vehicle identity.",
            new PostgresqlConstraintViolationCause(failure.sqlState()));
      }
      throw new DataIntegrityViolationException("PostgreSQL rejected vehicle data.",
          new PostgresqlConstraintViolationCause(failure.sqlState()));
    }
  }

  @Override
  public List<Vehicle> saveAll(Iterable<Vehicle> vehicles) {
    var saved = new java.util.ArrayList<Vehicle>();
    vehicles.forEach(vehicle -> saved.add(save(vehicle)));
    return List.copyOf(saved);
  }

  private static Vehicle save(DSLContext transaction, Vehicle vehicle) {
    transaction.insertInto(VEHICLE)
        .set(VEHICLE.VEHICLE_ID, vehicle.getId())
        .set(VEHICLE.BODY_STYLE, vehicle.getBodyStyle())
        .set(VEHICLE.BODY_CLASS, vehicle.getBodyClass())
        .set(VEHICLE.COLOR, vehicle.getColor())
        .set(VEHICLE.CREATED_BY, vehicle.getCreatedBy())
        .set(VEHICLE.CREATED_ON, offset(vehicle.getCreatedOn()))
        .set(VEHICLE.DRIVETRAIN, vehicle.getDrivetrain())
        .set(VEHICLE.DOORS, vehicle.getDoors())
        .set(VEHICLE.ENGINE, vehicle.getEngine())
        .set(VEHICLE.FUEL_TYPE, vehicle.getFuelType())
        .set(VEHICLE.GVWR, vehicle.getGvwr())
        .set(VEHICLE.LAST_MODIFIED_BY, vehicle.getLastModifiedBy())
        .set(VEHICLE.LAST_UPDATED_ON, offset(vehicle.getLastUpdatedOn()))
        .set(VEHICLE.LICENSE_PLATE, vehicle.getLicensePlate())
        .set(VEHICLE.LICENSE_PLATE_STATE, vehicle.getLicensePlateState())
        .set(VEHICLE.MAKE, vehicle.getMake())
        .set(VEHICLE.MANUFACTURER, vehicle.getManufacturer())
        .set(VEHICLE.MANUFACTURER_ID, vehicle.getManufacturerId())
        .set(VEHICLE.MILEAGE, vehicle.getMileage() == null ? null : vehicle.getMileage().longValue())
        .set(VEHICLE.MODEL, vehicle.getModel())
        .set(VEHICLE.MODEL_YEAR, vehicle.getYear())
        .set(VEHICLE.NHTSA_ERROR_CODE, vehicle.getNhtsaErrorCode())
        .set(VEHICLE.NHTSA_ERROR_TEXT, vehicle.getNhtsaErrorText())
        .set(VEHICLE.NHTSA_LAST_DECODED_ON, offset(vehicle.getNhtsaLastDecodedOn()))
        .set(VEHICLE.NICKNAME, vehicle.getNickname())
        .set(VEHICLE.NOTES, vehicle.getNotes())
        .set(VEHICLE.PLANT_CITY, vehicle.getPlantCity())
        .set(VEHICLE.PLANT_COUNTRY, vehicle.getPlantCountry())
        .set(VEHICLE.PLANT_STATE, vehicle.getPlantState())
        .set(VEHICLE.PURCHASE_DATE, vehicle.getPurchaseDate())
        .set(VEHICLE.SERIES, vehicle.getSeries())
        .set(VEHICLE.TRANSMISSION, vehicle.getTransmission())
        .set(VEHICLE.TRIM, vehicle.getTrim())
        .set(VEHICLE.VEHICLE_TYPE, vehicle.getVehicleType())
        .set(VEHICLE.VIN, vehicle.getVin())
        .onConflict(VEHICLE.VEHICLE_ID).doUpdate()
        .set(VEHICLE.BODY_STYLE, vehicle.getBodyStyle())
        .set(VEHICLE.BODY_CLASS, vehicle.getBodyClass())
        .set(VEHICLE.COLOR, vehicle.getColor())
        .set(VEHICLE.CREATED_BY, vehicle.getCreatedBy())
        .set(VEHICLE.CREATED_ON, offset(vehicle.getCreatedOn()))
        .set(VEHICLE.DRIVETRAIN, vehicle.getDrivetrain())
        .set(VEHICLE.DOORS, vehicle.getDoors())
        .set(VEHICLE.ENGINE, vehicle.getEngine())
        .set(VEHICLE.FUEL_TYPE, vehicle.getFuelType())
        .set(VEHICLE.GVWR, vehicle.getGvwr())
        .set(VEHICLE.LAST_MODIFIED_BY, vehicle.getLastModifiedBy())
        .set(VEHICLE.LAST_UPDATED_ON, offset(vehicle.getLastUpdatedOn()))
        .set(VEHICLE.LICENSE_PLATE, vehicle.getLicensePlate())
        .set(VEHICLE.LICENSE_PLATE_STATE, vehicle.getLicensePlateState())
        .set(VEHICLE.MAKE, vehicle.getMake())
        .set(VEHICLE.MANUFACTURER, vehicle.getManufacturer())
        .set(VEHICLE.MANUFACTURER_ID, vehicle.getManufacturerId())
        .set(VEHICLE.MILEAGE, vehicle.getMileage() == null ? null : vehicle.getMileage().longValue())
        .set(VEHICLE.MODEL, vehicle.getModel())
        .set(VEHICLE.MODEL_YEAR, vehicle.getYear())
        .set(VEHICLE.NHTSA_ERROR_CODE, vehicle.getNhtsaErrorCode())
        .set(VEHICLE.NHTSA_ERROR_TEXT, vehicle.getNhtsaErrorText())
        .set(VEHICLE.NHTSA_LAST_DECODED_ON, offset(vehicle.getNhtsaLastDecodedOn()))
        .set(VEHICLE.NICKNAME, vehicle.getNickname())
        .set(VEHICLE.NOTES, vehicle.getNotes())
        .set(VEHICLE.PLANT_CITY, vehicle.getPlantCity())
        .set(VEHICLE.PLANT_COUNTRY, vehicle.getPlantCountry())
        .set(VEHICLE.PLANT_STATE, vehicle.getPlantState())
        .set(VEHICLE.PURCHASE_DATE, vehicle.getPurchaseDate())
        .set(VEHICLE.SERIES, vehicle.getSeries())
        .set(VEHICLE.TRANSMISSION, vehicle.getTransmission())
        .set(VEHICLE.TRIM, vehicle.getTrim())
        .set(VEHICLE.VEHICLE_TYPE, vehicle.getVehicleType())
        .set(VEHICLE.VIN, vehicle.getVin())
        .execute();
    transaction.deleteFrom(VEHICLE_DECODED_VALUE)
        .where(VEHICLE_DECODED_VALUE.VEHICLE_ID.eq(vehicle.getId())).execute();
    if (vehicle.getNhtsaDecodedValues() != null) {
      vehicle.getNhtsaDecodedValues().forEach((name, value) -> transaction
          .insertInto(VEHICLE_DECODED_VALUE)
          .set(VEHICLE_DECODED_VALUE.VEHICLE_ID, vehicle.getId())
          .set(VEHICLE_DECODED_VALUE.FIELD_NAME, name)
          .set(VEHICLE_DECODED_VALUE.FIELD_VALUE, value)
          .execute());
    }
    return map(transaction, transaction.selectFrom(VEHICLE)
        .where(VEHICLE.VEHICLE_ID.eq(vehicle.getId())).fetchSingle());
  }

  @Override
  public Optional<Vehicle> findById(String id) {
    return database.selectFrom(VEHICLE).where(VEHICLE.VEHICLE_ID.eq(id))
        .fetchOptional(row -> map(database, row));
  }

  @Override
  public void delete(Vehicle vehicle) {
    database.deleteFrom(VEHICLE).where(VEHICLE.VEHICLE_ID.eq(vehicle.getId())).execute();
  }

  @Override
  public boolean existsByVin(String vin) {
    return database.fetchExists(VEHICLE, VEHICLE.VIN.eq(vin));
  }

  @Override
  public List<Vehicle> findByNotes(String notes) {
    return database.selectFrom(VEHICLE).where(VEHICLE.NOTES.eq(notes))
        .orderBy(VEHICLE.VEHICLE_ID).fetch(row -> map(database, row));
  }

  @Override
  public List<Vehicle> findByVinIsNotNull() {
    return database.selectFrom(VEHICLE).where(VEHICLE.VIN.isNotNull())
        .orderBy(VEHICLE.VEHICLE_ID).fetch(row -> map(database, row));
  }

  @Override
  public List<Vehicle> findByMakeIgnoreCase(String make) {
    return database.selectFrom(VEHICLE).where(VEHICLE.MAKE.equalIgnoreCase(make))
        .orderBy(VEHICLE.VEHICLE_ID).fetch(row -> map(database, row));
  }

  @Override
  public List<Vehicle> findAllByOrderByMakeAscModelAscYearDesc() {
    return database.selectFrom(VEHICLE)
        .orderBy(VEHICLE.MAKE.asc().nullsLast(), VEHICLE.MODEL.asc().nullsLast(),
            VEHICLE.MODEL_YEAR.desc().nullsLast(), VEHICLE.VEHICLE_ID.asc())
        .fetch(row -> map(database, row));
  }

  private static Vehicle map(DSLContext context, VehicleRecord row) {
    var decoded = new LinkedHashMap<String, String>();
    context.selectFrom(VEHICLE_DECODED_VALUE)
        .where(VEHICLE_DECODED_VALUE.VEHICLE_ID.eq(row.getVehicleId()))
        .orderBy(VEHICLE_DECODED_VALUE.FIELD_NAME)
        .forEach(value -> decoded.put(value.getFieldName(), value.getFieldValue()));
    return Vehicle.builder()
        .id(row.getVehicleId()).bodyStyle(row.getBodyStyle()).bodyClass(row.getBodyClass())
        .color(row.getColor()).createdBy(row.getCreatedBy()).createdOn(instant(row.getCreatedOn()))
        .drivetrain(row.getDrivetrain()).doors(row.getDoors()).engine(row.getEngine())
        .fuelType(row.getFuelType()).gvwr(row.getGvwr()).lastModifiedBy(row.getLastModifiedBy())
        .lastUpdatedOn(instant(row.getLastUpdatedOn())).licensePlate(row.getLicensePlate())
        .licensePlateState(row.getLicensePlateState()).make(row.getMake())
        .manufacturer(row.getManufacturer()).manufacturerId(row.getManufacturerId())
        .mileage(row.getMileage() == null ? null : Math.toIntExact(row.getMileage()))
        .model(row.getModel()).nhtsaDecodedValues(MapCopy.copy(decoded))
        .nhtsaErrorCode(row.getNhtsaErrorCode()).nhtsaErrorText(row.getNhtsaErrorText())
        .nhtsaLastDecodedOn(instant(row.getNhtsaLastDecodedOn())).nickname(row.getNickname())
        .notes(row.getNotes()).plantCity(row.getPlantCity()).plantCountry(row.getPlantCountry())
        .plantState(row.getPlantState()).purchaseDate(row.getPurchaseDate()).series(row.getSeries())
        .transmission(row.getTransmission()).trim(row.getTrim()).vehicleType(row.getVehicleType())
        .vin(row.getVin()).year(row.getModelYear()).build();
  }

  private static java.time.OffsetDateTime offset(java.time.Instant value) {
    return value == null ? null : value.atOffset(ZoneOffset.UTC);
  }

  private static java.time.Instant instant(java.time.OffsetDateTime value) {
    return value == null ? null : value.toInstant();
  }

  private static final class MapCopy {
    private MapCopy() {}
    static java.util.Map<String, String> copy(java.util.Map<String, String> values) {
      return values.isEmpty() ? java.util.Map.of() : java.util.Map.copyOf(values);
    }
  }
}
