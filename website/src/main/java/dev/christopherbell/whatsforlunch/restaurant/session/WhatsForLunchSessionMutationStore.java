package dev.christopherbell.whatsforlunch.restaurant.session;

import dev.christopherbell.configuration.mongo.domain.DomainMongoOperationsFactory;
import dev.christopherbell.configuration.mongo.domain.KindScopedMongoOperations;
import dev.christopherbell.whatsforlunch.restaurant.model.WhatsForLunchRestaurantResetAudit;
import dev.christopherbell.whatsforlunch.restaurant.model.WhatsForLunchSession;
import dev.christopherbell.whatsforlunch.restaurant.model.WhatsForLunchSessionRestaurantsRequest;
import java.time.Instant;
import java.util.Objects;
import java.util.regex.Pattern;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;

/** Performs bounded one-document session mutations without whole-document saves. */
@Component
public class WhatsForLunchSessionMutationStore {
  private static final int RESET_AUDIT_LIMIT = 100;
  private static final Pattern SAFE_MAP_KEY = Pattern.compile("[A-Za-z0-9_-]{1,128}");

  private final KindScopedMongoOperations<WhatsForLunchSession> sessions;
  private final WhatsForLunchSessionRepository repository;

  public WhatsForLunchSessionMutationStore(
      DomainMongoOperationsFactory factory,
      WhatsForLunchSessionRepository repository) {
    this.sessions = factory.forType(WhatsForLunchSession.class);
    this.repository = repository;
  }

  /** Atomically joins below the member cap; retries by existing members are no-ops. */
  public Result join(
      String sessionId,
      String accountId,
      String username,
      Instant now,
      int maxMembers
  ) {
    requireMemberLimit(maxMembers);
    var safeAccountId = safeMapKey(accountId);
    var query = activeSession(sessionId, now)
        .addCriteria(Criteria.where("participantAccountIds").ne(safeAccountId))
        .addCriteria(Criteria.where("participantAccountIds." + (maxMembers - 1)).exists(false));
    var update = new Update()
        .addToSet("participantAccountIds", safeAccountId)
        .set("participantUsernamesByAccountId." + safeAccountId, username)
        .set("lastUpdatedOn", now)
        .inc("revision", 1);
    var updated = sessions.findAndUpdate(query, update);
    if (updated.isPresent()) {
      return new Result(Status.UPDATED, updated.orElseThrow());
    }
    return classifyJoin(sessionId, safeAccountId, now, maxMembers);
  }

  /** Atomically writes only the caller's vote entry. */
  public Result vote(String sessionId, String accountId, String restaurantId, Instant now) {
    var safeAccountId = safeMapKey(accountId);
    var query = activeSession(sessionId, now)
        .addCriteria(Criteria.where("participantAccountIds").is(safeAccountId))
        .addCriteria(Criteria.where("restaurantIds").is(restaurantId));
    var update = new Update()
        .set("votesByAccountId." + safeAccountId, restaurantId)
        .set("lastUpdatedOn", now)
        .inc("revision", 1);
    var updated = sessions.findAndUpdate(query, update);
    if (updated.isPresent()) {
      return new Result(Status.UPDATED, updated.orElseThrow());
    }
    return classifyVote(sessionId, safeAccountId, restaurantId, now);
  }

  /** Atomically resets picks only for the host at the expected revision. */
  public Result resetRestaurants(
      String sessionId,
      String accountId,
      String username,
      WhatsForLunchSessionRestaurantsRequest request,
      Instant now
  ) {
    Objects.requireNonNull(request, "request");
    var safeAccountId = safeMapKey(accountId);
    var audit = new WhatsForLunchRestaurantResetAudit(
        Math.incrementExact(request.expectedRevision()),
        safeAccountId,
        username,
        request.restaurantIds(),
        now);
    var query = activeSession(sessionId, now)
        .addCriteria(Criteria.where("createdByAccountId").is(safeAccountId))
        .addCriteria(Criteria.where("revision").is(request.expectedRevision()));
    var update = new Update()
        .set("restaurantIds", request.restaurantIds())
        .set("votesByAccountId", java.util.Map.of())
        .set("lastUpdatedOn", now)
        .inc("revision", 1)
        .inc("restaurantResetCount", 1);
    update.push("restaurantResetAudit").slice(-RESET_AUDIT_LIMIT).each(audit);
    var updated = sessions.findAndUpdate(query, update);
    if (updated.isPresent()) {
      return new Result(Status.UPDATED, updated.orElseThrow());
    }
    return classifyReset(sessionId, safeAccountId, request.expectedRevision(), now);
  }

