package dev.christopherbell.vehicle.core;

import dev.christopherbell.configuration.persistence.PostgresPersistence;
import dev.christopherbell.configuration.persistence.PostgresqlIntegrityViolationTranslator;
import dev.christopherbell.configuration.persistence.PostgresqlSchemaNames;
import dev.christopherbell.vehicle.model.Vehicle;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionOperations;

/** PostgreSQL vehicle aggregate adapter with exact VIN ownership. */
@PostgresPersistence
public class PostgresVehicleRepository implements VehicleRepository {
  private final JdbcClient database;
  private final TransactionOperations transactions;
  private final String vehicleTable;
  private final String decodedTable;

  public PostgresVehicleRepository(
      JdbcClient database, PostgresqlSchemaNames schemas, TransactionOperations transactions) {
    this.database = database;
    this.transactions = transactions;
    vehicleTable = schemas.qualifiedTable("mobility", "vehicle");
    decodedTable = schemas.qualifiedTable("mobility", "vehicle_decoded_value");
  }

  @Override
  public Vehicle save(Vehicle vehicle) {
    try {
      return transactions.execute(status -> saveInsideTransaction(vehicle));
    } catch (DataIntegrityViolationException failure) {
      throw PostgresqlIntegrityViolationTranslator.translate(
          sqlState(failure),
          "PostgreSQL rejected a duplicate vehicle identity.",
          "PostgreSQL rejected vehicle data.");
    }
  }

  @Override
  public List<Vehicle> saveAll(Iterable<Vehicle> vehicles) {
    var saved = new java.util.ArrayList<Vehicle>();
    vehicles.forEach(vehicle -> saved.add(save(vehicle)));
    return List.copyOf(saved);
  }

