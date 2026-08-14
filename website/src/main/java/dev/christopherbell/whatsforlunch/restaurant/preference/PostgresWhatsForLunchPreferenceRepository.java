package dev.christopherbell.whatsforlunch.restaurant.preference;

import static dev.christopherbell.persistence.jooq.lunch.Tables.LUNCH_PREFERENCE;
import static dev.christopherbell.persistence.jooq.lunch.Tables.LUNCH_PREFERENCE_CUISINE;

import dev.christopherbell.configuration.persistence.PostgresPersistence;
import dev.christopherbell.whatsforlunch.restaurant.model.WhatsForLunchPreference;
import java.util.List;
import java.util.Optional;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;

/** PostgreSQL ordered lunch-preference adapter. */
@PostgresPersistence
public class PostgresWhatsForLunchPreferenceRepository implements WhatsForLunchPreferenceRepository {
  private final DSLContext database;
  public PostgresWhatsForLunchPreferenceRepository(DSLContext database) { this.database = database; }
  @Override public WhatsForLunchPreference save(WhatsForLunchPreference value) {
    return database.transactionResult(configuration -> {
      var transaction = DSL.using(configuration);
      transaction.insertInto(LUNCH_PREFERENCE).set(LUNCH_PREFERENCE.ACCOUNT_ID, value.getAccountId())
          .set(LUNCH_PREFERENCE.RADIUS_MILES, value.getRadiusMiles())
          .onConflict(LUNCH_PREFERENCE.ACCOUNT_ID).doUpdate()
          .set(LUNCH_PREFERENCE.RADIUS_MILES, value.getRadiusMiles()).execute();
      transaction.deleteFrom(LUNCH_PREFERENCE_CUISINE)
          .where(LUNCH_PREFERENCE_CUISINE.ACCOUNT_ID.eq(value.getAccountId())).execute();
      var cuisines = value.getCuisines() == null ? List.<String>of() : value.getCuisines();
      for (int ordinal = 0; ordinal < cuisines.size(); ordinal++) {
        transaction.insertInto(LUNCH_PREFERENCE_CUISINE)
            .set(LUNCH_PREFERENCE_CUISINE.ACCOUNT_ID, value.getAccountId())
            .set(LUNCH_PREFERENCE_CUISINE.ORDINAL, ordinal)
            .set(LUNCH_PREFERENCE_CUISINE.CUISINE, cuisines.get(ordinal)).execute();
      }
      return findById(transaction, value.getAccountId()).orElseThrow();
    });
  }
  @Override public Optional<WhatsForLunchPreference> findById(String id) { return findById(database, id); }
  private static Optional<WhatsForLunchPreference> findById(DSLContext context, String id) {
    return context.selectFrom(LUNCH_PREFERENCE).where(LUNCH_PREFERENCE.ACCOUNT_ID.eq(id))
        .fetchOptional(row -> WhatsForLunchPreference.builder().accountId(id).radiusMiles(row.getRadiusMiles())
            .cuisines(context.select(LUNCH_PREFERENCE_CUISINE.CUISINE).from(LUNCH_PREFERENCE_CUISINE)
                .where(LUNCH_PREFERENCE_CUISINE.ACCOUNT_ID.eq(id)).orderBy(LUNCH_PREFERENCE_CUISINE.ORDINAL)
                .fetch(LUNCH_PREFERENCE_CUISINE.CUISINE)).build());
  }
}
