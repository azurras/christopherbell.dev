package dev.christopherbell.whatsforlunch.restaurant.session;

import dev.christopherbell.whatsforlunch.restaurant.model.WhatsForLunchSession;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.bson.Document;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.BasicQuery;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Repository;

/** Owns the atomic WFL session-membership transition and its capacity invariant. */
@Repository
@RequiredArgsConstructor
public class WhatsForLunchSessionMemberships {
  private final MongoTemplate mongo;

  /**
   * Adds an account exactly once when the session has capacity.
   *
   * <p>The document query and update run as one MongoDB find-and-modify operation, so concurrent
   * joins cannot both consume the final remaining place.</p>
   */
  public SessionJoinOutcome joinIfCapacityRemains(
      String sessionId,
      String accountId,
      String username,
      int maxParticipants
  ) {
    var query = new BasicQuery(new Document("_id", sessionId)
        .append("participantAccountIds", new Document("$ne", accountId))
        .append("$expr", new Document("$lt", List.of(
            new Document("$size", new Document("$ifNull", List.of("$participantAccountIds", List.of()))),
            maxParticipants))));
    var update = new Update()
        .addToSet("participantAccountIds", accountId)
        .set("participantUsernamesByAccountId." + accountId, username)
        .set("lastUpdatedOn", Instant.now());
    var joined = mongo.findAndModify(
        query,
        update,
        FindAndModifyOptions.options().returnNew(true),
        WhatsForLunchSession.class);
    if (joined != null) {
      return SessionJoinOutcome.JOINED;
    }

    var existing = mongo.findById(sessionId, WhatsForLunchSession.class);
    if (existing == null) {
      return SessionJoinOutcome.NOT_FOUND;
    }
    var participantIds = existing.getParticipantAccountIds();
    if (participantIds != null && participantIds.contains(accountId)) {
      return SessionJoinOutcome.ALREADY_MEMBER;
    }
    return SessionJoinOutcome.FULL;
  }
}
