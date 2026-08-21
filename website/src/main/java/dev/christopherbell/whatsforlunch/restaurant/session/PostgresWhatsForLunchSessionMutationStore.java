package dev.christopherbell.whatsforlunch.restaurant.session;

import static dev.christopherbell.persistence.jooq.lunch.Tables.LUNCH_SESSION;
import static dev.christopherbell.persistence.jooq.lunch.Tables.LUNCH_SESSION_PARTICIPANT;
import static dev.christopherbell.persistence.jooq.lunch.Tables.LUNCH_SESSION_RESET_AUDIT;
import static dev.christopherbell.persistence.jooq.lunch.Tables.LUNCH_SESSION_RESET_RESTAURANT;
import static dev.christopherbell.persistence.jooq.lunch.Tables.LUNCH_SESSION_RESTAURANT;
import static dev.christopherbell.persistence.jooq.lunch.Tables.LUNCH_SESSION_VOTE;

import dev.christopherbell.configuration.persistence.PostgresPersistence;
import dev.christopherbell.whatsforlunch.restaurant.model.WhatsForLunchSession;
import dev.christopherbell.whatsforlunch.restaurant.model.WhatsForLunchSessionRestaurantsRequest;
import java.time.Instant;
import java.util.Objects;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;

/** PostgreSQL row-locked shared-session mutation adapter. */
@PostgresPersistence
public class PostgresWhatsForLunchSessionMutationStore implements WhatsForLunchSessionMutationPort {
  private static final int RESET_AUDIT_LIMIT = 100;
  private final DSLContext database;
  public PostgresWhatsForLunchSessionMutationStore(DSLContext database) { this.database = database; }

  @Override public WhatsForLunchSessionMutationStore.Result join(
      String sessionId, String accountId, String username, Instant now, int maxMembers) {
    requireMemberLimit(maxMembers);
    requireIdentity(accountId);
    return database.transactionResult(configuration -> {
      var transaction = DSL.using(configuration);
      var current = lock(transaction, sessionId);
      var classification = classifyJoin(current, accountId, now, maxMembers);
      if (classification != WhatsForLunchSessionMutationStore.Status.UPDATED) {
        return new WhatsForLunchSessionMutationStore.Result(classification, current);
      }
      int ordinal = current.getParticipantAccountIds().size();
      transaction.insertInto(LUNCH_SESSION_PARTICIPANT)
          .set(LUNCH_SESSION_PARTICIPANT.LUNCH_SESSION_ID, sessionId)
          .set(LUNCH_SESSION_PARTICIPANT.ORDINAL, ordinal)
          .set(LUNCH_SESSION_PARTICIPANT.ACCOUNT_ID, accountId)
          .set(LUNCH_SESSION_PARTICIPANT.USERNAME, username).execute();
      touch(transaction, sessionId, now, 1, 0);
      return updated(transaction, sessionId);
    });
  }

  @Override public WhatsForLunchSessionMutationStore.Result vote(
      String sessionId, String accountId, String restaurantId, Instant now) {
    requireIdentity(accountId);
    return database.transactionResult(configuration -> {
      var transaction = DSL.using(configuration);
      var current = lock(transaction, sessionId);
      var status = classifyVote(current, accountId, restaurantId, now);
      if (status != WhatsForLunchSessionMutationStore.Status.UPDATED) {
        return new WhatsForLunchSessionMutationStore.Result(status, current);
      }
      transaction.insertInto(LUNCH_SESSION_VOTE)
          .set(LUNCH_SESSION_VOTE.LUNCH_SESSION_ID, sessionId)
          .set(LUNCH_SESSION_VOTE.ACCOUNT_ID, accountId)
          .set(LUNCH_SESSION_VOTE.RESTAURANT_ID, restaurantId)
          .onConflict(LUNCH_SESSION_VOTE.LUNCH_SESSION_ID, LUNCH_SESSION_VOTE.ACCOUNT_ID).doUpdate()
          .set(LUNCH_SESSION_VOTE.RESTAURANT_ID, restaurantId).execute();
      touch(transaction, sessionId, now, 1, 0);
      return updated(transaction, sessionId);
    });
  }