  private Vehicle saveInsideTransaction(Vehicle vehicle) {
    database.sql("""
            insert into %s
              (vehicle_id, body_style, body_class, color, created_by, created_on, drivetrain,
               doors, engine, fuel_type, gvwr, last_modified_by, last_updated_on, license_plate,
               license_plate_state, make, manufacturer, manufacturer_id, mileage, model,
               model_year, nhtsa_error_code, nhtsa_error_text, nhtsa_last_decoded_on, nickname,
               notes, plant_city, plant_country, plant_state, purchase_date, series,
               transmission, trim, vehicle_type, vin)
            values
              (:id, :bodyStyle, :bodyClass, :color, :createdBy, :createdOn, :drivetrain,
               :doors, :engine, :fuelType, :gvwr, :modifiedBy, :updatedOn, :licensePlate,
               :licensePlateState, :make, :manufacturer, :manufacturerId, :mileage, :model,
               :modelYear, :errorCode, :errorText, :decodedOn, :nickname, :notes, :plantCity,
               :plantCountry, :plantState, :purchaseDate, :series, :transmission, :trim,
               :vehicleType, :vin)
            on conflict (vehicle_id) do update set
              body_style=excluded.body_style, body_class=excluded.body_class, color=excluded.color,
              created_by=excluded.created_by, created_on=excluded.created_on,
              drivetrain=excluded.drivetrain, doors=excluded.doors, engine=excluded.engine,
              fuel_type=excluded.fuel_type, gvwr=excluded.gvwr,
              last_modified_by=excluded.last_modified_by, last_updated_on=excluded.last_updated_on,
              license_plate=excluded.license_plate, license_plate_state=excluded.license_plate_state,
              make=excluded.make, manufacturer=excluded.manufacturer,
              manufacturer_id=excluded.manufacturer_id, mileage=excluded.mileage,
              model=excluded.model, model_year=excluded.model_year,
              nhtsa_error_code=excluded.nhtsa_error_code,
              nhtsa_error_text=excluded.nhtsa_error_text,
              nhtsa_last_decoded_on=excluded.nhtsa_last_decoded_on,
              nickname=excluded.nickname, notes=excluded.notes, plant_city=excluded.plant_city,
              plant_country=excluded.plant_country, plant_state=excluded.plant_state,
              purchase_date=excluded.purchase_date, series=excluded.series,
              transmission=excluded.transmission, trim=excluded.trim,
              vehicle_type=excluded.vehicle_type, vin=excluded.vin
            """.formatted(vehicleTable))
        .param("id", vehicle.getId())
        .param("bodyStyle", vehicle.getBodyStyle(), Types.VARCHAR)
        .param("bodyClass", vehicle.getBodyClass(), Types.VARCHAR)
        .param("color", vehicle.getColor(), Types.VARCHAR)
        .param("createdBy", vehicle.getCreatedBy(), Types.VARCHAR)
        .param("createdOn", offset(vehicle.getCreatedOn()), Types.TIMESTAMP_WITH_TIMEZONE)
        .param("drivetrain", vehicle.getDrivetrain(), Types.VARCHAR)
        .param("doors", vehicle.getDoors(), Types.INTEGER)
        .param("engine", vehicle.getEngine(), Types.VARCHAR)
        .param("fuelType", vehicle.getFuelType(), Types.VARCHAR)
        .param("gvwr", vehicle.getGvwr(), Types.VARCHAR)
        .param("modifiedBy", vehicle.getLastModifiedBy(), Types.VARCHAR)
        .param("updatedOn", offset(vehicle.getLastUpdatedOn()), Types.TIMESTAMP_WITH_TIMEZONE)
        .param("licensePlate", vehicle.getLicensePlate(), Types.VARCHAR)
        .param("licensePlateState", vehicle.getLicensePlateState(), Types.VARCHAR)
        .param("make", vehicle.getMake(), Types.VARCHAR)
        .param("manufacturer", vehicle.getManufacturer(), Types.VARCHAR)
        .param("manufacturerId", vehicle.getManufacturerId(), Types.VARCHAR)
        .param("mileage", vehicle.getMileage() == null ? null : vehicle.getMileage().longValue(), Types.BIGINT)
        .param("model", vehicle.getModel(), Types.VARCHAR)
        .param("modelYear", vehicle.getYear(), Types.INTEGER)
        .param("errorCode", vehicle.getNhtsaErrorCode(), Types.VARCHAR)
        .param("errorText", vehicle.getNhtsaErrorText(), Types.VARCHAR)
        .param("decodedOn", offset(vehicle.getNhtsaLastDecodedOn()), Types.TIMESTAMP_WITH_TIMEZONE)
        .param("nickname", vehicle.getNickname(), Types.VARCHAR)
        .param("notes", vehicle.getNotes(), Types.VARCHAR)
        .param("plantCity", vehicle.getPlantCity(), Types.VARCHAR)
        .param("plantCountry", vehicle.getPlantCountry(), Types.VARCHAR)
        .param("plantState", vehicle.getPlantState(), Types.VARCHAR)
        .param("purchaseDate", vehicle.getPurchaseDate(), Types.DATE)
        .param("series", vehicle.getSeries(), Types.VARCHAR)
        .param("transmission", vehicle.getTransmission(), Types.VARCHAR)
        .param("trim", vehicle.getTrim(), Types.VARCHAR)
        .param("vehicleType", vehicle.getVehicleType(), Types.VARCHAR)
        .param("vin", vehicle.getVin(), Types.VARCHAR).update();
    database.sql("delete from %s where vehicle_id = :id".formatted(decodedTable))
        .param("id", vehicle.getId()).update();
    if (vehicle.getNhtsaDecodedValues() != null) {
      vehicle.getNhtsaDecodedValues().entrySet().stream().sorted(Map.Entry.comparingByKey())
          .forEach(entry -> database.sql("""
                  insert into %s (vehicle_id, field_name, field_value)
                  values (:id, :name, :value)
                  """.formatted(decodedTable))
              .param("id", vehicle.getId()).param("name", entry.getKey())
              .param("value", entry.getValue()).update());
    }
    return findById(vehicle.getId()).orElseThrow();
  }

  @Override
  public Optional<Vehicle> findById(String id) {
    return database.sql("select * from %s where vehicle_id = :id".formatted(vehicleTable))
        .param("id", id).query(this::map).optional();
  }

  @Override
  public void delete(Vehicle vehicle) {
    database.sql("delete from %s where vehicle_id = :id".formatted(vehicleTable))
        .param("id", vehicle.getId()).update();
  }

