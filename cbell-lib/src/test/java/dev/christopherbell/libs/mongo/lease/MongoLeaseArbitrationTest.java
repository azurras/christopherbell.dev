package dev.christopherbell.libs.mongo.lease;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.mongodb.client.result.UpdateResult;
import dev.christopherbell.libs.lease.LeaseGrant;
import dev.christopherbell.libs.lease.LeaseOwnershipLostException;
import dev.christopherbell.libs.lease.LeaseService;
import dev.christopherbell.libs.lease.LeaseStore;
import dev.christopherbell.libs.lease.ScheduledCollectorCoordinator;
import dev.christopherbell.libs.lease.ScheduledCollectorRunStatus;
import dev.christopherbell.libs.lease.ScheduledCollectorRunStore;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

/** Exercises production lease arbitration against a deterministic Mongo query/update boundary. */
class MongoLeaseArbitrationTest {
  private static final Instant NOW = Instant.parse("2026-07-26T12:00:00Z");
  private static final Duration LEASE_DURATION = Duration.ofMinutes(2);
  private static final String LEASE_NAME = "collector:test";

  @Test
  void twoCoordinatorsSerializeWorkAndTakeOverAtTheExactExpiryBoundary() {
    var leaseMongo = new StatefulLeaseMongo();
    var clock = new MutableClock(NOW);
    var leases = new LeaseService(
        new LegacyLeaseStore(new MongoLeaseService(leaseMongo), clock));
    var firstNode = new ScheduledCollectorCoordinator(
        leases, mock(ScheduledCollectorRunStore.class), clock);
    var secondNode = new ScheduledCollectorCoordinator(
        leases, mock(ScheduledCollectorRunStore.class), clock);
    var workRuns = new AtomicInteger();
    var contended = new AtomicReference<ScheduledCollectorCoordinator.Outcome<String>>();
    var takeover = new AtomicReference<ScheduledCollectorCoordinator.Outcome<String>>();
    var firstExpiry = new AtomicReference<Instant>();

    assertThatThrownBy(() -> firstNode.run(LEASE_NAME, LEASE_DURATION, firstGuard -> {
      workRuns.incrementAndGet();
      contended.set(secondNode.run(LEASE_NAME, LEASE_DURATION, secondGuard -> {
        workRuns.incrementAndGet();
        return "should-not-run";
      }));
      assertThat(workRuns).hasValue(1);
      firstExpiry.set(leaseMongo.expiresAt());
      clock.set(firstExpiry.get());
      takeover.set(secondNode.run(LEASE_NAME, LEASE_DURATION, secondGuard -> {
        workRuns.incrementAndGet();
        String staleOwner = leaseMongo.acquiredOwners().getFirst();
        assertThat(leases.renew(
            LEASE_NAME, staleOwner, clock.instant(), clock.instant().plus(LEASE_DURATION)))
            .isFalse();
        assertThat(leases.release(LEASE_NAME, staleOwner)).isFalse();
        return "takeover";
      }));
      return "stale-owner";
    })).isInstanceOf(LeaseOwnershipLostException.class);

    assertThat(contended.get().status()).isEqualTo(ScheduledCollectorRunStatus.SKIPPED_LOCKED);
    assertThat(takeover.get().status()).isEqualTo(ScheduledCollectorRunStatus.SUCCEEDED);
    assertThat(takeover.get().value()).isEqualTo("takeover");
    assertThat(clock.instant()).isEqualTo(firstExpiry.get());
    assertThat(leaseMongo.acquiredOwners()).hasSize(2);
    assertThat(leaseMongo.acquiredOwners().get(0))
        .isNotEqualTo(leaseMongo.acquiredOwners().get(1));
    assertThat(leaseMongo.acquiredExpiries())
        .containsExactly(NOW.plus(LEASE_DURATION), NOW.plus(LEASE_DURATION.multipliedBy(2)));
    assertThat(workRuns).hasValue(2);
  }

