package dev.christopherbell.whatsforlunch.restaurant;

import static dev.christopherbell.persistence.jooq.lunch.Tables.DAILY_LUNCH_PICKS;
import static dev.christopherbell.persistence.jooq.lunch.Tables.DAILY_LUNCH_PICK_RESTAURANT;

import dev.christopherbell.configuration.persistence.PostgresPersistence;
import dev.christopherbell.whatsforlunch.restaurant.model.DailyLunchPicks;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Optional;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;

/** PostgreSQL ordered daily-lunch-picks adapter. */
@PostgresPersistence
public class PostgresDailyLunchPicksRepository implements DailyLunchPicksRepository {
  private final DSLContext database;
  public PostgresDailyLunchPicksRepository(DSLContext database) { this.database = database; }

  @Override public DailyLunchPicks save(DailyLunchPicks picks) {
    return database.transactionResult(configuration -> {
      var transaction = DSL.using(configuration);
      transaction.insertInto(DAILY_LUNCH_PICKS).set(DAILY_LUNCH_PICKS.DAILY_LUNCH_PICKS_ID, picks.getId())
          .set(DAILY_LUNCH_PICKS.PICK_DATE, LocalDate.parse(picks.getPickDate()))
          .set(DAILY_LUNCH_PICKS.GENERATED_ON, picks.getGeneratedOn().atOffset(ZoneOffset.UTC))
          .onConflict(DAILY_LUNCH_PICKS.DAILY_LUNCH_PICKS_ID).doUpdate()
          .set(DAILY_LUNCH_PICKS.PICK_DATE, LocalDate.parse(picks.getPickDate()))
          .set(DAILY_LUNCH_PICKS.GENERATED_ON, picks.getGeneratedOn().atOffset(ZoneOffset.UTC)).execute();
      transaction.deleteFrom(DAILY_LUNCH_PICK_RESTAURANT)
          .where(DAILY_LUNCH_PICK_RESTAURANT.DAILY_LUNCH_PICKS_ID.eq(picks.getId())).execute();
      var ids = picks.getRestaurantIds() == null ? java.util.List.<String>of() : picks.getRestaurantIds();
      for (int ordinal = 0; ordinal < ids.size(); ordinal++) {
        transaction.insertInto(DAILY_LUNCH_PICK_RESTAURANT)
            .set(DAILY_LUNCH_PICK_RESTAURANT.DAILY_LUNCH_PICKS_ID, picks.getId())
            .set(DAILY_LUNCH_PICK_RESTAURANT.ORDINAL, ordinal)
            .set(DAILY_LUNCH_PICK_RESTAURANT.RESTAURANT_ID, ids.get(ordinal)).execute();
      }
      return findById(transaction, picks.getId()).orElseThrow();
    });
  }
  @Override public Optional<DailyLunchPicks> findById(String id) { return findById(database, id); }
  private static Optional<DailyLunchPicks> findById(DSLContext context, String id) {
    return context.selectFrom(DAILY_LUNCH_PICKS).where(DAILY_LUNCH_PICKS.DAILY_LUNCH_PICKS_ID.eq(id))
        .fetchOptional(row -> DailyLunchPicks.builder().id(row.getDailyLunchPicksId())
            .pickDate(row.getPickDate().toString()).generatedOn(row.getGeneratedOn().toInstant())
            .restaurantIds(context.select(DAILY_LUNCH_PICK_RESTAURANT.RESTAURANT_ID)
                .from(DAILY_LUNCH_PICK_RESTAURANT)
                .where(DAILY_LUNCH_PICK_RESTAURANT.DAILY_LUNCH_PICKS_ID.eq(id))
                .orderBy(DAILY_LUNCH_PICK_RESTAURANT.ORDINAL)
                .fetch(DAILY_LUNCH_PICK_RESTAURANT.RESTAURANT_ID)).build());
  }
}
