package dev.christopherbell.whatsforlunch.restaurant.preference;

import dev.christopherbell.configuration.persistence.PostgresPersistence;
import dev.christopherbell.configuration.persistence.PostgresqlSchemaNames;
import dev.christopherbell.whatsforlunch.restaurant.model.WhatsForLunchPreference;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionOperations;

/** PostgreSQL ordered lunch-preference adapter. */
@PostgresPersistence
public class PostgresWhatsForLunchPreferenceRepository implements WhatsForLunchPreferenceRepository {
  private final JdbcClient database;
  private final TransactionOperations transactions;
  private final String preferenceTable;
  private final String cuisineTable;

  public PostgresWhatsForLunchPreferenceRepository(
      JdbcClient database, PostgresqlSchemaNames schemas, TransactionOperations transactions) {
    this.database = database;
    this.transactions = transactions;
    preferenceTable = schemas.qualifiedTable("lunch", "lunch_preference");
    cuisineTable = schemas.qualifiedTable("lunch", "lunch_preference_cuisine");
  }

  @Override
  public WhatsForLunchPreference save(WhatsForLunchPreference value) {
    return transactions.execute(status -> {
      database.sql("""
              insert into %s (account_id, radius_miles) values (:accountId, :radiusMiles)
              on conflict (account_id) do update set radius_miles = excluded.radius_miles
              """.formatted(preferenceTable))
          .param("accountId", value.getAccountId())
          .param("radiusMiles", value.getRadiusMiles(), java.sql.Types.INTEGER)
          .update();
      database.sql("delete from %s where account_id = :accountId".formatted(cuisineTable))
          .param("accountId", value.getAccountId())
          .update();
      var cuisines = value.getCuisines() == null ? List.<String>of() : value.getCuisines();
      for (int ordinal = 0; ordinal < cuisines.size(); ordinal++) {
        database.sql("""
                insert into %s (account_id, ordinal, cuisine)
                values (:accountId, :ordinal, :cuisine)
                """.formatted(cuisineTable))
            .param("accountId", value.getAccountId())
            .param("ordinal", ordinal)
            .param("cuisine", cuisines.get(ordinal))
            .update();
      }
      return findById(value.getAccountId()).orElseThrow();
    });
  }

  @Override
  public Optional<WhatsForLunchPreference> findById(String id) {
    return database.sql("select radius_miles from %s where account_id = :id"
            .formatted(preferenceTable))
        .param("id", id)
        .query((row, rowNumber) -> WhatsForLunchPreference.builder()
            .accountId(id)
            .radiusMiles(row.getObject("radius_miles", Integer.class))
            .cuisines(database.sql("""
                    select cuisine from %s where account_id = :id order by ordinal
                    """.formatted(cuisineTable))
                .param("id", id)
                .query(String.class)
                .list())
            .build())
        .optional();
  }
}
