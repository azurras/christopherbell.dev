package dev.christopherbell.whatsforlunch.restaurant;

import static dev.christopherbell.persistence.jooq.lunch.Tables.RESTAURANT;

import dev.christopherbell.configuration.persistence.PostgresPersistence;
import dev.christopherbell.configuration.persistence.PostgresqlConstraintViolationCause;
import dev.christopherbell.persistence.jooq.lunch.tables.records.RestaurantRecord;
import dev.christopherbell.whatsforlunch.restaurant.model.Address;
import dev.christopherbell.whatsforlunch.restaurant.model.Restaurant;
import java.math.BigDecimal;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.SortField;
import org.jooq.impl.DSL;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

/** PostgreSQL restaurant adapter with real-location and unique-owner enforcement. */
@PostgresPersistence
public class PostgresRestaurantRepository implements RestaurantRepository {
  private final DSLContext database;

  public PostgresRestaurantRepository(DSLContext database) { this.database = database; }

  @Override public Restaurant save(Restaurant restaurant) {
    RestaurantLocationIntegrity.requireGenuine(restaurant);
    try {
      return database.transactionResult(configuration -> {
        var transaction = DSL.using(configuration);
        transaction.select(RESTAURANT.RESTAURANT_ID).from(RESTAURANT)
            .where(RESTAURANT.RESTAURANT_ID.eq(restaurant.getId())).forUpdate().fetchOptional();
        upsert(transaction, restaurant);
        return findById(transaction, restaurant.getId()).orElseThrow();
      });
    } catch (org.jooq.exception.IntegrityConstraintViolationException failure) {
      if ("23505".equals(failure.sqlState())) {
        throw new DuplicateKeyException("PostgreSQL rejected a duplicate restaurant identity.",
            new PostgresqlConstraintViolationCause(failure.sqlState()));
      }
      throw new DataIntegrityViolationException("PostgreSQL rejected restaurant data.",
          new PostgresqlConstraintViolationCause(failure.sqlState()));
    }
  }

  private static void upsert(DSLContext transaction, Restaurant value) {
    var address = value.getAddress();
    transaction.insertInto(RESTAURANT)
        .set(RESTAURANT.RESTAURANT_ID, value.getId()).set(RESTAURANT.CITY, address.getCity())
        .set(RESTAURANT.COUNTRY, address.getCountry()).set(RESTAURANT.COUNTY, address.getCounty())
        .set(RESTAURANT.CREATED_BY, value.getCreatedBy()).set(RESTAURANT.CREATED_ON, offset(value.getCreatedOn()))
        .set(RESTAURANT.CUISINE, value.getCuisine()).set(RESTAURANT.DEDUPE_KEY, value.getDedupeKey())
        .set(RESTAURANT.DISPLAY_NAME, value.getName()).set(RESTAURANT.LAST_MODIFIED_BY, value.getLastModifiedBy())
        .set(RESTAURANT.LAST_UPDATED_ON, offset(value.getLastUpdatedOn()))
        .set(RESTAURANT.LATITUDE, BigDecimal.valueOf(address.getLatitude()))
        .set(RESTAURANT.LONGITUDE, BigDecimal.valueOf(address.getLongitude()))
        .set(RESTAURANT.NORMALIZED_NAME, value.getNormalizedName())
        .set(RESTAURANT.PHONE_NUMBER, value.getPhoneNumber()).set(RESTAURANT.POSTAL_CODE, address.getPostalCode())
        .set(RESTAURANT.REGION, address.getState()).set(RESTAURANT.SEARCH_CITY, value.getSearchCity())
        .set(RESTAURANT.SEARCH_STATE, value.getSearchState()).set(RESTAURANT.SOURCE_AMENITY, value.getSourceAmenity())
        .set(RESTAURANT.STREET_1, address.getStreet1()).set(RESTAURANT.STREET_2, address.getStreet2())
        .set(RESTAURANT.WEBSITE, value.getWebsite())
        .onConflict(RESTAURANT.RESTAURANT_ID).doUpdate()
        .set(RESTAURANT.CITY, address.getCity()).set(RESTAURANT.COUNTRY, address.getCountry())
        .set(RESTAURANT.COUNTY, address.getCounty()).set(RESTAURANT.CREATED_BY, value.getCreatedBy())
        .set(RESTAURANT.CREATED_ON, offset(value.getCreatedOn())).set(RESTAURANT.CUISINE, value.getCuisine())
        .set(RESTAURANT.DEDUPE_KEY, value.getDedupeKey()).set(RESTAURANT.DISPLAY_NAME, value.getName())
        .set(RESTAURANT.LAST_MODIFIED_BY, value.getLastModifiedBy())
        .set(RESTAURANT.LAST_UPDATED_ON, offset(value.getLastUpdatedOn()))
        .set(RESTAURANT.LATITUDE, BigDecimal.valueOf(address.getLatitude()))
        .set(RESTAURANT.LONGITUDE, BigDecimal.valueOf(address.getLongitude()))
        .set(RESTAURANT.NORMALIZED_NAME, value.getNormalizedName())
        .set(RESTAURANT.PHONE_NUMBER, value.getPhoneNumber()).set(RESTAURANT.POSTAL_CODE, address.getPostalCode())
        .set(RESTAURANT.REGION, address.getState()).set(RESTAURANT.SEARCH_CITY, value.getSearchCity())
        .set(RESTAURANT.SEARCH_STATE, value.getSearchState()).set(RESTAURANT.SOURCE_AMENITY, value.getSourceAmenity())
        .set(RESTAURANT.STREET_1, address.getStreet1()).set(RESTAURANT.STREET_2, address.getStreet2())
        .set(RESTAURANT.WEBSITE, value.getWebsite()).execute();
  }