  private static final class LegacyLeaseStore implements LeaseStore {
    private final MongoLeaseService leases;
    private final Clock clock;
    private final AtomicInteger fence = new AtomicInteger();

    private LegacyLeaseStore(MongoLeaseService leases, Clock clock) {
      this.leases = leases;
      this.clock = clock;
    }

    @Override public Optional<LeaseGrant> tryAcquire(
        String name, String owner, Duration duration) {
      Instant now = clock.instant();
      Instant expiresAt = now.plus(duration);
      return leases.tryAcquire(name, owner, now, expiresAt)
          ? Optional.of(new LeaseGrant(name, owner, fence.incrementAndGet(), expiresAt))
          : Optional.empty();
    }

    @Override public Optional<LeaseGrant> renew(LeaseGrant grant, Duration duration) {
      Instant now = clock.instant();
      Instant expiresAt = now.plus(duration);
      return leases.renew(grant.leaseName(), grant.ownerId(), now, expiresAt)
          ? Optional.of(new LeaseGrant(
              grant.leaseName(), grant.ownerId(), grant.fenceToken(), expiresAt))
          : Optional.empty();
    }

    @Override public boolean release(LeaseGrant grant) {
      return leases.release(grant.leaseName(), grant.ownerId());
    }
  }

  private static final class StatefulLeaseMongo implements MongoLeaseStore {
    private final MongoTemplate template = mock(MongoTemplate.class);
    private final List<String> acquiredOwners = new ArrayList<>();
    private final List<Instant> acquiredExpiries = new ArrayList<>();
    private Document lease;

    private StatefulLeaseMongo() {
      when(template.findAndModify(
          any(Query.class),
          any(Update.class),
          any(FindAndModifyOptions.class),
          eq(MongoLeaseDocument.class)))
          .thenAnswer(invocation -> findAndModify(
              invocation.getArgument(0), invocation.getArgument(1)));
      when(template.updateFirst(
          any(Query.class), any(Update.class), eq(MongoLeaseDocument.class)))
          .thenAnswer(invocation -> updateFirst(
              invocation.getArgument(0), invocation.getArgument(1)));
    }

    private MongoTemplate template() {
      return template;
    }

    @Override
    public synchronized boolean tryAcquire(
        String name, String ownerToken, Instant now, Instant expiresAt) {
      if (lease != null
          && !ownerToken.equals(lease.getString("ownerToken"))
          && instant(lease.get("expiresAt")).isAfter(now)) {
        return false;
      }
      lease = new Document("_id", name)
          .append("ownerToken", ownerToken)
          .append("acquiredAt", now)
          .append("expiresAt", expiresAt);
      acquiredOwners.add(ownerToken);
      acquiredExpiries.add(expiresAt);
      return true;
    }

    @Override
    public synchronized boolean renew(
        String name, String ownerToken, Instant now, Instant expiresAt) {
      if (lease == null || !name.equals(lease.getString("_id"))
          || !ownerToken.equals(lease.getString("ownerToken"))
          || !instant(lease.get("expiresAt")).isAfter(now)) {
        return false;
      }
      lease.put("expiresAt", expiresAt);
      return true;
    }

    @Override
    public synchronized boolean release(String name, String ownerToken) {
      if (lease == null || !name.equals(lease.getString("_id"))
          || !ownerToken.equals(lease.getString("ownerToken"))) {
        return false;
      }
      lease.remove("ownerToken");
      lease.put("expiresAt", Instant.EPOCH);
      return true;
    }

    private synchronized MongoLeaseDocument findAndModify(Query query, Update update) {
      Document criteria = query.getQueryObject();
      if (lease != null && !matches(lease, criteria)) {
        throw new DuplicateKeyException("lease name already exists");
      }
      if (lease == null) {
        lease = new Document("_id", criteria.get("_id"));
      }
      apply(update.getUpdateObject());
      acquiredOwners.add(lease.getString("ownerToken"));
      acquiredExpiries.add(instant(lease.get("expiresAt")));
      return snapshot();
    }