  @Override public WhatsForLunchSessionMutationStore.Result resetRestaurants(
      String sessionId, String accountId, String username,
      WhatsForLunchSessionRestaurantsRequest request, Instant now) {
    Objects.requireNonNull(request, "request");
    requireIdentity(accountId);
    return database.transactionResult(configuration -> {
      var transaction = DSL.using(configuration);
      var current = lock(transaction, sessionId);
      var status = classifyReset(current, accountId, request.expectedRevision(), now);
      if (status != WhatsForLunchSessionMutationStore.Status.UPDATED) {
        return new WhatsForLunchSessionMutationStore.Result(status, current);
      }
      transaction.deleteFrom(LUNCH_SESSION_VOTE).where(LUNCH_SESSION_VOTE.LUNCH_SESSION_ID.eq(sessionId)).execute();
      transaction.deleteFrom(LUNCH_SESSION_RESTAURANT)
          .where(LUNCH_SESSION_RESTAURANT.LUNCH_SESSION_ID.eq(sessionId)).execute();
      for (int ordinal = 0; ordinal < request.restaurantIds().size(); ordinal++) {
        transaction.insertInto(LUNCH_SESSION_RESTAURANT)
            .set(LUNCH_SESSION_RESTAURANT.LUNCH_SESSION_ID, sessionId)
            .set(LUNCH_SESSION_RESTAURANT.ORDINAL, ordinal)
            .set(LUNCH_SESSION_RESTAURANT.RESTAURANT_ID, request.restaurantIds().get(ordinal)).execute();
      }
      int auditOrdinal = transaction.select(DSL.coalesce(DSL.max(LUNCH_SESSION_RESET_AUDIT.ORDINAL), -1).plus(1))
          .from(LUNCH_SESSION_RESET_AUDIT).where(LUNCH_SESSION_RESET_AUDIT.LUNCH_SESSION_ID.eq(sessionId)).fetchSingle().value1();
      long revision = Math.incrementExact(request.expectedRevision());
      transaction.insertInto(LUNCH_SESSION_RESET_AUDIT)
          .set(LUNCH_SESSION_RESET_AUDIT.LUNCH_SESSION_ID, sessionId)
          .set(LUNCH_SESSION_RESET_AUDIT.ORDINAL, auditOrdinal)
          .set(LUNCH_SESSION_RESET_AUDIT.ACCOUNT_ID, accountId)
          .set(LUNCH_SESSION_RESET_AUDIT.USERNAME, username)
          .set(LUNCH_SESSION_RESET_AUDIT.OCCURRED_ON, PostgresWhatsForLunchSessionRepository.offset(now))
          .set(LUNCH_SESSION_RESET_AUDIT.REVISION, revision).execute();
      for (int ordinal = 0; ordinal < request.restaurantIds().size(); ordinal++) {
        transaction.insertInto(LUNCH_SESSION_RESET_RESTAURANT)
            .set(LUNCH_SESSION_RESET_RESTAURANT.LUNCH_SESSION_ID, sessionId)
            .set(LUNCH_SESSION_RESET_RESTAURANT.RESET_ORDINAL, auditOrdinal)
            .set(LUNCH_SESSION_RESET_RESTAURANT.RESTAURANT_ORDINAL, ordinal)
            .set(LUNCH_SESSION_RESET_RESTAURANT.RESTAURANT_ID, request.restaurantIds().get(ordinal)).execute();
      }
      if (auditOrdinal >= RESET_AUDIT_LIMIT) {
        transaction.deleteFrom(LUNCH_SESSION_RESET_AUDIT)
            .where(LUNCH_SESSION_RESET_AUDIT.LUNCH_SESSION_ID.eq(sessionId)
                .and(LUNCH_SESSION_RESET_AUDIT.ORDINAL.le(auditOrdinal - RESET_AUDIT_LIMIT))).execute();
      }
      touch(transaction, sessionId, now, 1, 1);
      return updated(transaction, sessionId);
    });
  }

