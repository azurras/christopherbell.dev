package dev.christopherbell.whatsforlunch.restaurant.session;

import static dev.christopherbell.persistence.jooq.lunch.Tables.LUNCH_SESSION;
import static dev.christopherbell.persistence.jooq.lunch.Tables.LUNCH_SESSION_PARTICIPANT;
import static dev.christopherbell.persistence.jooq.lunch.Tables.LUNCH_SESSION_RESET_AUDIT;
import static dev.christopherbell.persistence.jooq.lunch.Tables.LUNCH_SESSION_RESET_RESTAURANT;
import static dev.christopherbell.persistence.jooq.lunch.Tables.LUNCH_SESSION_RESTAURANT;
import static dev.christopherbell.persistence.jooq.lunch.Tables.LUNCH_SESSION_VOTE;

import dev.christopherbell.configuration.persistence.PostgresPersistence;
import dev.christopherbell.whatsforlunch.restaurant.model.WhatsForLunchRestaurantResetAudit;
import dev.christopherbell.whatsforlunch.restaurant.model.WhatsForLunchSession;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.springframework.data.domain.Pageable;

/** PostgreSQL shared-lunch-session aggregate adapter. */
@PostgresPersistence
public class PostgresWhatsForLunchSessionRepository implements WhatsForLunchSessionRepository {
  private final DSLContext database;
  public PostgresWhatsForLunchSessionRepository(DSLContext database) { this.database = database; }

  @Override public WhatsForLunchSession save(WhatsForLunchSession value) {
    return database.transactionResult(configuration -> {
      var transaction = DSL.using(configuration);
      transaction.insertInto(LUNCH_SESSION)
          .set(LUNCH_SESSION.LUNCH_SESSION_ID, value.getId())
          .set(LUNCH_SESSION.ACTIVE_UNTIL, offset(value.getActiveUntil()))
          .set(LUNCH_SESSION.CREATED_BY_ACCOUNT_ID, value.getCreatedByAccountId())
          .set(LUNCH_SESSION.CREATED_BY_USERNAME, value.getCreatedByUsername())
          .set(LUNCH_SESSION.CREATED_ON, offset(value.getCreatedOn()))
          .set(LUNCH_SESSION.DELETE_ON, offset(value.getDeleteOn()))
          .set(LUNCH_SESSION.LAST_UPDATED_ON, offset(value.getLastUpdatedOn()))
          .set(LUNCH_SESSION.RESTAURANT_RESET_COUNT, value.getRestaurantResetCount())
          .set(LUNCH_SESSION.REVISION, value.getRevision())
          .onConflict(LUNCH_SESSION.LUNCH_SESSION_ID).doUpdate()
          .set(LUNCH_SESSION.ACTIVE_UNTIL, offset(value.getActiveUntil()))
          .set(LUNCH_SESSION.CREATED_BY_ACCOUNT_ID, value.getCreatedByAccountId())
          .set(LUNCH_SESSION.CREATED_BY_USERNAME, value.getCreatedByUsername())
          .set(LUNCH_SESSION.CREATED_ON, offset(value.getCreatedOn()))
          .set(LUNCH_SESSION.DELETE_ON, offset(value.getDeleteOn()))
          .set(LUNCH_SESSION.LAST_UPDATED_ON, offset(value.getLastUpdatedOn()))
          .set(LUNCH_SESSION.RESTAURANT_RESET_COUNT, value.getRestaurantResetCount())
          .set(LUNCH_SESSION.REVISION, value.getRevision()).execute();
      replaceChildren(transaction, value);
      return findById(transaction, value.getId()).orElseThrow();
    });
  }