    private synchronized UpdateResult updateFirst(Query query, Update update) {
      if (lease == null || !matches(lease, query.getQueryObject())) {
        return UpdateResult.acknowledged(0, 0L, null);
      }
      apply(update.getUpdateObject());
      return UpdateResult.acknowledged(1, 1L, null);
    }

    private boolean matches(Document stored, Document criteria) {
      for (Map.Entry<String, Object> entry : criteria.entrySet()) {
        String key = entry.getKey();
        if ("$and".equals(key) && !clausesMatch(stored, entry.getValue(), true)) {
          return false;
        }
        if ("$or".equals(key) && !clausesMatch(stored, entry.getValue(), false)) {
          return false;
        }
        if (!key.startsWith("$") && !fieldMatches(stored.get(key), entry.getValue())) {
          return false;
        }
      }
      return true;
    }

    private boolean clausesMatch(Document stored, Object rawClauses, boolean requireAll) {
      List<?> clauses = (List<?>) rawClauses;
      return requireAll
          ? clauses.stream().allMatch(clause -> matches(stored, document(clause)))
          : clauses.stream().anyMatch(clause -> matches(stored, document(clause)));
    }

    private boolean fieldMatches(Object actual, Object expected) {
      if (!(expected instanceof Map<?, ?> operations)) {
        return Objects.equals(actual, expected);
      }
      if (actual == null) return false;
      for (Map.Entry<?, ?> operation : operations.entrySet()) {
        int comparison = compare(actual, operation.getValue());
        switch (String.valueOf(operation.getKey())) {
          case "$lte" -> {
            if (comparison > 0) return false;
          }
          case "$gt" -> {
            if (comparison <= 0) return false;
          }
          default -> throw new IllegalArgumentException(
              "Unsupported lease query operation " + operation.getKey() + ".");
        }
      }
      return true;
    }

    private int compare(Object actual, Object expected) {
      return instant(actual).compareTo(instant(expected));
    }

    private Instant instant(Object value) {
      if (value instanceof Instant instant) return instant;
      if (value instanceof Date date) return date.toInstant();
      throw new IllegalArgumentException("Expected a time value but found " + value + ".");
    }

    private void apply(Document update) {
      Document set = document(update.get("$set"));
      if (set != null) {
        set.forEach(lease::put);
      }
      Document unset = document(update.get("$unset"));
      if (unset != null) {
        unset.keySet().forEach(lease::remove);
      }
    }

    private Document document(Object value) {
      if (value == null) return null;
      if (value instanceof Document document) return document;
      if (value instanceof Map<?, ?> map) {
        var document = new Document();
        map.forEach((key, entryValue) -> document.put(String.valueOf(key), entryValue));
        return document;
      }
      throw new IllegalArgumentException("Expected a Mongo document but found " + value + ".");
    }

    private MongoLeaseDocument snapshot() {
      var snapshot = new MongoLeaseDocument();
      snapshot.setId(lease.getString("_id"));
      snapshot.setOwnerToken(lease.getString("ownerToken"));
      snapshot.setAcquiredAt(instant(lease.get("acquiredAt")));
      snapshot.setExpiresAt(instant(lease.get("expiresAt")));
      return snapshot;
    }

    private synchronized Instant expiresAt() {
      return instant(lease.get("expiresAt"));
    }

    private List<String> acquiredOwners() {
      return List.copyOf(acquiredOwners);
    }

    private List<Instant> acquiredExpiries() {
      return List.copyOf(acquiredExpiries);
    }
  }

  private static final class MutableClock extends Clock {
    private Instant now;

    private MutableClock(Instant now) {
      this.now = now;
    }

    private void set(Instant next) {
      now = next;
    }

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      if (!ZoneOffset.UTC.equals(zone)) {
        throw new IllegalArgumentException("The arbitration test clock is UTC.");
      }
      return this;
    }

    @Override
    public Instant instant() {
      return now;
    }
  }
}
