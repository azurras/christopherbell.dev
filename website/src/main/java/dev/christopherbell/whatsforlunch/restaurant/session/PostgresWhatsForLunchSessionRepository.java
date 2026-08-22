package dev.christopherbell.whatsforlunch.restaurant.session;

import dev.christopherbell.configuration.persistence.PostgresPersistence;
import dev.christopherbell.configuration.persistence.PostgresqlSchemaNames;
import dev.christopherbell.whatsforlunch.restaurant.model.WhatsForLunchRestaurantResetAudit;
import dev.christopherbell.whatsforlunch.restaurant.model.WhatsForLunchSession;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionOperations;

/** PostgreSQL shared-lunch-session aggregate adapter. */
@PostgresPersistence
public class PostgresWhatsForLunchSessionRepository implements WhatsForLunchSessionRepository {
  private final JdbcClient database;
  private final TransactionOperations transactions;
  final Tables tables;

  public PostgresWhatsForLunchSessionRepository(
      JdbcClient database, PostgresqlSchemaNames schemas, TransactionOperations transactions) {
    this.database = database;
    this.transactions = transactions;
    tables = new Tables(schemas);
  }

  @Override
  public WhatsForLunchSession save(WhatsForLunchSession value) {
    var saved = transactions.execute(ignored -> {
      database.sql("""
              insert into %s (
                lunch_session_id, active_until, created_by_account_id, created_by_username,
                created_on, delete_on, last_updated_on, restaurant_reset_count, revision)
              values (:id, :activeUntil, :creatorId, :creatorUsername, :createdOn,
                :deleteOn, :updatedOn, :resetCount, :revision)
              on conflict (lunch_session_id) do update set
                active_until = excluded.active_until,
                created_by_account_id = excluded.created_by_account_id,
                created_by_username = excluded.created_by_username,
                created_on = excluded.created_on, delete_on = excluded.delete_on,
                last_updated_on = excluded.last_updated_on,
                restaurant_reset_count = excluded.restaurant_reset_count,
                revision = excluded.revision
              """.formatted(tables.session))
          .param("id", value.getId()).param("activeUntil", offset(value.getActiveUntil()))
          .param("creatorId", value.getCreatedByAccountId())
          .param("creatorUsername", value.getCreatedByUsername())
          .param("createdOn", offset(value.getCreatedOn()))
          .param("deleteOn", offset(value.getDeleteOn()))
          .param("updatedOn", offset(value.getLastUpdatedOn()))
          .param("resetCount", value.getRestaurantResetCount())
          .param("revision", value.getRevision()).update();
      replaceChildren(database, tables, value);
      return findById(value.getId()).orElseThrow();
    });
    if (saved == null) throw new IllegalStateException("Lunch session transaction returned no value.");
    return saved;
  }

  static void replaceChildren(JdbcClient database, Tables tables, WhatsForLunchSession value) {
    deleteChildren(database, tables.participant, value.getId());
    var participants = value.getParticipantAccountIds() == null
        ? List.<String>of() : value.getParticipantAccountIds();
    var usernames = value.getParticipantUsernamesByAccountId() == null
        ? Map.<String, String>of() : value.getParticipantUsernamesByAccountId();
    for (int ordinal = 0; ordinal < participants.size(); ordinal++) {
      String accountId = participants.get(ordinal);
      database.sql("""
              insert into %s (lunch_session_id, ordinal, account_id, username)
              values (:id, :ordinal, :accountId, :username)
              """.formatted(tables.participant)).param("id", value.getId())
          .param("ordinal", ordinal).param("accountId", accountId)
          .param("username", usernames.get(accountId)).update();
    }
    deleteChildren(database, tables.restaurant, value.getId());
    var restaurants = value.getRestaurantIds() == null ? List.<String>of() : value.getRestaurantIds();
    for (int ordinal = 0; ordinal < restaurants.size(); ordinal++) {
      database.sql("""
              insert into %s (lunch_session_id, ordinal, restaurant_id)
              values (:id, :ordinal, :restaurantId)
              """.formatted(tables.restaurant)).param("id", value.getId())
          .param("ordinal", ordinal).param("restaurantId", restaurants.get(ordinal)).update();
    }
    deleteChildren(database, tables.vote, value.getId());
    if (value.getVotesByAccountId() != null) {
      value.getVotesByAccountId().forEach((accountId, restaurantId) -> database.sql("""
              insert into %s (lunch_session_id, account_id, restaurant_id)
              values (:id, :accountId, :restaurantId)
              """.formatted(tables.vote)).param("id", value.getId())
          .param("accountId", accountId).param("restaurantId", restaurantId).update());
    }
    deleteChildren(database, tables.resetAudit, value.getId());
    var audits = value.getRestaurantResetAudit() == null
        ? List.<WhatsForLunchRestaurantResetAudit>of() : value.getRestaurantResetAudit();
    for (int ordinal = 0; ordinal < audits.size(); ordinal++) {
      var audit = audits.get(ordinal);
      database.sql("""
              insert into %s (
                lunch_session_id, ordinal, account_id, username, occurred_on, revision)
              values (:id, :ordinal, :accountId, :username, :occurredOn, :revision)
              """.formatted(tables.resetAudit)).param("id", value.getId())
          .param("ordinal", ordinal).param("accountId", audit.accountId())
          .param("username", audit.username()).param("occurredOn", offset(audit.occurredOn()))
          .param("revision", audit.revision()).update();
      for (int restaurantOrdinal = 0;
           restaurantOrdinal < audit.restaurantIds().size(); restaurantOrdinal++) {
        database.sql("""
                insert into %s (
                  lunch_session_id, reset_ordinal, restaurant_ordinal, restaurant_id)
                values (:id, :resetOrdinal, :restaurantOrdinal, :restaurantId)
                """.formatted(tables.resetRestaurant)).param("id", value.getId())
            .param("resetOrdinal", ordinal).param("restaurantOrdinal", restaurantOrdinal)
            .param("restaurantId", audit.restaurantIds().get(restaurantOrdinal)).update();
      }
    }
  }