  private Result classifyJoin(
      String sessionId,
      String accountId,
      Instant now,
      int maxMembers
  ) {
    var current = repository.findById(sessionId).orElse(null);
    if (current == null) {
      return new Result(Status.MISSING, null);
    }
    if (!active(current, now)) {
      return new Result(Status.EXPIRED, current);
    }
    if (current.getParticipantAccountIds() != null
        && current.getParticipantAccountIds().contains(accountId)) {
      return new Result(Status.UNCHANGED, current);
    }
    if (current.getParticipantAccountIds() != null
        && current.getParticipantAccountIds().size() >= maxMembers) {
      return new Result(Status.FULL, current);
    }
    return new Result(Status.CHANGED, current);
  }

  private Result classifyVote(
      String sessionId,
      String accountId,
      String restaurantId,
      Instant now
  ) {
    var current = repository.findById(sessionId).orElse(null);
    if (current == null) {
      return new Result(Status.MISSING, null);
    }
    if (!active(current, now)) {
      return new Result(Status.EXPIRED, current);
    }
    if (current.getParticipantAccountIds() == null
        || !current.getParticipantAccountIds().contains(accountId)) {
      return new Result(Status.NOT_PARTICIPANT, current);
    }
    if (current.getRestaurantIds() == null || !current.getRestaurantIds().contains(restaurantId)) {
      return new Result(Status.INVALID_RESTAURANT, current);
    }
    return new Result(Status.CHANGED, current);
  }

  private Result classifyReset(
      String sessionId,
      String accountId,
      long expectedRevision,
      Instant now
  ) {
    var current = repository.findById(sessionId).orElse(null);
    if (current == null) {
      return new Result(Status.MISSING, null);
    }
    if (!active(current, now)) {
      return new Result(Status.EXPIRED, current);
    }
    if (!accountId.equals(current.getCreatedByAccountId())) {
      return new Result(Status.NOT_HOST, current);
    }
    if (current.getRevision() != expectedRevision) {
      return new Result(Status.CHANGED, current);
    }
    return new Result(Status.CHANGED, current);
  }

  private Query activeSession(String sessionId, Instant now) {
    return new Query(Criteria.where("id").is(sessionId).and("activeUntil").gt(now));
  }

  private boolean active(WhatsForLunchSession session, Instant now) {
    return session.getActiveUntil() == null || now.isBefore(session.getActiveUntil());
  }

  private String safeMapKey(String accountId) {
    if (accountId == null || !SAFE_MAP_KEY.matcher(accountId).matches()) {
      throw new IllegalArgumentException("Account id cannot be used as a Mongo map key.");
    }
    return accountId;
  }

  private void requireMemberLimit(int maxMembers) {
    if (maxMembers < 1 || maxMembers > 100) {
      throw new IllegalArgumentException("Session member limit must be between 1 and 100.");
    }
  }

  /** Stable mutation classifications used by the service's API contract. */
  public enum Status {
    UPDATED,
    UNCHANGED,
    FULL,
    EXPIRED,
    MISSING,
    NOT_PARTICIPANT,
    NOT_HOST,
    INVALID_RESTAURANT,
    CHANGED
  }

  /** Atomic mutation outcome with the latest observed document when available. */
  public record Result(Status status, WhatsForLunchSession session) {}
}