  static void replaceChildren(DSLContext transaction, WhatsForLunchSession value) {
    transaction.deleteFrom(LUNCH_SESSION_PARTICIPANT)
        .where(LUNCH_SESSION_PARTICIPANT.LUNCH_SESSION_ID.eq(value.getId())).execute();
    var participants = value.getParticipantAccountIds() == null ? List.<String>of() : value.getParticipantAccountIds();
    var usernames = value.getParticipantUsernamesByAccountId() == null ? java.util.Map.<String, String>of()
        : value.getParticipantUsernamesByAccountId();
    for (int ordinal = 0; ordinal < participants.size(); ordinal++) {
      String accountId = participants.get(ordinal);
      transaction.insertInto(LUNCH_SESSION_PARTICIPANT)
          .set(LUNCH_SESSION_PARTICIPANT.LUNCH_SESSION_ID, value.getId())
          .set(LUNCH_SESSION_PARTICIPANT.ORDINAL, ordinal)
          .set(LUNCH_SESSION_PARTICIPANT.ACCOUNT_ID, accountId)
          .set(LUNCH_SESSION_PARTICIPANT.USERNAME, usernames.get(accountId)).execute();
    }
    transaction.deleteFrom(LUNCH_SESSION_RESTAURANT)
        .where(LUNCH_SESSION_RESTAURANT.LUNCH_SESSION_ID.eq(value.getId())).execute();
    var restaurants = value.getRestaurantIds() == null ? List.<String>of() : value.getRestaurantIds();
    for (int ordinal = 0; ordinal < restaurants.size(); ordinal++) {
      transaction.insertInto(LUNCH_SESSION_RESTAURANT)
          .set(LUNCH_SESSION_RESTAURANT.LUNCH_SESSION_ID, value.getId())
          .set(LUNCH_SESSION_RESTAURANT.ORDINAL, ordinal)
          .set(LUNCH_SESSION_RESTAURANT.RESTAURANT_ID, restaurants.get(ordinal)).execute();
    }
    transaction.deleteFrom(LUNCH_SESSION_VOTE)
        .where(LUNCH_SESSION_VOTE.LUNCH_SESSION_ID.eq(value.getId())).execute();
    if (value.getVotesByAccountId() != null) value.getVotesByAccountId().forEach((accountId, restaurantId) ->
        transaction.insertInto(LUNCH_SESSION_VOTE).set(LUNCH_SESSION_VOTE.LUNCH_SESSION_ID, value.getId())
            .set(LUNCH_SESSION_VOTE.ACCOUNT_ID, accountId).set(LUNCH_SESSION_VOTE.RESTAURANT_ID, restaurantId).execute());
    transaction.deleteFrom(LUNCH_SESSION_RESET_AUDIT)
        .where(LUNCH_SESSION_RESET_AUDIT.LUNCH_SESSION_ID.eq(value.getId())).execute();
    var audits = value.getRestaurantResetAudit() == null ? List.<WhatsForLunchRestaurantResetAudit>of()
        : value.getRestaurantResetAudit();
    for (int ordinal = 0; ordinal < audits.size(); ordinal++) {
      var audit = audits.get(ordinal);
      transaction.insertInto(LUNCH_SESSION_RESET_AUDIT)
          .set(LUNCH_SESSION_RESET_AUDIT.LUNCH_SESSION_ID, value.getId())
          .set(LUNCH_SESSION_RESET_AUDIT.ORDINAL, ordinal)
          .set(LUNCH_SESSION_RESET_AUDIT.ACCOUNT_ID, audit.accountId())
          .set(LUNCH_SESSION_RESET_AUDIT.USERNAME, audit.username())
          .set(LUNCH_SESSION_RESET_AUDIT.OCCURRED_ON, offset(audit.occurredOn()))
          .set(LUNCH_SESSION_RESET_AUDIT.REVISION, audit.revision()).execute();
      for (int restaurantOrdinal = 0; restaurantOrdinal < audit.restaurantIds().size(); restaurantOrdinal++) {
        transaction.insertInto(LUNCH_SESSION_RESET_RESTAURANT)
            .set(LUNCH_SESSION_RESET_RESTAURANT.LUNCH_SESSION_ID, value.getId())
            .set(LUNCH_SESSION_RESET_RESTAURANT.RESET_ORDINAL, ordinal)
            .set(LUNCH_SESSION_RESET_RESTAURANT.RESTAURANT_ORDINAL, restaurantOrdinal)
            .set(LUNCH_SESSION_RESET_RESTAURANT.RESTAURANT_ID, audit.restaurantIds().get(restaurantOrdinal)).execute();
      }
    }
  }

