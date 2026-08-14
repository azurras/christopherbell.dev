package dev.christopherbell.canesboxtracker;

import static dev.christopherbell.persistence.jooq.canes.Tables.METRO_PRICE;
import static dev.christopherbell.persistence.jooq.canes.Tables.PRICE_SNAPSHOT;

import dev.christopherbell.canesboxtracker.model.CanesBoxMetroPrice;
import dev.christopherbell.canesboxtracker.model.CanesBoxPriceSnapshot;
import dev.christopherbell.configuration.persistence.PostgresPersistence;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;

/** PostgreSQL weekly Canes price-snapshot adapter with ordered exact-money children. */
@PostgresPersistence
public class PostgresCanesBoxPriceSnapshotRepository
    implements CanesBoxPriceSnapshotRepository {
  private final DSLContext database;

  public PostgresCanesBoxPriceSnapshotRepository(DSLContext database) {
    this.database = database;
  }

  @Override
  public CanesBoxPriceSnapshot save(CanesBoxPriceSnapshot snapshot) {
    requireSnapshot(snapshot);
    return database.transactionResult(configuration -> {
      var transaction = DSL.using(configuration);
      transaction.insertInto(PRICE_SNAPSHOT)
          .set(PRICE_SNAPSHOT.PRICE_SNAPSHOT_ID, snapshot.getId())
          .set(PRICE_SNAPSHOT.WEEK_START_DATE, LocalDate.parse(snapshot.getWeekStartDate()))
          .set(PRICE_SNAPSHOT.COLLECTED_ON, snapshot.getCollectedOn().atOffset(ZoneOffset.UTC))
          .set(PRICE_SNAPSHOT.AVERAGE_PRICE, money(snapshot.getAveragePrice(), "average price"))
          .set(PRICE_SNAPSHOT.CURRENCY, snapshot.getCurrency())
          .set(PRICE_SNAPSHOT.SUCCESSFUL_METRO_COUNT, snapshot.getSuccessfulMetroCount())
          .set(PRICE_SNAPSHOT.TOTAL_METRO_COUNT, snapshot.getTotalMetroCount())
          .set(PRICE_SNAPSHOT.VERIFIED_METRO_COUNT, snapshot.getVerifiedMetroCount())
          .set(PRICE_SNAPSHOT.PROVISIONAL_METRO_COUNT, snapshot.getProvisionalMetroCount())
          .set(PRICE_SNAPSHOT.EXCLUDED_METRO_COUNT, snapshot.getExcludedMetroCount())
          .onConflict(PRICE_SNAPSHOT.PRICE_SNAPSHOT_ID).doUpdate()
          .set(PRICE_SNAPSHOT.WEEK_START_DATE, LocalDate.parse(snapshot.getWeekStartDate()))
          .set(PRICE_SNAPSHOT.COLLECTED_ON, snapshot.getCollectedOn().atOffset(ZoneOffset.UTC))
          .set(PRICE_SNAPSHOT.AVERAGE_PRICE, money(snapshot.getAveragePrice(), "average price"))
          .set(PRICE_SNAPSHOT.CURRENCY, snapshot.getCurrency())
          .set(PRICE_SNAPSHOT.SUCCESSFUL_METRO_COUNT, snapshot.getSuccessfulMetroCount())
          .set(PRICE_SNAPSHOT.TOTAL_METRO_COUNT, snapshot.getTotalMetroCount())
          .set(PRICE_SNAPSHOT.VERIFIED_METRO_COUNT, snapshot.getVerifiedMetroCount())
          .set(PRICE_SNAPSHOT.PROVISIONAL_METRO_COUNT, snapshot.getProvisionalMetroCount())
          .set(PRICE_SNAPSHOT.EXCLUDED_METRO_COUNT, snapshot.getExcludedMetroCount())
          .execute();
      transaction.deleteFrom(METRO_PRICE)
          .where(METRO_PRICE.PRICE_SNAPSHOT_ID.eq(snapshot.getId()))
          .execute();
      var prices = snapshot.getMetroPrices() == null ? List.<CanesBoxMetroPrice>of()
          : snapshot.getMetroPrices();
      for (int ordinal = 0; ordinal < prices.size(); ordinal++) {
        insertMetroPrice(transaction, snapshot.getId(), ordinal, prices.get(ordinal));
      }
      return findById(transaction, snapshot.getId()).orElseThrow();
    });
  }

  @Override
  public Optional<CanesBoxPriceSnapshot> findById(String id) {
    return findById(database, id);
  }

  @Override
  public List<CanesBoxPriceSnapshot> findTop60ByOrderByWeekStartDateDesc() {
    return database.select(PRICE_SNAPSHOT.PRICE_SNAPSHOT_ID)
        .from(PRICE_SNAPSHOT)
        .orderBy(PRICE_SNAPSHOT.WEEK_START_DATE.desc(), PRICE_SNAPSHOT.PRICE_SNAPSHOT_ID.desc())
        .limit(60)
        .fetch(PRICE_SNAPSHOT.PRICE_SNAPSHOT_ID)
        .stream()
        .map(id -> findById(database, id).orElseThrow())
        .toList();
  }

  private static Optional<CanesBoxPriceSnapshot> findById(DSLContext context, String id) {
    return context.selectFrom(PRICE_SNAPSHOT)
        .where(PRICE_SNAPSHOT.PRICE_SNAPSHOT_ID.eq(id))
        .fetchOptional(row -> {
          var snapshot = new CanesBoxPriceSnapshot();
          snapshot.setId(row.getPriceSnapshotId());
          snapshot.setWeekStartDate(row.getWeekStartDate().toString());
          snapshot.setCollectedOn(row.getCollectedOn().toInstant());
          snapshot.setAveragePrice(row.getAveragePrice());
          snapshot.setCurrency(row.getCurrency());
          snapshot.setSuccessfulMetroCount(row.getSuccessfulMetroCount());
          snapshot.setTotalMetroCount(row.getTotalMetroCount());
          snapshot.setVerifiedMetroCount(row.getVerifiedMetroCount());
          snapshot.setProvisionalMetroCount(row.getProvisionalMetroCount());
          snapshot.setExcludedMetroCount(row.getExcludedMetroCount());
          snapshot.setMetroPrices(context.selectFrom(METRO_PRICE)
              .where(METRO_PRICE.PRICE_SNAPSHOT_ID.eq(id))
              .orderBy(METRO_PRICE.ORDINAL)
              .fetch(PostgresCanesBoxPriceSnapshotRepository::mapMetroPrice));
          return snapshot;
        });
  }

  private static void insertMetroPrice(
      DSLContext context, String snapshotId, int ordinal, CanesBoxMetroPrice price) {
    if (price == null || price.getMetroName() == null || price.getMetroName().isBlank()
        || price.getStatus() == null || price.getStatus().isBlank()
        || price.getCollectedOn() == null) {
      throw new IllegalArgumentException("A metro price requires metro, status, and collection time.");
    }
    context.insertInto(METRO_PRICE)
        .set(METRO_PRICE.PRICE_SNAPSHOT_ID, snapshotId)
        .set(METRO_PRICE.ORDINAL, ordinal)
        .set(METRO_PRICE.METRO_NAME, price.getMetroName())
        .set(METRO_PRICE.CITY, price.getCity())
        .set(METRO_PRICE.REGION, price.getState())
        .set(METRO_PRICE.RESTAURANT_REF, price.getRestaurantRef())
        .set(METRO_PRICE.RESTAURANT_NAME, price.getRestaurantName())
        .set(METRO_PRICE.ADDRESS, price.getAddress())
        .set(METRO_PRICE.SOURCE_URL, price.getSourceUrl())
        .set(METRO_PRICE.PRICE, price.getPrice() == null ? null : money(price.getPrice(), "metro price"))
        .set(METRO_PRICE.CURRENCY, price.getCurrency())
        .set(METRO_PRICE.STATUS, price.getStatus())
        .set(METRO_PRICE.SOURCE_NAME, price.getSourceName())
        .set(METRO_PRICE.QUALITY_STATUS, price.getQualityStatus())
        .set(METRO_PRICE.CONFIDENCE_LEVEL, price.getConfidenceLevel())
        .set(METRO_PRICE.RAW_RESPONSE_HASH, price.getRawResponseHash())
        .set(METRO_PRICE.MATCHED_ITEM_NAME, price.getMatchedItemName())
        .set(METRO_PRICE.FAILURE_REASON, price.getFailureReason())
        .set(METRO_PRICE.REVIEW_NOTE, price.getReviewNote())
        .set(METRO_PRICE.COLLECTED_ON, price.getCollectedOn().atOffset(ZoneOffset.UTC))
        .set(METRO_PRICE.SOURCE_FETCHED_ON, offset(price.getSourceFetchedOn()))
        .set(METRO_PRICE.REVIEWED_ON, offset(price.getReviewedOn()))
        .execute();
  }

  private static CanesBoxMetroPrice mapMetroPrice(
      dev.christopherbell.persistence.jooq.canes.tables.records.MetroPriceRecord row) {
    return new CanesBoxMetroPrice(
        row.getMetroName(), row.getCity(), row.getRegion(), row.getRestaurantRef(),
        row.getRestaurantName(), row.getAddress(), row.getSourceUrl(), row.getPrice(),
        row.getCurrency(), row.getStatus(), row.getSourceName(), row.getQualityStatus(),
        row.getConfidenceLevel(), row.getRawResponseHash(), row.getMatchedItemName(),
        row.getFailureReason(), row.getReviewNote(), row.getCollectedOn().toInstant(),
        instant(row.getSourceFetchedOn()), instant(row.getReviewedOn()));
  }

  private static void requireSnapshot(CanesBoxPriceSnapshot snapshot) {
    if (snapshot == null || snapshot.getId() == null || snapshot.getId().isBlank()
        || snapshot.getWeekStartDate() == null || snapshot.getCollectedOn() == null
        || snapshot.getAveragePrice() == null || snapshot.getCurrency() == null) {
      throw new IllegalArgumentException("A price snapshot requires identity, week, money, and collection time.");
    }
  }

  private static BigDecimal money(BigDecimal value, String field) {
    if (value.signum() < 0) {
      throw new IllegalArgumentException(field + " cannot be negative.");
    }
    try {
      return value.setScale(2, RoundingMode.UNNECESSARY);
    } catch (ArithmeticException imprecise) {
      throw new IllegalArgumentException(field + " must have cent precision.", imprecise);
    }
  }

  private static java.time.OffsetDateTime offset(java.time.Instant value) {
    return value == null ? null : value.atOffset(ZoneOffset.UTC);
  }

  private static java.time.Instant instant(java.time.OffsetDateTime value) {
    return value == null ? null : value.toInstant();
  }
}
