package dev.christopherbell.whatsforlunch.restaurant;

import dev.christopherbell.configuration.persistence.PostgresPersistence;
import dev.christopherbell.configuration.persistence.PostgresqlIntegrityViolationTranslator;
import dev.christopherbell.configuration.persistence.PostgresqlSchemaNames;
import dev.christopherbell.whatsforlunch.restaurant.model.Address;
import dev.christopherbell.whatsforlunch.restaurant.model.Restaurant;
import java.sql.SQLException;
import java.sql.Types;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionOperations;

/** PostgreSQL restaurant adapter with real-location and unique-owner enforcement. */
@PostgresPersistence
public class PostgresRestaurantRepository implements RestaurantRepository {
  private final JdbcClient database;
  private final TransactionOperations transactions;
  private final String table;

  public PostgresRestaurantRepository(
      JdbcClient database, PostgresqlSchemaNames schemas, TransactionOperations transactions) {
    this.database = database;
    this.transactions = transactions;
    table = schemas.qualifiedTable("lunch", "restaurant");
  }

  @Override
  public Restaurant save(Restaurant restaurant) {
    RestaurantLocationIntegrity.requireGenuine(restaurant);
    try {
      return transactions.execute(status -> {
        database.sql("select restaurant_id from %s where restaurant_id = :id for update"
                .formatted(table))
            .param("id", restaurant.getId()).query(String.class).optional();
        return upsert(restaurant);
      });
    } catch (DataIntegrityViolationException failure) {
      throw PostgresqlIntegrityViolationTranslator.translate(
          sqlState(failure),
          "PostgreSQL rejected a duplicate restaurant identity.",
          "PostgreSQL rejected restaurant data.");
    }
  }

  private Restaurant upsert(Restaurant value) {
    var address = value.getAddress();
    return database.sql("""
            insert into %s
              (restaurant_id, city, country, county, created_by, created_on, cuisine,
               dedupe_key, display_name, last_modified_by, last_updated_on, latitude,
               longitude, normalized_name, phone_number, postal_code, region, search_city,
               search_state, source_amenity, street_1, street_2, website)
            values
              (:id, :city, :country, :county, :createdBy, :createdOn, :cuisine,
               :dedupeKey, :displayName, :lastModifiedBy, :lastUpdatedOn, :latitude,
               :longitude, :normalizedName, :phoneNumber, :postalCode, :region, :searchCity,
               :searchState, :sourceAmenity, :street1, :street2, :website)
            on conflict (restaurant_id) do update set
              city = excluded.city, country = excluded.country, county = excluded.county,
              created_by = excluded.created_by, created_on = excluded.created_on,
              cuisine = excluded.cuisine, dedupe_key = excluded.dedupe_key,
              display_name = excluded.display_name,
              last_modified_by = excluded.last_modified_by,
              last_updated_on = excluded.last_updated_on, latitude = excluded.latitude,
              longitude = excluded.longitude, normalized_name = excluded.normalized_name,
              phone_number = excluded.phone_number, postal_code = excluded.postal_code,
              region = excluded.region, search_city = excluded.search_city,
              search_state = excluded.search_state, source_amenity = excluded.source_amenity,
              street_1 = excluded.street_1, street_2 = excluded.street_2,
              website = excluded.website
            returning *
            """.formatted(table))
        .param("id", value.getId()).param("city", address.getCity())
        .param("country", address.getCountry()).param("county", address.getCounty(), Types.VARCHAR)
        .param("createdBy", value.getCreatedBy()).param("createdOn", offset(value.getCreatedOn()))
        .param("cuisine", value.getCuisine(), Types.VARCHAR)
        .param("dedupeKey", value.getDedupeKey(), Types.VARCHAR)
        .param("displayName", value.getName()).param("lastModifiedBy", value.getLastModifiedBy())
        .param("lastUpdatedOn", offset(value.getLastUpdatedOn()))
        .param("latitude", java.math.BigDecimal.valueOf(address.getLatitude()))
        .param("longitude", java.math.BigDecimal.valueOf(address.getLongitude()))
        .param("normalizedName", value.getNormalizedName())
        .param("phoneNumber", value.getPhoneNumber(), Types.VARCHAR)
        .param("postalCode", address.getPostalCode(), Types.VARCHAR)
        .param("region", address.getState()).param("searchCity", value.getSearchCity(), Types.VARCHAR)
        .param("searchState", value.getSearchState(), Types.VARCHAR)
        .param("sourceAmenity", value.getSourceAmenity(), Types.VARCHAR)
        .param("street1", address.getStreet1(), Types.VARCHAR)
        .param("street2", address.getStreet2(), Types.VARCHAR)
        .param("website", value.getWebsite(), Types.VARCHAR)
        .query(PostgresRestaurantRepository::map).single();
  }

  @Override
  public Optional<Restaurant> findById(String id) {
    return database.sql("select * from %s where restaurant_id = :id".formatted(table))
        .param("id", id).query(PostgresRestaurantRepository::map).optional();
  }