  @Override public Optional<WhatsForLunchSession> findById(String id) { return findById(database, id); }
  static Optional<WhatsForLunchSession> findById(DSLContext context, String id) {
    return context.selectFrom(LUNCH_SESSION).where(LUNCH_SESSION.LUNCH_SESSION_ID.eq(id))
        .fetchOptional(row -> map(context, row));
  }
  @Override public List<WhatsForLunchSession> findByParticipantAccountIdsContainingAndDeleteOnAfterOrderByCreatedOnDesc(
      String accountId, Instant now, Pageable pageable) {
    var ids = contextIds(database, accountId, now, pageable);
    return ids.stream().map(id -> findById(database, id).orElseThrow()).toList();
  }
  private static List<String> contextIds(DSLContext context, String accountId, Instant now, Pageable pageable) {
    var query = context.select(LUNCH_SESSION.LUNCH_SESSION_ID).from(LUNCH_SESSION)
        .join(LUNCH_SESSION_PARTICIPANT).using(LUNCH_SESSION.LUNCH_SESSION_ID)
        .where(LUNCH_SESSION_PARTICIPANT.ACCOUNT_ID.eq(accountId)
            .and(LUNCH_SESSION.DELETE_ON.gt(offset(now))))
        .orderBy(LUNCH_SESSION.CREATED_ON.desc(), LUNCH_SESSION.LUNCH_SESSION_ID.asc());
    return pageable.isPaged()
        ? query.limit(pageable.getPageSize()).offset(Math.toIntExact(pageable.getOffset())).fetch(LUNCH_SESSION.LUNCH_SESSION_ID)
        : query.fetch(LUNCH_SESSION.LUNCH_SESSION_ID);
  }

  private static WhatsForLunchSession map(DSLContext context,
      dev.christopherbell.persistence.jooq.lunch.tables.records.LunchSessionRecord row) {
    var participants = context.selectFrom(LUNCH_SESSION_PARTICIPANT)
        .where(LUNCH_SESSION_PARTICIPANT.LUNCH_SESSION_ID.eq(row.getLunchSessionId()))
        .orderBy(LUNCH_SESSION_PARTICIPANT.ORDINAL).fetch();
    var usernames = new LinkedHashMap<String, String>();
    participants.forEach(value -> usernames.put(value.getAccountId(), value.getUsername()));
    var restaurants = context.select(LUNCH_SESSION_RESTAURANT.RESTAURANT_ID).from(LUNCH_SESSION_RESTAURANT)
        .where(LUNCH_SESSION_RESTAURANT.LUNCH_SESSION_ID.eq(row.getLunchSessionId()))
        .orderBy(LUNCH_SESSION_RESTAURANT.ORDINAL).fetch(LUNCH_SESSION_RESTAURANT.RESTAURANT_ID);
    var votes = new LinkedHashMap<String, String>();
    context.selectFrom(LUNCH_SESSION_VOTE).where(LUNCH_SESSION_VOTE.LUNCH_SESSION_ID.eq(row.getLunchSessionId()))
        .orderBy(LUNCH_SESSION_VOTE.ACCOUNT_ID)
        .forEach(value -> votes.put(value.getAccountId(), value.getRestaurantId()));
    var audits = context.selectFrom(LUNCH_SESSION_RESET_AUDIT)
        .where(LUNCH_SESSION_RESET_AUDIT.LUNCH_SESSION_ID.eq(row.getLunchSessionId()))
        .orderBy(LUNCH_SESSION_RESET_AUDIT.ORDINAL).fetch(value -> new WhatsForLunchRestaurantResetAudit(
            value.getRevision(), value.getAccountId(), value.getUsername(),
            context.select(LUNCH_SESSION_RESET_RESTAURANT.RESTAURANT_ID).from(LUNCH_SESSION_RESET_RESTAURANT)
                .where(LUNCH_SESSION_RESET_RESTAURANT.LUNCH_SESSION_ID.eq(row.getLunchSessionId())
                    .and(LUNCH_SESSION_RESET_RESTAURANT.RESET_ORDINAL.eq(value.getOrdinal())))
                .orderBy(LUNCH_SESSION_RESET_RESTAURANT.RESTAURANT_ORDINAL)
                .fetch(LUNCH_SESSION_RESET_RESTAURANT.RESTAURANT_ID), value.getOccurredOn().toInstant()));
    return WhatsForLunchSession.builder().id(row.getLunchSessionId())
        .createdByAccountId(row.getCreatedByAccountId()).createdByUsername(row.getCreatedByUsername())
        .participantAccountIds(participants.map(value -> value.getAccountId()))
        .participantUsernamesByAccountId(java.util.Map.copyOf(usernames)).restaurantIds(restaurants)
        .votesByAccountId(java.util.Map.copyOf(votes)).revision(row.getRevision())
        .activeUntil(row.getActiveUntil().toInstant()).deleteOn(row.getDeleteOn().toInstant())
        .restaurantResetCount(row.getRestaurantResetCount()).restaurantResetAudit(audits)
        .createdOn(row.getCreatedOn().toInstant()).lastUpdatedOn(row.getLastUpdatedOn().toInstant()).build();
  }
  static java.time.OffsetDateTime offset(Instant value) { return value == null ? null : value.atOffset(ZoneOffset.UTC); }
}
