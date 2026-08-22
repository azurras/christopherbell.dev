package dev.christopherbell.whatsforlunch.restaurant.session;

import dev.christopherbell.configuration.persistence.PostgresPersistence;
import dev.christopherbell.configuration.persistence.PostgresqlSchemaNames;
import dev.christopherbell.whatsforlunch.restaurant.model.WhatsForLunchSession;
import dev.christopherbell.whatsforlunch.restaurant.model.WhatsForLunchSessionRestaurantsRequest;
import java.time.Instant;
import java.util.Objects;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionOperations;

/** PostgreSQL row-locked shared-session mutation adapter. */
@PostgresPersistence
public class PostgresWhatsForLunchSessionMutationStore implements WhatsForLunchSessionMutationPort {
  private static final int RESET_AUDIT_LIMIT = 100;
  private final JdbcClient database;
  private final TransactionOperations transactions;
  private final PostgresWhatsForLunchSessionRepository sessions;
  private final PostgresWhatsForLunchSessionRepository.Tables tables;

  public PostgresWhatsForLunchSessionMutationStore(
      JdbcClient database, PostgresqlSchemaNames schemas, TransactionOperations transactions) {
    this.database = database;
    this.transactions = transactions;
    sessions = new PostgresWhatsForLunchSessionRepository(database, schemas, transactions);
    tables = sessions.tables;
  }

  @Override
  public WhatsForLunchSessionMutationStore.Result join(
      String sessionId, String accountId, String username, Instant now, int maxMembers) {
    requireMemberLimit(maxMembers);
    requireIdentity(accountId);
    return inTransaction(sessionId, current -> {
      var status = classifyJoin(current, accountId, now, maxMembers);
      if (status != WhatsForLunchSessionMutationStore.Status.UPDATED) return result(status, current);
      database.sql("""
              insert into %s (lunch_session_id, ordinal, account_id, username)
              values (:id, :ordinal, :accountId, :username)
              """.formatted(tables.participant)).param("id", sessionId)
          .param("ordinal", current.getParticipantAccountIds().size())
          .param("accountId", accountId).param("username", username).update();
      touch(sessionId, now, 1, 0);
      return updated(sessionId);
    });
  }

  @Override
  public WhatsForLunchSessionMutationStore.Result vote(
      String sessionId, String accountId, String restaurantId, Instant now) {
    requireIdentity(accountId);
    return inTransaction(sessionId, current -> {
      var status = classifyVote(current, accountId, restaurantId, now);
      if (status != WhatsForLunchSessionMutationStore.Status.UPDATED) return result(status, current);
      database.sql("""
              insert into %s (lunch_session_id, account_id, restaurant_id)
              values (:id, :accountId, :restaurantId)
              on conflict (lunch_session_id, account_id) do update
                set restaurant_id = excluded.restaurant_id
              """.formatted(tables.vote)).param("id", sessionId)
          .param("accountId", accountId).param("restaurantId", restaurantId).update();
      touch(sessionId, now, 1, 0);
      return updated(sessionId);
    });
  }

  @Override
  public WhatsForLunchSessionMutationStore.Result resetRestaurants(
      String sessionId, String accountId, String username,
      WhatsForLunchSessionRestaurantsRequest request, Instant now) {
    Objects.requireNonNull(request, "request");
    requireIdentity(accountId);
    return inTransaction(sessionId, current -> {
      var status = classifyReset(current, accountId, request.expectedRevision(), now);
      if (status != WhatsForLunchSessionMutationStore.Status.UPDATED) return result(status, current);
      delete(tables.vote, sessionId);
      delete(tables.restaurant, sessionId);
      for (int ordinal = 0; ordinal < request.restaurantIds().size(); ordinal++) {
        database.sql("""
                insert into %s (lunch_session_id, ordinal, restaurant_id)
                values (:id, :ordinal, :restaurantId)
                """.formatted(tables.restaurant)).param("id", sessionId)
            .param("ordinal", ordinal).param("restaurantId", request.restaurantIds().get(ordinal))
            .update();
      }
      int auditOrdinal = database.sql("""
              select coalesce(max(ordinal), -1) + 1 from %s where lunch_session_id = :id
              """.formatted(tables.resetAudit)).param("id", sessionId)
          .query(Integer.class).single();
      long revision = Math.incrementExact(request.expectedRevision());
      database.sql("""
              insert into %s (
                lunch_session_id, ordinal, account_id, username, occurred_on, revision)
              values (:id, :ordinal, :accountId, :username, :occurredOn, :revision)
              """.formatted(tables.resetAudit)).param("id", sessionId)
          .param("ordinal", auditOrdinal).param("accountId", accountId).param("username", username)
          .param("occurredOn", PostgresWhatsForLunchSessionRepository.offset(now))
          .param("revision", revision).update();
      for (int ordinal = 0; ordinal < request.restaurantIds().size(); ordinal++) {
        database.sql("""
                insert into %s (
                  lunch_session_id, reset_ordinal, restaurant_ordinal, restaurant_id)
                values (:id, :resetOrdinal, :restaurantOrdinal, :restaurantId)
                """.formatted(tables.resetRestaurant)).param("id", sessionId)
            .param("resetOrdinal", auditOrdinal).param("restaurantOrdinal", ordinal)
            .param("restaurantId", request.restaurantIds().get(ordinal)).update();
      }
      if (auditOrdinal >= RESET_AUDIT_LIMIT) {
        database.sql("""
                delete from %s where lunch_session_id = :id and ordinal <= :lastRemoved
                """.formatted(tables.resetAudit)).param("id", sessionId)
            .param("lastRemoved", auditOrdinal - RESET_AUDIT_LIMIT).update();
      }
      touch(sessionId, now, 1, 1);
      return updated(sessionId);
    });
  }

