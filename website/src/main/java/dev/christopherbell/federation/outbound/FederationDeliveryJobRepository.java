package dev.christopherbell.federation.outbound;

import dev.christopherbell.federation.configuration.FederationOutboundProperties.ControlledPeer;
import dev.christopherbell.post.model.Post;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Repository;

/** Mongo owner of idempotent enqueue, scan cursor, due claim, and exact-owner transitions. */
@Repository
class FederationDeliveryJobRepository implements FederationDeliveryStore {
  private final MongoTemplate mongo;

  FederationDeliveryJobRepository(MongoTemplate mongo) {
    this.mongo = mongo;
  }

  @Override
  public FederationScanCursor loadCursor() {
    var state = mongo.findById(
        FederationScanState.OUTBOUND_CREATE, FederationScanState.class);
    return state == null ? null : state.cursor();
  }

  @Override
  public List<Post> scanEligibleAfter(FederationScanCursor cursor, int limit) {
    var eligible = Criteria.where("federationOutboundEligible").is(true);
    Criteria criteria = eligible;
    if (cursor != null) {
      criteria = new Criteria().andOperator(eligible, new Criteria().orOperator(
          Criteria.where("createdOn").gt(cursor.createdOn()),
          new Criteria().andOperator(
              Criteria.where("createdOn").is(cursor.createdOn()),
              Criteria.where("_id").gt(cursor.postId()))));
    }
    var query = Query.query(criteria)
        .with(Sort.by(Sort.Order.asc("createdOn"), Sort.Order.asc("_id")))
        .limit(limit);
    return mongo.find(query, Post.class);
  }

  @Override
  public void enqueueIfAbsent(Post post, ControlledPeer peer, Instant now) {
    String id = stableJobId(post.getId(), peer.name());
    var update = new Update()
        .setOnInsert("postId", post.getId())
        .setOnInsert("accountId", post.getAccountId())
        .setOnInsert("peerName", peer.name())
        .setOnInsert("peerInbox", peer.inbox().toString())
        .setOnInsert("state", FederationDeliveryState.PENDING)
        .setOnInsert("attempts", 0)
        .setOnInsert("nextAttemptOn", now)
        .setOnInsert("createdOn", now)
        .setOnInsert("updatedOn", now);
    mongo.upsert(Query.query(Criteria.where("_id").is(id)), update, FederationDeliveryJob.class);
  }

  @Override
  public void saveCursor(FederationScanCursor cursor, Instant now) {
    var update = new Update()
        .set("createdOn", cursor.createdOn())
        .set("postId", cursor.postId())
        .set("updatedOn", now);
    mongo.upsert(Query.query(Criteria.where("_id").is(FederationScanState.OUTBOUND_CREATE)),
        update, FederationScanState.class);
  }

  @Override
  public Optional<FederationDeliveryJob> claimDue(
      String owner, Instant now, Instant leaseUntil) {
    var available = new Criteria().orOperator(
        new Criteria().andOperator(
            Criteria.where("state").in(FederationDeliveryState.PENDING, FederationDeliveryState.RETRY),
            Criteria.where("nextAttemptOn").lte(now)),
        new Criteria().andOperator(
            Criteria.where("state").is(FederationDeliveryState.CLAIMED),
            Criteria.where("claimUntil").lte(now)));
    var query = Query.query(available)
        .with(Sort.by(Sort.Order.asc("nextAttemptOn"), Sort.Order.asc("createdOn")));
    var update = new Update()
        .set("state", FederationDeliveryState.CLAIMED)
        .set("claimOwner", owner)
        .set("claimUntil", leaseUntil)
        .set("updatedOn", now)
        .inc("attempts", 1);
    return Optional.ofNullable(mongo.findAndModify(
        query, update, FindAndModifyOptions.options().returnNew(true), FederationDeliveryJob.class));
  }

  @Override
  public boolean succeed(String jobId, String owner, int status, Instant now) {
    return transition(jobId, owner, new Update()
        .set("state", FederationDeliveryState.SUCCEEDED)
        .set("lastStatus", status)
        .set("lastOutcome", "DELIVERED")
        .set("updatedOn", now)
        .unset("claimOwner").unset("claimUntil"));
  }

  @Override
  public boolean retry(
      String jobId, String owner, Integer status, Instant nextAttempt, Instant now) {
    var update = new Update()
        .set("state", FederationDeliveryState.RETRY)
        .set("nextAttemptOn", nextAttempt)
        .set("lastStatus", status)
        .set("lastOutcome", "RETRYABLE")
        .set("updatedOn", now)
        .unset("claimOwner").unset("claimUntil");
    return transition(jobId, owner, update);
  }

  @Override
  public boolean dead(
      String jobId, String owner, Integer status, String reason, Instant now) {
    var update = new Update()
        .set("state", FederationDeliveryState.DEAD)
        .set("lastStatus", status)
        .set("lastOutcome", bounded(reason))
        .set("updatedOn", now)
        .unset("claimOwner").unset("claimUntil");
    return transition(jobId, owner, update);
  }

  @Override
  public boolean cancel(String jobId, String owner, String reason, Instant now) {
    return transition(jobId, owner, new Update()
        .set("state", FederationDeliveryState.CANCELLED)
        .set("lastOutcome", bounded(reason))
        .set("updatedOn", now)
        .unset("claimOwner").unset("claimUntil"));
  }

  private boolean transition(String jobId, String owner, Update update) {
    var query = Query.query(new Criteria().andOperator(
        Criteria.where("_id").is(jobId),
        Criteria.where("state").is(FederationDeliveryState.CLAIMED),
        Criteria.where("claimOwner").is(owner)));
    return mongo.updateFirst(query, update, FederationDeliveryJob.class).getModifiedCount() == 1;
  }

  private static String stableJobId(String postId, String peerName) {
    try {
      byte[] input = (postId + "\0" + peerName + "\0Create").getBytes(StandardCharsets.UTF_8);
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input));
    } catch (NoSuchAlgorithmException failure) {
      throw new IllegalStateException("SHA-256 is unavailable", failure);
    }
  }

  private static String bounded(String reason) {
    if (reason == null || reason.isBlank() || reason.length() > 64) {
      throw new IllegalArgumentException("Federation delivery outcome must be 1-64 characters");
    }
    return reason;
  }
}
