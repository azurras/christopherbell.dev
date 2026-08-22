package dev.christopherbell.canesboxtracker;

import dev.christopherbell.canesboxtracker.model.CanesBoxMetroPrice;
import dev.christopherbell.canesboxtracker.model.CanesBoxPriceSnapshot;
import dev.christopherbell.configuration.persistence.PostgresPersistence;
import dev.christopherbell.configuration.persistence.PostgresqlSchemaNames;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.SQLException;
import java.sql.Types;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionOperations;

/** PostgreSQL weekly Canes price-snapshot adapter with ordered exact-money children. */
@PostgresPersistence
public class PostgresCanesBoxPriceSnapshotRepository
    implements CanesBoxPriceSnapshotRepository {
  private final JdbcClient database;
  private final TransactionOperations transactions;
  private final String snapshotTable;
  private final String metroPriceTable;

  public PostgresCanesBoxPriceSnapshotRepository(
      JdbcClient database, PostgresqlSchemaNames schemas, TransactionOperations transactions) {
    this.database = database;
    this.transactions = transactions;
    snapshotTable = schemas.qualifiedTable("canes", "price_snapshot");
    metroPriceTable = schemas.qualifiedTable("canes", "metro_price");
  }

  @Override
  public CanesBoxPriceSnapshot save(CanesBoxPriceSnapshot snapshot) {
    requireSnapshot(snapshot);
    var saved = transactions.execute(ignored -> {
      database.sql("""
              insert into %s (
                price_snapshot_id, week_start_date, collected_on, average_price, currency,
                successful_metro_count, total_metro_count, verified_metro_count,
                provisional_metro_count, excluded_metro_count)
              values (
                :id, :week, :collectedOn, :averagePrice, :currency,
                :successful, :total, :verified, :provisional, :excluded)
              on conflict (price_snapshot_id) do update set
                week_start_date = excluded.week_start_date,
                collected_on = excluded.collected_on,
                average_price = excluded.average_price,
                currency = excluded.currency,
                successful_metro_count = excluded.successful_metro_count,
                total_metro_count = excluded.total_metro_count,
                verified_metro_count = excluded.verified_metro_count,
                provisional_metro_count = excluded.provisional_metro_count,
                excluded_metro_count = excluded.excluded_metro_count
              """.formatted(snapshotTable))
          .param("id", snapshot.getId())
          .param("week", LocalDate.parse(snapshot.getWeekStartDate()))
          .param("collectedOn", snapshot.getCollectedOn().atOffset(ZoneOffset.UTC))
          .param("averagePrice", money(snapshot.getAveragePrice(), "average price"))
          .param("currency", snapshot.getCurrency())
          .param("successful", snapshot.getSuccessfulMetroCount())
          .param("total", snapshot.getTotalMetroCount())
          .param("verified", snapshot.getVerifiedMetroCount())
          .param("provisional", snapshot.getProvisionalMetroCount())
          .param("excluded", snapshot.getExcludedMetroCount()).update();
      database.sql("delete from %s where price_snapshot_id = :id".formatted(metroPriceTable))
          .param("id", snapshot.getId()).update();
      var prices = snapshot.getMetroPrices() == null ? List.<CanesBoxMetroPrice>of()
          : snapshot.getMetroPrices();
      for (int ordinal = 0; ordinal < prices.size(); ordinal++) {
        insertMetroPrice(snapshot.getId(), ordinal, prices.get(ordinal));
      }
      return findById(snapshot.getId()).orElseThrow();
    });
    if (saved == null) throw new IllegalStateException("Canes snapshot transaction returned no value");
    return saved;
  }

  @Override
  public Optional<CanesBoxPriceSnapshot> findById(String id) {
    return database.sql("select * from %s where price_snapshot_id = :id".formatted(snapshotTable))
        .param("id", id).query(PostgresCanesBoxPriceSnapshotRepository::mapSnapshotRow)
        .optional().map(snapshot -> {
          snapshot.setMetroPrices(metroPrices(List.of(id)).getOrDefault(id, List.of()));
          return snapshot;
        });
  }

  @Override
  public List<CanesBoxPriceSnapshot> findTop60ByOrderByWeekStartDateDesc() {
    var snapshots = database.sql("""
            select * from %s
            order by week_start_date desc, price_snapshot_id desc limit 60
            """.formatted(snapshotTable))
        .query(PostgresCanesBoxPriceSnapshotRepository::mapSnapshotRow).list();
    if (snapshots.isEmpty()) return List.of();
    var prices = metroPrices(snapshots.stream().map(CanesBoxPriceSnapshot::getId).toList());
    snapshots.forEach(snapshot -> snapshot.setMetroPrices(
        List.copyOf(prices.getOrDefault(snapshot.getId(), List.of()))));
    return List.copyOf(snapshots);
  }

  private LinkedHashMap<String, List<CanesBoxMetroPrice>> metroPrices(List<String> snapshotIds) {
    var result = new LinkedHashMap<String, List<CanesBoxMetroPrice>>();
    snapshotIds.forEach(id -> result.put(id, new ArrayList<>()));
    database.sql("""
            select * from %s where price_snapshot_id in (:ids)
            order by price_snapshot_id, ordinal
            """.formatted(metroPriceTable))
        .param("ids", snapshotIds)
        .query((row, ignored) -> new MetroRow(
            row.getString("price_snapshot_id"), mapMetroPrice(row)))
        .list().forEach(row -> result.get(row.snapshotId()).add(row.price()));
    return result;
  }

  private void insertMetroPrice(String snapshotId, int ordinal, CanesBoxMetroPrice price) {
    if (price == null || price.getMetroName() == null || price.getMetroName().isBlank()
        || price.getStatus() == null || price.getStatus().isBlank()
        || price.getCollectedOn() == null) {
      throw new IllegalArgumentException("A metro price requires metro, status, and collection time.");
    }
    database.sql("""
            insert into %s (
              price_snapshot_id, ordinal, metro_name, city, region, restaurant_ref,
              restaurant_name, address, source_url, price, currency, status, source_name,
              quality_status, confidence_level, raw_response_hash, matched_item_name,
              failure_reason, review_note, collected_on, source_fetched_on, reviewed_on)
            values (
              :snapshotId, :ordinal, :metroName, :city, :region, :restaurantRef,
              :restaurantName, :address, :sourceUrl, :price, :currency, :status, :sourceName,
              :qualityStatus, :confidence, :rawHash, :matchedName,
              :failureReason, :reviewNote, :collectedOn, :sourceFetchedOn, :reviewedOn)
            """.formatted(metroPriceTable))
        .paramSource(new MapSqlParameterSource()
            .addValue("snapshotId", snapshotId).addValue("ordinal", ordinal)
            .addValue("metroName", price.getMetroName()).addValue("city", price.getCity(), Types.VARCHAR)
            .addValue("region", price.getState(), Types.VARCHAR)
            .addValue("restaurantRef", price.getRestaurantRef(), Types.VARCHAR)
            .addValue("restaurantName", price.getRestaurantName(), Types.VARCHAR)
            .addValue("address", price.getAddress(), Types.VARCHAR)
            .addValue("sourceUrl", price.getSourceUrl(), Types.VARCHAR)
            .addValue("price", price.getPrice() == null ? null : money(price.getPrice(), "metro price"), Types.NUMERIC)
            .addValue("currency", price.getCurrency(), Types.VARCHAR).addValue("status", price.getStatus())
            .addValue("sourceName", price.getSourceName(), Types.VARCHAR)
            .addValue("qualityStatus", price.getQualityStatus(), Types.VARCHAR)
            .addValue("confidence", price.getConfidenceLevel(), Types.VARCHAR)
            .addValue("rawHash", price.getRawResponseHash(), Types.VARCHAR)
            .addValue("matchedName", price.getMatchedItemName(), Types.VARCHAR)
            .addValue("failureReason", price.getFailureReason(), Types.VARCHAR)
            .addValue("reviewNote", price.getReviewNote(), Types.VARCHAR)
            .addValue("collectedOn", price.getCollectedOn().atOffset(ZoneOffset.UTC))
            .addValue("sourceFetchedOn", offset(price.getSourceFetchedOn()), Types.TIMESTAMP_WITH_TIMEZONE)
            .addValue("reviewedOn", offset(price.getReviewedOn()), Types.TIMESTAMP_WITH_TIMEZONE))
        .update();
  }

  private static CanesBoxPriceSnapshot mapSnapshotRow(java.sql.ResultSet row, int rowNumber)
      throws SQLException {
    var snapshot = new CanesBoxPriceSnapshot();
    snapshot.setId(row.getString("price_snapshot_id"));
    snapshot.setWeekStartDate(row.getObject("week_start_date", LocalDate.class).toString());
    snapshot.setCollectedOn(row.getObject("collected_on", OffsetDateTime.class).toInstant());
    snapshot.setAveragePrice(row.getBigDecimal("average_price"));
    snapshot.setCurrency(row.getString("currency"));
    snapshot.setSuccessfulMetroCount(row.getInt("successful_metro_count"));
    snapshot.setTotalMetroCount(row.getInt("total_metro_count"));
    snapshot.setVerifiedMetroCount(row.getInt("verified_metro_count"));
    snapshot.setProvisionalMetroCount(row.getInt("provisional_metro_count"));
    snapshot.setExcludedMetroCount(row.getInt("excluded_metro_count"));
    return snapshot;
  }

  private static CanesBoxMetroPrice mapMetroPrice(java.sql.ResultSet row) throws SQLException {
    return new CanesBoxMetroPrice(
        row.getString("metro_name"), row.getString("city"), row.getString("region"),
        row.getString("restaurant_ref"), row.getString("restaurant_name"),
        row.getString("address"), row.getString("source_url"), row.getBigDecimal("price"),
        row.getString("currency"), row.getString("status"), row.getString("source_name"),
        row.getString("quality_status"), row.getString("confidence_level"),
        row.getString("raw_response_hash"), row.getString("matched_item_name"),
        row.getString("failure_reason"), row.getString("review_note"),
        row.getObject("collected_on", OffsetDateTime.class).toInstant(),
        instant(row.getObject("source_fetched_on", OffsetDateTime.class)),
        instant(row.getObject("reviewed_on", OffsetDateTime.class)));
  }

  private static void requireSnapshot(CanesBoxPriceSnapshot snapshot) {
    if (snapshot == null || snapshot.getId() == null || snapshot.getId().isBlank()
        || snapshot.getWeekStartDate() == null || snapshot.getCollectedOn() == null
        || snapshot.getAveragePrice() == null || snapshot.getCurrency() == null) {
      throw new IllegalArgumentException(
          "A price snapshot requires identity, week, money, and collection time.");
    }
  }

  private static BigDecimal money(BigDecimal value, String field) {
    if (value.signum() < 0) throw new IllegalArgumentException(field + " cannot be negative.");
    try {
      return value.setScale(2, RoundingMode.UNNECESSARY);
    } catch (ArithmeticException imprecise) {
      throw new IllegalArgumentException(field + " must have cent precision.", imprecise);
    }
  }

  private static OffsetDateTime offset(java.time.Instant value) {
    return value == null ? null : value.atOffset(ZoneOffset.UTC);
  }

  private static java.time.Instant instant(OffsetDateTime value) {
    return value == null ? null : value.toInstant();
  }

  private record MetroRow(String snapshotId, CanesBoxMetroPrice price) {}
}