  private static WhatsForLunchSession lock(DSLContext transaction, String id) {
    var row = transaction.selectFrom(LUNCH_SESSION).where(LUNCH_SESSION.LUNCH_SESSION_ID.eq(id))
        .forUpdate().fetchOne();
    return row == null ? null : PostgresWhatsForLunchSessionRepository.findById(transaction, id).orElseThrow();
  }
  private static WhatsForLunchSessionMutationStore.Result updated(DSLContext transaction, String id) {
    return new WhatsForLunchSessionMutationStore.Result(WhatsForLunchSessionMutationStore.Status.UPDATED,
        PostgresWhatsForLunchSessionRepository.findById(transaction, id).orElseThrow());
  }
  private static void touch(DSLContext transaction, String id, Instant now, long revision, long resets) {
    transaction.update(LUNCH_SESSION).set(LUNCH_SESSION.LAST_UPDATED_ON,
            PostgresWhatsForLunchSessionRepository.offset(now))
        .set(LUNCH_SESSION.REVISION, LUNCH_SESSION.REVISION.plus(revision))
        .set(LUNCH_SESSION.RESTAURANT_RESET_COUNT, LUNCH_SESSION.RESTAURANT_RESET_COUNT.plus(resets))
        .where(LUNCH_SESSION.LUNCH_SESSION_ID.eq(id)).execute();
  }
  private static WhatsForLunchSessionMutationStore.Status classifyJoin(
      WhatsForLunchSession current, String accountId, Instant now, int maxMembers) {
    if (current == null) return WhatsForLunchSessionMutationStore.Status.MISSING;
    if (!active(current, now)) return WhatsForLunchSessionMutationStore.Status.EXPIRED;
    if (current.getParticipantAccountIds().contains(accountId)) return WhatsForLunchSessionMutationStore.Status.UNCHANGED;
    if (current.getParticipantAccountIds().size() >= maxMembers) return WhatsForLunchSessionMutationStore.Status.FULL;
    return WhatsForLunchSessionMutationStore.Status.UPDATED;
  }
  private static WhatsForLunchSessionMutationStore.Status classifyVote(
      WhatsForLunchSession current, String accountId, String restaurantId, Instant now) {
    if (current == null) return WhatsForLunchSessionMutationStore.Status.MISSING;
    if (!active(current, now)) return WhatsForLunchSessionMutationStore.Status.EXPIRED;
    if (!current.getParticipantAccountIds().contains(accountId)) return WhatsForLunchSessionMutationStore.Status.NOT_PARTICIPANT;
    if (!current.getRestaurantIds().contains(restaurantId)) return WhatsForLunchSessionMutationStore.Status.INVALID_RESTAURANT;
    return WhatsForLunchSessionMutationStore.Status.UPDATED;
  }
  private static WhatsForLunchSessionMutationStore.Status classifyReset(
      WhatsForLunchSession current, String accountId, long expectedRevision, Instant now) {
    if (current == null) return WhatsForLunchSessionMutationStore.Status.MISSING;
    if (!active(current, now)) return WhatsForLunchSessionMutationStore.Status.EXPIRED;
    if (!accountId.equals(current.getCreatedByAccountId())) return WhatsForLunchSessionMutationStore.Status.NOT_HOST;
    if (current.getRevision() != expectedRevision) return WhatsForLunchSessionMutationStore.Status.CHANGED;
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
    if (maxMembers < 1 || maxMembers > 100) throw new IllegalArgumentException("Session member limit must be between 1 and 100.");
  }
}