  private WhatsForLunchSessionMutationStore.Result inTransaction(
      String sessionId,
      java.util.function.Function<WhatsForLunchSession, WhatsForLunchSessionMutationStore.Result> effect) {
    var value = transactions.execute(ignored -> effect.apply(sessions.lock(sessionId)));
    if (value == null) throw new IllegalStateException("Lunch mutation transaction returned no value.");
    return value;
  }

  private WhatsForLunchSessionMutationStore.Result updated(String id) {
    return result(WhatsForLunchSessionMutationStore.Status.UPDATED,
        sessions.findById(id).orElseThrow());
  }

  private static WhatsForLunchSessionMutationStore.Result result(
      WhatsForLunchSessionMutationStore.Status status, WhatsForLunchSession session) {
    return new WhatsForLunchSessionMutationStore.Result(status, session);
  }

  private void touch(String id, Instant now, long revision, long resets) {
    database.sql("""
            update %s set last_updated_on = :now, revision = revision + :revision,
              restaurant_reset_count = restaurant_reset_count + :resets
            where lunch_session_id = :id
            """.formatted(tables.session)).param("now", PostgresWhatsForLunchSessionRepository.offset(now))
        .param("revision", revision).param("resets", resets).param("id", id).update();
  }

  private void delete(String table, String id) {
    database.sql("delete from %s where lunch_session_id = :id".formatted(table))
        .param("id", id).update();
  }

  private static WhatsForLunchSessionMutationStore.Status classifyJoin(
      WhatsForLunchSession current, String accountId, Instant now, int maxMembers) {
    if (current == null) return WhatsForLunchSessionMutationStore.Status.MISSING;
    if (!active(current, now)) return WhatsForLunchSessionMutationStore.Status.EXPIRED;
    if (current.getParticipantAccountIds().contains(accountId)) {
      return WhatsForLunchSessionMutationStore.Status.UNCHANGED;
    }
    if (current.getParticipantAccountIds().size() >= maxMembers) {
      return WhatsForLunchSessionMutationStore.Status.FULL;
    }
    return WhatsForLunchSessionMutationStore.Status.UPDATED;
  }

  private static WhatsForLunchSessionMutationStore.Status classifyVote(
      WhatsForLunchSession current, String accountId, String restaurantId, Instant now) {
    if (current == null) return WhatsForLunchSessionMutationStore.Status.MISSING;
    if (!active(current, now)) return WhatsForLunchSessionMutationStore.Status.EXPIRED;
    if (!current.getParticipantAccountIds().contains(accountId)) {
      return WhatsForLunchSessionMutationStore.Status.NOT_PARTICIPANT;
    }
    if (!current.getRestaurantIds().contains(restaurantId)) {
      return WhatsForLunchSessionMutationStore.Status.INVALID_RESTAURANT;
    }
    return WhatsForLunchSessionMutationStore.Status.UPDATED;
  }

  private static WhatsForLunchSessionMutationStore.Status classifyReset(
      WhatsForLunchSession current, String accountId, long expectedRevision, Instant now) {
    if (current == null) return WhatsForLunchSessionMutationStore.Status.MISSING;
    if (!active(current, now)) return WhatsForLunchSessionMutationStore.Status.EXPIRED;
    if (!accountId.equals(current.getCreatedByAccountId())) {
      return WhatsForLunchSessionMutationStore.Status.NOT_HOST;
    }
    if (current.getRevision() != expectedRevision) {
      return WhatsForLunchSessionMutationStore.Status.CHANGED;
    }
    return WhatsForLunchSessionMutationStore.Status.UPDATED;
  }

  private static boolean active(WhatsForLunchSession current, Instant now) {
    return current.getActiveUntil() == null || now.isBefore(current.getActiveUntil());
  }

  private static void requireIdentity(String accountId) {
    if (accountId == null || !accountId.matches("[A-Za-z0-9_-]{1,128}")) {
      throw new IllegalArgumentException("Account id is invalid.");
    }
  }

  private static void requireMemberLimit(int maxMembers) {
    if (maxMembers < 1 || maxMembers > 100) {
      throw new IllegalArgumentException("Session member limit must be between 1 and 100.");
    }
  }
}