  @Override
  public boolean existsByVin(String vin) {
    return database.sql("select exists(select 1 from %s where vin = :vin)".formatted(vehicleTable))
        .param("vin", vin).query(Boolean.class).single();
  }

  @Override
  public List<Vehicle> findByNotes(String notes) {
    return database.sql("select * from %s where notes = :notes order by vehicle_id"
            .formatted(vehicleTable))
        .param("notes", notes).query(this::map).list();
  }

  @Override
  public List<Vehicle> findByVinIsNotNull() {
    return database.sql("select * from %s where vin is not null order by vehicle_id"
            .formatted(vehicleTable))
        .query(this::map).list();
  }

  @Override
  public List<Vehicle> findByMakeIgnoreCase(String make) {
    return database.sql("select * from %s where lower(make) = lower(:make) order by vehicle_id"
            .formatted(vehicleTable))
        .param("make", make).query(this::map).list();
  }

  @Override
  public List<Vehicle> findAllByOrderByMakeAscModelAscYearDesc() {
    return database.sql("""
            select * from %s
            order by make asc nulls last, model asc nulls last,
                     model_year desc nulls last, vehicle_id asc
            """.formatted(vehicleTable))
        .query(this::map).list();
  }

  private Vehicle map(ResultSet row, int rowNumber) throws SQLException {
    var decoded = new LinkedHashMap<String, String>();
    database.sql("""
            select field_name, field_value from %s where vehicle_id = :id order by field_name
            """.formatted(decodedTable))
        .param("id", row.getString("vehicle_id"))
        .query((value, ignored) -> Map.entry(
            value.getString("field_name"), value.getString("field_value")))
        .list().forEach(value -> decoded.put(value.getKey(), value.getValue()));
    var mileage = row.getObject("mileage", Long.class);
    return Vehicle.builder().id(row.getString("vehicle_id"))
        .bodyStyle(row.getString("body_style")).bodyClass(row.getString("body_class"))
        .color(row.getString("color")).createdBy(row.getString("created_by"))
        .createdOn(instant(row.getObject("created_on", OffsetDateTime.class)))
        .drivetrain(row.getString("drivetrain")).doors(row.getObject("doors", Integer.class))
        .engine(row.getString("engine")).fuelType(row.getString("fuel_type"))
        .gvwr(row.getString("gvwr")).lastModifiedBy(row.getString("last_modified_by"))
        .lastUpdatedOn(instant(row.getObject("last_updated_on", OffsetDateTime.class)))
        .licensePlate(row.getString("license_plate"))
        .licensePlateState(row.getString("license_plate_state")).make(row.getString("make"))
        .manufacturer(row.getString("manufacturer"))
        .manufacturerId(row.getString("manufacturer_id"))
        .mileage(mileage == null ? null : Math.toIntExact(mileage)).model(row.getString("model"))
        .nhtsaDecodedValues(decoded.isEmpty() ? Map.of() : Map.copyOf(decoded))
        .nhtsaErrorCode(row.getString("nhtsa_error_code"))
        .nhtsaErrorText(row.getString("nhtsa_error_text"))
        .nhtsaLastDecodedOn(instant(row.getObject("nhtsa_last_decoded_on", OffsetDateTime.class)))
        .nickname(row.getString("nickname")).notes(row.getString("notes"))
        .plantCity(row.getString("plant_city")).plantCountry(row.getString("plant_country"))
        .plantState(row.getString("plant_state"))
        .purchaseDate(row.getObject("purchase_date", java.time.LocalDate.class))
        .series(row.getString("series")).transmission(row.getString("transmission"))
        .trim(row.getString("trim")).vehicleType(row.getString("vehicle_type"))
        .vin(row.getString("vin")).year(row.getObject("model_year", Integer.class)).build();
  }

  private static OffsetDateTime offset(java.time.Instant value) {
    return value == null ? null : value.atOffset(ZoneOffset.UTC);
  }

  private static java.time.Instant instant(OffsetDateTime value) {
    return value == null ? null : value.toInstant();
  }

  private static String sqlState(Throwable failure) {
    for (var cause = failure; cause != null; cause = cause.getCause()) {
      if (cause instanceof SQLException sqlFailure) {
        return sqlFailure.getSQLState();
      }
    }
    return null;
  }
}
