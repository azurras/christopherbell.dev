package dev.christopherbell.whatsforlunch.restaurant.session;

import dev.christopherbell.configuration.mongo.domain.DomainMongoOperationsFactory;
import dev.christopherbell.configuration.mongo.domain.KindScopedMongoOperations;
import dev.christopherbell.configuration.persistence.MongoPersistence;
import dev.christopherbell.whatsforlunch.restaurant.model.WhatsForLunchRestaurantResetAudit;
import dev.christopherbell.whatsforlunch.restaurant.model.WhatsForLunchSession;
import dev.christopherbell.whatsforlunch.restaurant.model.WhatsForLunchSessionRestaurantsRequest;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.regex.Pattern;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;

/** Performs bounded one-document session mutations without whole-document saves. */
@Component
@MongoPersistence
public class WhatsForLunchSessionMutationStore implements WhatsForLunchSessionMutationPort {
  private static final int RESET_AUDIT_LIMIT = 100;
  private static final int MIN_MEMBERS = 1;
  private static final int MAX_MEMBERS = 100;
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
  @Override
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
    return sessions.findAndUpdate(query, update)
        .map(session -> new Result(Status.UPDATED, session))
        .orElseGet(() -> classifyJoin(sessionId, safeAccountId, now, maxMembers));
  }

  /** Atomically writes only the caller's vote entry. */
  @Override
  public Result vote(String sessionId, String accountId, String restaurantId, Instant now) {
    var safeAccountId = safeMapKey(accountId);
    var query = activeSession(sessionId, now)
        .addCriteria(Criteria.where("participantAccountIds").is(safeAccountId))
        .addCriteria(Criteria.where("restaurantIds").is(restaurantId));
    var update = new Update()
        .set("votesByAccountId." + safeAccountId, restaurantId)
        .set("lastUpdatedOn", now)
        .inc("revision", 1);
    return sessions.findAndUpdate(query, update)
        .map(session -> new Result(Status.UPDATED, session))
        .orElseGet(() -> classifyVote(sessionId, safeAccountId, restaurantId, now));
  }

  /** Atomically resets picks only for the host at the expected revision. */
  @Override
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
        .set("votesByAccountId", Map.of())
        .set("lastUpdatedOn", now)
        .inc("revision", 1)
        .inc("restaurantResetCount", 1);
    update.push("restaurantResetAudit").slice(-RESET_AUDIT_LIMIT).each(audit);
    return sessions.findAndUpdate(query, update)
        .map(session -> new Result(Status.UPDATED, session))
        .orElseGet(() -> classifyReset(sessionId, safeAccountId, now));
  }

  /** Explains why a join matched nothing, from the session as it is now. */
  private Result classifyJoin(
      String sessionId,
      String accountId,
      Instant now,
      int maxMembers
  ) {
    return classifyExisting(sessionId, now, current -> {
      var participants = current.getParticipantAccountIds();
      if (participants != null && participants.contains(accountId)) {
        return Status.UNCHANGED;
      }
      if (participants != null && participants.size() >= maxMembers) {
        return Status.FULL;
      }
      return Status.CHANGED;
    });
  }

  /** Explains why a vote matched nothing, from the session as it is now. */
  private Result classifyVote(
      String sessionId,
      String accountId,
      String restaurantId,
      Instant now
  ) {
    return classifyExisting(sessionId, now, current -> {
      if (current.getParticipantAccountIds() == null
          || !current.getParticipantAccountIds().contains(accountId)) {
        return Status.NOT_PARTICIPANT;
      }
      if (current.getRestaurantIds() == null || !current.getRestaurantIds().contains(restaurantId)) {
        return Status.INVALID_RESTAURANT;
      }
      return Status.CHANGED;
    });
  }

  /**
   * Explains why a reset matched nothing. A host whose reset missed was racing another change,
   * whether or not the revision has since moved on.
   */
  private Result classifyReset(String sessionId, String accountId, Instant now) {
    return classifyExisting(sessionId, now, current -> accountId.equals(current.getCreatedByAccountId())
        ? Status.CHANGED
        : Status.NOT_HOST);
  }

  /** A missing session or an expired one is classified before the mutation-specific rules. */
  private Result classifyExisting(
      String sessionId,
      Instant now,
      Function<WhatsForLunchSession, Status> classifyActive
  ) {
    return repository.findById(sessionId)
        .map(current -> new Result(active(current, now) ? classifyActive.apply(current) : Status.EXPIRED, current))
        .orElseGet(() -> new Result(Status.MISSING, null));
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
    if (maxMembers < MIN_MEMBERS || maxMembers > MAX_MEMBERS) {
      throw new IllegalArgumentException(
          "Session member limit must be between %d and %d.".formatted(MIN_MEMBERS, MAX_MEMBERS));
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

  /** Atomic mutation outcome; {@code session} is the latest observed document, or null when MISSING. */
  public record Result(Status status, WhatsForLunchSession session) {}
}
