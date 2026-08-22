package dev.christopherbell.whatsforlunch.restaurant;

import dev.christopherbell.configuration.persistence.PostgresPersistence;
import dev.christopherbell.configuration.persistence.PostgresqlSchemaNames;
import dev.christopherbell.whatsforlunch.restaurant.model.DailyLunchPicks;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionOperations;

/** PostgreSQL ordered daily-lunch-picks adapter. */
@PostgresPersistence
public class PostgresDailyLunchPicksRepository implements DailyLunchPicksRepository {
  private final JdbcClient database;
  private final TransactionOperations transactions;
  private final String picksTable;
  private final String restaurantTable;

  public PostgresDailyLunchPicksRepository(
      JdbcClient database, PostgresqlSchemaNames schemas, TransactionOperations transactions) {
    this.database = database;
    this.transactions = transactions;
    picksTable = schemas.qualifiedTable("lunch", "daily_lunch_picks");
    restaurantTable = schemas.qualifiedTable("lunch", "daily_lunch_pick_restaurant");
  }

  @Override
  public DailyLunchPicks save(DailyLunchPicks picks) {
    return transactions.execute(status -> {
      database.sql("""
              insert into %s (daily_lunch_picks_id, pick_date, generated_on)
              values (:id, :pickDate, :generatedOn)
              on conflict (daily_lunch_picks_id) do update set
                pick_date = excluded.pick_date,
                generated_on = excluded.generated_on
              """.formatted(picksTable))
          .param("id", picks.getId())
          .param("pickDate", LocalDate.parse(picks.getPickDate()))
          .param("generatedOn", picks.getGeneratedOn().atOffset(ZoneOffset.UTC))
          .update();
      database.sql("delete from %s where daily_lunch_picks_id = :id"
              .formatted(restaurantTable))
          .param("id", picks.getId())
          .update();
      var ids = picks.getRestaurantIds() == null ? java.util.List.<String>of() : picks.getRestaurantIds();
      for (int ordinal = 0; ordinal < ids.size(); ordinal++) {
        database.sql("""
                insert into %s (daily_lunch_picks_id, ordinal, restaurant_id)
                values (:id, :ordinal, :restaurantId)
                """.formatted(restaurantTable))
            .param("id", picks.getId())
            .param("ordinal", ordinal)
            .param("restaurantId", ids.get(ordinal))
            .update();
      }
      return findById(picks.getId()).orElseThrow();
    });
  }

  @Override
  public Optional<DailyLunchPicks> findById(String id) {
    return database.sql("""
            select pick_date, generated_on from %s where daily_lunch_picks_id = :id
            """.formatted(picksTable))
        .param("id", id)
        .query((row, rowNumber) -> DailyLunchPicks.builder()
            .id(id)
            .pickDate(row.getObject("pick_date", LocalDate.class).toString())
            .generatedOn(row.getObject("generated_on", OffsetDateTime.class).toInstant())
            .restaurantIds(database.sql("""
                    select restaurant_id from %s
                    where daily_lunch_picks_id = :id order by ordinal
                    """.formatted(restaurantTable))
                .param("id", id)
                .query(String.class)
                .list())
            .build())
        .optional();
  }
}