  @Override
  public Optional<WhatsForLunchSession> findById(String id) {
    return database.sql("select * from %s where lunch_session_id = :id".formatted(tables.session))
        .param("id", id).query(this::map).optional();
  }

  @Override
  public List<WhatsForLunchSession>
      findByParticipantAccountIdsContainingAndDeleteOnAfterOrderByCreatedOnDesc(
          String accountId, Instant now, Pageable pageable) {
    var statement = database.sql("""
            select session.lunch_session_id from %s session
            join %s participant using (lunch_session_id)
            where participant.account_id = :accountId and session.delete_on > :now
            order by session.created_on desc, session.lunch_session_id asc
            limit :limit offset :offset
            """.formatted(tables.session, tables.participant))
        .param("accountId", accountId).param("now", offset(now))
        .param("limit", pageable.isPaged() ? pageable.getPageSize() : Integer.MAX_VALUE)
        .param("offset", pageable.isPaged() ? Math.toIntExact(pageable.getOffset()) : 0);
    return statement.query(String.class).list().stream()
        .map(id -> findById(id).orElseThrow()).toList();
  }

  WhatsForLunchSession lock(String id) {
    return database.sql("""
            select lunch_session_id from %s where lunch_session_id = :id for update
            """.formatted(tables.session)).param("id", id).query(String.class).optional()
        .flatMap(this::findById).orElse(null);
  }

  private WhatsForLunchSession map(ResultSet row, int rowNumber) throws SQLException {
    String id = row.getString("lunch_session_id");
    var participantRows = database.sql("""
            select account_id, username from %s where lunch_session_id = :id order by ordinal
            """.formatted(tables.participant)).param("id", id)
        .query((child, ignored) -> Map.entry(child.getString(1), child.getString(2))).list();
    var usernames = new LinkedHashMap<String, String>();
    participantRows.forEach(entry -> usernames.put(entry.getKey(), entry.getValue()));
    var restaurants = database.sql("""
            select restaurant_id from %s where lunch_session_id = :id order by ordinal
            """.formatted(tables.restaurant)).param("id", id).query(String.class).list();
    var votes = new LinkedHashMap<String, String>();
    database.sql("""
            select account_id, restaurant_id from %s
            where lunch_session_id = :id order by account_id
            """.formatted(tables.vote)).param("id", id)
        .query((child, ignored) -> Map.entry(child.getString(1), child.getString(2))).list()
        .forEach(entry -> votes.put(entry.getKey(), entry.getValue()));
    var audits = database.sql("""
            select * from %s where lunch_session_id = :id order by ordinal
            """.formatted(tables.resetAudit)).param("id", id).query((audit, ignored) -> {
          int ordinal = audit.getInt("ordinal");
          var resetRestaurants = database.sql("""
                  select restaurant_id from %s where lunch_session_id = :id
                    and reset_ordinal = :ordinal order by restaurant_ordinal
                  """.formatted(tables.resetRestaurant)).param("id", id)
              .param("ordinal", ordinal).query(String.class).list();
          return new WhatsForLunchRestaurantResetAudit(
              audit.getLong("revision"), audit.getString("account_id"),
              audit.getString("username"), resetRestaurants,
              audit.getObject("occurred_on", OffsetDateTime.class).toInstant());
        }).list();
    return WhatsForLunchSession.builder().id(id)
        .createdByAccountId(row.getString("created_by_account_id"))
        .createdByUsername(row.getString("created_by_username"))
        .participantAccountIds(participantRows.stream().map(Map.Entry::getKey).toList())
        .participantUsernamesByAccountId(Map.copyOf(usernames)).restaurantIds(restaurants)
        .votesByAccountId(Map.copyOf(votes)).revision(row.getLong("revision"))
        .activeUntil(instant(row, "active_until")).deleteOn(instant(row, "delete_on"))
        .restaurantResetCount(row.getLong("restaurant_reset_count"))
        .restaurantResetAudit(audits).createdOn(instant(row, "created_on"))
        .lastUpdatedOn(instant(row, "last_updated_on")).build();
  }

  private static void deleteChildren(JdbcClient database, String table, String id) {
    database.sql("delete from %s where lunch_session_id = :id".formatted(table))
        .param("id", id).update();
  }

  static OffsetDateTime offset(Instant value) {
    return value == null ? null : value.atOffset(ZoneOffset.UTC);
  }

  private static Instant instant(ResultSet row, String column) throws SQLException {
    var value = row.getObject(column, OffsetDateTime.class);
    return value == null ? null : value.toInstant();
  }

  static final class Tables {
    final String session;
    final String participant;
    final String restaurant;
    final String vote;
    final String resetAudit;
    final String resetRestaurant;

    Tables(PostgresqlSchemaNames schemas) {
      session = schemas.qualifiedTable("lunch", "lunch_session");
      participant = schemas.qualifiedTable("lunch", "lunch_session_participant");
      restaurant = schemas.qualifiedTable("lunch", "lunch_session_restaurant");
      vote = schemas.qualifiedTable("lunch", "lunch_session_vote");
      resetAudit = schemas.qualifiedTable("lunch", "lunch_session_reset_audit");
      resetRestaurant = schemas.qualifiedTable("lunch", "lunch_session_reset_restaurant");
    }
  }
}