  @Override
  public void delete(Restaurant value) {
    database.sql("delete from %s where restaurant_id = :id".formatted(table))
        .param("id", value.getId()).update();
  }

  @Override
  public void deleteAll(Iterable<Restaurant> values) {
    var ids = new ArrayList<String>();
    values.forEach(value -> ids.add(value.getId()));
    if (!ids.isEmpty()) {
      database.sql("delete from %s where restaurant_id in (:ids)".formatted(table))
          .param("ids", ids).update();
    }
  }

  @Override
  public List<Restaurant> findAll() {
    return database.sql("select * from %s order by restaurant_id".formatted(table))
        .query(PostgresRestaurantRepository::map).list();
  }

  @Override
  public long count() {
    return database.sql("select count(*) from %s".formatted(table)).query(Long.class).single();
  }

  @Override
  public Page<Restaurant> findAll(Pageable pageable) {
    var sql = "select * from %s order by %s".formatted(table, orderBy(pageable));
    List<Restaurant> items;
    if (pageable.isPaged()) {
      items = database.sql(sql + " limit :limit offset :offset")
          .param("limit", pageable.getPageSize()).param("offset", Math.toIntExact(pageable.getOffset()))
          .query(PostgresRestaurantRepository::map).list();
    } else {
      items = database.sql(sql).query(PostgresRestaurantRepository::map).list();
    }
    return new PageImpl<>(items, pageable, count());
  }

  @Override
  public List<Restaurant> findAllById(Iterable<String> values) {
    var ids = new ArrayList<String>();
    values.forEach(ids::add);
    if (ids.isEmpty()) {
      return List.of();
    }
    return database.sql("select * from %s where restaurant_id in (:ids) order by restaurant_id"
            .formatted(table))
        .param("ids", ids).query(PostgresRestaurantRepository::map).list();
  }

  @Override
  public Optional<Restaurant> findByNormalizedName(String name) {
    return database.sql("select * from %s where normalized_name = :name".formatted(table))
        .param("name", name).query(PostgresRestaurantRepository::map).optional();
  }

  @Override
  public List<Restaurant> findByDedupeKeyIn(List<String> keys) {
    if (keys.isEmpty()) {
      return List.of();
    }
    return database.sql("""
            select * from %s where dedupe_key in (:keys)
            order by dedupe_key, restaurant_id
            """.formatted(table))
        .param("keys", keys).query(PostgresRestaurantRepository::map).list();
  }

  @Override
  public List<Restaurant> findByCoordinateBounds(
      double minLatitude, double maxLatitude, double minLongitude, double maxLongitude) {
    return database.sql("""
            select * from %s
            where latitude between :minLatitude and :maxLatitude
              and longitude between :minLongitude and :maxLongitude
            order by restaurant_id
            """.formatted(table))
        .param("minLatitude", java.math.BigDecimal.valueOf(minLatitude))
        .param("maxLatitude", java.math.BigDecimal.valueOf(maxLatitude))
        .param("minLongitude", java.math.BigDecimal.valueOf(minLongitude))
        .param("maxLongitude", java.math.BigDecimal.valueOf(maxLongitude))
        .query(PostgresRestaurantRepository::map).list();
  }

  static Restaurant map(java.sql.ResultSet row, int rowNumber) throws SQLException {
    var latitude = row.getBigDecimal("latitude");
    var longitude = row.getBigDecimal("longitude");
    var address = Address.builder().city(row.getString("city")).county(row.getString("county"))
        .country(row.getString("country"))
        .latitude(latitude == null ? null : latitude.doubleValue())
        .longitude(longitude == null ? null : longitude.doubleValue())
        .postalCode(row.getString("postal_code")).state(row.getString("region"))
        .street1(row.getString("street_1")).street2(row.getString("street_2")).build();
    return Restaurant.builder().id(row.getString("restaurant_id")).address(address)
        .createdBy(row.getString("created_by"))
        .createdOn(instant(row.getObject("created_on", OffsetDateTime.class)))
        .lastModifiedBy(row.getString("last_modified_by"))
        .lastUpdatedOn(instant(row.getObject("last_updated_on", OffsetDateTime.class)))
        .name(row.getString("display_name")).normalizedName(row.getString("normalized_name"))
        .dedupeKey(row.getString("dedupe_key")).searchCity(row.getString("search_city"))
        .searchState(row.getString("search_state")).cuisine(row.getString("cuisine"))
        .phoneNumber(row.getString("phone_number"))
        .sourceAmenity(row.getString("source_amenity")).website(row.getString("website")).build();
  }

  private static String orderBy(Pageable pageable) {
    var fields = new ArrayList<String>();
    pageable.getSort().forEach(order -> {
      var column = switch (order.getProperty()) {
        case "normalizedName" -> "normalized_name";
        case "dedupeKey" -> "dedupe_key";
        case "name" -> "display_name";
        case "createdOn" -> "created_on";
        default -> "restaurant_id";
      };
      fields.add(column + (order.isAscending() ? " asc" : " desc"));
    });
    return fields.isEmpty() ? "restaurant_id asc" : String.join(", ", fields);
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
