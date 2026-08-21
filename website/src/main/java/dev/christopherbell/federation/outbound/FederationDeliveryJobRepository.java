package dev.christopherbell.federation.outbound;

import dev.christopherbell.configuration.persistence.MongoPersistence;

import dev.christopherbell.federation.configuration.FederationOutboundProperties.ControlledPeer;
import dev.christopherbell.configuration.mongo.domain.DomainMongoOperationsFactory;
import dev.christopherbell.configuration.mongo.domain.KindScopedMongoOperations;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Repository;

/** Mongo owner of idempotent enqueue, scan cursor, due claim, and exact-owner transitions. */
@MongoPersistence
@Repository
class FederationDeliveryJobRepository implements FederationDeliveryStore {
  private final KindScopedMongoOperations<FederationScanState> scans;
  private final KindScopedMongoOperations<FederationDeliveryJob> jobs;

  FederationDeliveryJobRepository(DomainMongoOperationsFactory factory) {
    this.scans = factory.forType(FederationScanState.class);
    this.jobs = factory.forType(FederationDeliveryJob.class);
  }

  @Override
  public FederationScanCursor loadCursor() {
    return scans.findById(FederationScanState.OUTBOUND_CREATE)
        .map(FederationScanState::cursor).orElse(null);
  }

  @Override
  public void enqueueIfAbsent(
      String postId, String accountId, ControlledPeer peer, Instant now) {
    String id = stableJobId(postId, peer.name());
    if (jobs.findById(id).isPresent()) return;
    try {
      jobs.insert(new FederationDeliveryJob(id, postId, accountId, peer.name(),
          peer.inbox().toString(), FederationDeliveryState.PENDING, 0, now, null, null,
          null, null, now, now));
    } catch (org.springframework.dao.DuplicateKeyException ignored) {
      // A concurrent coordinator already created the same deterministic job.
    }
  }

  @Override
  public void saveCursor(FederationScanCursor cursor, Instant now) {
    scans.save(new FederationScanState(
        FederationScanState.OUTBOUND_CREATE, cursor.createdOn(), cursor.postId(), now));
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
    return jobs.findAndUpdate(query, update);
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
        Criteria.where("id").is(jobId),
        Criteria.where("state").is(FederationDeliveryState.CLAIMED),
        Criteria.where("claimOwner").is(owner)));
    return jobs.updateFirst(query, update).getModifiedCount() == 1;
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