  @Override public Optional<Restaurant> findById(String id) { return findById(database, id); }
  private static Optional<Restaurant> findById(DSLContext context, String id) {
    return context.selectFrom(RESTAURANT).where(RESTAURANT.RESTAURANT_ID.eq(id))
        .fetchOptional(PostgresRestaurantRepository::map);
  }
  @Override public void delete(Restaurant value) { database.deleteFrom(RESTAURANT).where(RESTAURANT.RESTAURANT_ID.eq(value.getId())).execute(); }
  @Override public void deleteAll(Iterable<Restaurant> values) {
    var ids = new ArrayList<String>(); values.forEach(value -> ids.add(value.getId()));
    if (!ids.isEmpty()) database.deleteFrom(RESTAURANT).where(RESTAURANT.RESTAURANT_ID.in(ids)).execute();
  }
  @Override public List<Restaurant> findAll() { return database.selectFrom(RESTAURANT).orderBy(RESTAURANT.RESTAURANT_ID).fetch(PostgresRestaurantRepository::map); }
  @Override public long count() { return database.fetchCount(RESTAURANT); }
  @Override public Page<Restaurant> findAll(Pageable pageable) {
    var sort = sort(pageable);
    var query = database.selectFrom(RESTAURANT).orderBy(sort);
    var items = pageable.isPaged()
        ? query.limit(pageable.getPageSize()).offset(Math.toIntExact(pageable.getOffset())).fetch(PostgresRestaurantRepository::map)
        : query.fetch(PostgresRestaurantRepository::map);
    return new PageImpl<>(items, pageable, count());
  }
  @Override public List<Restaurant> findAllById(Iterable<String> values) {
    var ids = new ArrayList<String>(); values.forEach(ids::add);
    return ids.isEmpty() ? List.of() : database.selectFrom(RESTAURANT)
        .where(RESTAURANT.RESTAURANT_ID.in(ids)).orderBy(RESTAURANT.RESTAURANT_ID)
        .fetch(PostgresRestaurantRepository::map);
  }
  @Override public Optional<Restaurant> findByNormalizedName(String name) {
    return database.selectFrom(RESTAURANT).where(RESTAURANT.NORMALIZED_NAME.eq(name))
        .fetchOptional(PostgresRestaurantRepository::map);
  }
  @Override public List<Restaurant> findByDedupeKeyIn(List<String> keys) {
    return keys.isEmpty() ? List.of() : database.selectFrom(RESTAURANT)
        .where(RESTAURANT.DEDUPE_KEY.in(keys)).orderBy(RESTAURANT.DEDUPE_KEY, RESTAURANT.RESTAURANT_ID)
        .fetch(PostgresRestaurantRepository::map);
  }
  @Override public List<Restaurant> findByCoordinateBounds(double minLatitude, double maxLatitude,
      double minLongitude, double maxLongitude) {
    return database.selectFrom(RESTAURANT).where(RESTAURANT.LATITUDE.between(
            BigDecimal.valueOf(minLatitude), BigDecimal.valueOf(maxLatitude))
        .and(RESTAURANT.LONGITUDE.between(BigDecimal.valueOf(minLongitude), BigDecimal.valueOf(maxLongitude))))
        .orderBy(RESTAURANT.RESTAURANT_ID).fetch(PostgresRestaurantRepository::map);
  }

  static Restaurant map(RestaurantRecord row) {
    var address = Address.builder().city(row.getCity()).county(row.getCounty()).country(row.getCountry())
        .latitude(row.getLatitude() == null ? null : row.getLatitude().doubleValue())
        .longitude(row.getLongitude() == null ? null : row.getLongitude().doubleValue())
        .postalCode(row.getPostalCode()).state(row.getRegion()).street1(row.getStreet_1()).street2(row.getStreet_2()).build();
    return Restaurant.builder().id(row.getRestaurantId()).address(address).createdBy(row.getCreatedBy())
        .createdOn(instant(row.getCreatedOn())).lastModifiedBy(row.getLastModifiedBy())
        .lastUpdatedOn(instant(row.getLastUpdatedOn())).name(row.getDisplayName())
        .normalizedName(row.getNormalizedName()).dedupeKey(row.getDedupeKey())
        .searchCity(row.getSearchCity()).searchState(row.getSearchState()).cuisine(row.getCuisine())
        .phoneNumber(row.getPhoneNumber()).sourceAmenity(row.getSourceAmenity()).website(row.getWebsite()).build();
  }

  private static List<SortField<?>> sort(Pageable pageable) {
    var fields = new ArrayList<SortField<?>>();
    pageable.getSort().forEach(order -> {
      var field = switch (order.getProperty()) {
        case "normalizedName" -> RESTAURANT.NORMALIZED_NAME;
        case "dedupeKey" -> RESTAURANT.DEDUPE_KEY;
        case "name" -> RESTAURANT.DISPLAY_NAME;
        case "createdOn" -> RESTAURANT.CREATED_ON;
        default -> RESTAURANT.RESTAURANT_ID;
      };
      fields.add(order.isAscending() ? field.asc() : field.desc());
    });
    if (fields.isEmpty()) fields.add(RESTAURANT.RESTAURANT_ID.asc());
    return List.copyOf(fields);
  }
  private static java.time.OffsetDateTime offset(java.time.Instant value) { return value == null ? null : value.atOffset(ZoneOffset.UTC); }
  private static java.time.Instant instant(java.time.OffsetDateTime value) { return value == null ? null : value.toInstant(); }
}
