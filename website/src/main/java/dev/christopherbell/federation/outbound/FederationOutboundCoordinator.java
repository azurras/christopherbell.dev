package dev.christopherbell.federation.outbound;

import dev.christopherbell.account.AccountRepository;
import dev.christopherbell.account.model.Account;
import dev.christopherbell.account.model.AccountStatus;
import dev.christopherbell.federation.configuration.FederationOutboundProperties.ControlledPeer;
import dev.christopherbell.federation.configuration.FederationProperties;
import dev.christopherbell.post.PostRepository;
import dev.christopherbell.post.model.Post;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Reconciles eligible posts and dispatches one bounded controlled-peer job at a time. */
@Component
@ConditionalOnProperty(name = "app.federation.outbound-enabled", havingValue = "true")
final class FederationOutboundCoordinator {
  private static final Duration CLAIM_LEASE = Duration.ofSeconds(30);

  private final FederationProperties properties;
  private final FederationDeliveryStore store;
  private final PostRepository posts;
  private final AccountRepository accounts;
  private final FederationActivityDeliveryGateway gateway;
  private final Clock clock;
  private final String owner;

  @Autowired
  FederationOutboundCoordinator(
      FederationProperties properties,
      FederationDeliveryStore store,
      PostRepository posts,
      AccountRepository accounts,
      FederationActivityDeliveryGateway gateway,
      Clock clock
  ) {
    this(properties, store, posts, accounts, gateway, clock, UUID.randomUUID().toString());
  }

  FederationOutboundCoordinator(
      FederationProperties properties,
      FederationDeliveryStore store,
      PostRepository posts,
      AccountRepository accounts,
      FederationActivityDeliveryGateway gateway,
      Clock clock,
      String owner
  ) {
    this.properties = Objects.requireNonNull(properties, "properties");
    this.store = Objects.requireNonNull(store, "store");
    this.posts = Objects.requireNonNull(posts, "posts");
    this.accounts = Objects.requireNonNull(accounts, "accounts");
    this.gateway = Objects.requireNonNull(gateway, "gateway");
    this.clock = Objects.requireNonNull(clock, "clock");
    if (owner == null || owner.isBlank()) {
      throw new IllegalArgumentException("Federation worker owner must not be blank");
    }
    this.owner = owner;
  }

  @Scheduled(fixedDelayString = "${app.federation.outbound.scan-delay:2s}")
  void reconcile() {
    if (!properties.outboundEnabled()) {
      return;
    }
    Instant now = clock.instant();
    var found = store.scanEligibleAfter(
        store.loadCursor(), properties.outbound().batchSize());
    for (var post : found) {
      for (var peer : properties.outbound().peers()) {
        store.enqueueIfAbsent(post, peer, now);
      }
    }
    if (!found.isEmpty()) {
      Post last = found.getLast();
      store.saveCursor(new FederationScanCursor(last.getCreatedOn(), last.getId()), now);
    }
  }

  @Scheduled(fixedDelayString = "${app.federation.outbound.delivery-delay:2s}")
  void deliver() {
    if (!properties.outboundEnabled()) {
      return;
    }
    Instant now = clock.instant();
    var claimed = store.claimDue(owner, now, now.plus(CLAIM_LEASE));
    if (claimed.isEmpty()) {
      return;
    }
    FederationDeliveryJob job = claimed.orElseThrow();
    ControlledPeer peer = configuredPeer(job);
    if (peer == null) {
      store.cancel(job.id(), owner, "PEER_REMOVED", now);
      return;
    }
    Post post = posts.findById(job.postId()).orElse(null);
    if (!active(post, now)) {
      store.cancel(job.id(), owner, "POST_INELIGIBLE", now);
      return;
    }
    Account account = accounts.findById(job.accountId()).orElse(null);
    if (!active(account)) {
      store.cancel(job.id(), owner, "AUTHOR_INELIGIBLE", now);
      return;
    }

    final FederationDeliveryResult result;
    try {
      result = gateway.deliver(account, post, peer);
    } catch (RuntimeException failure) {
      store.dead(job.id(), owner, null, "LOCAL_DELIVERY_FAILURE", now);
      return;
    }
    switch (result) {
      case FederationDeliveryResult.Delivered delivered ->
          store.succeed(job.id(), owner, delivered.statusCode(), now);
      case FederationDeliveryResult.PermanentFailure permanent ->
          store.dead(job.id(), owner, permanent.statusCode(), "REMOTE_PERMANENT", now);
      case FederationDeliveryResult.RetryableFailure retryable ->
          retryOrExhaust(job, retryable, now);
    }
  }

  private void retryOrExhaust(
      FederationDeliveryJob job,
      FederationDeliveryResult.RetryableFailure retryable,
      Instant now
  ) {
    Integer status = retryable.statusCode().isPresent()
        ? retryable.statusCode().getAsInt()
        : null;
    if (job.attempts() >= properties.outbound().maxAttempts()) {
      store.dead(job.id(), owner, status, "ATTEMPTS_EXHAUSTED", now);
      return;
    }
    Duration exponential = exponentialBackoff(job.attempts());
    Duration requested = retryable.retryAfter().orElse(Duration.ZERO);
    Duration delay = exponential.compareTo(requested) >= 0 ? exponential : requested;
    if (delay.compareTo(properties.outbound().maxBackoff()) > 0) {
      delay = properties.outbound().maxBackoff();
    }
    store.retry(job.id(), owner, status, now.plus(delay), now);
  }

  private Duration exponentialBackoff(int attempts) {
    Duration delay = properties.outbound().initialBackoff();
    for (int attempt = 1; attempt < attempts; attempt++) {
      if (delay.compareTo(properties.outbound().maxBackoff().dividedBy(2)) > 0) {
        return properties.outbound().maxBackoff();
      }
      delay = delay.multipliedBy(2);
    }
    return delay.compareTo(properties.outbound().maxBackoff()) > 0
        ? properties.outbound().maxBackoff()
        : delay;
  }

  private ControlledPeer configuredPeer(FederationDeliveryJob job) {
    return properties.outbound().peers().stream()
        .filter(peer -> peer.name().equals(job.peerName()))
        .filter(peer -> peer.inbox().toString().equals(job.peerInbox()))
        .findFirst()
        .orElse(null);
  }

  private static boolean active(Post post, Instant now) {
    return post != null
        && post.isFederationOutboundEligible()
        && (post.getExpiresOn() == null || post.getExpiresOn().isAfter(now));
  }

  private static boolean active(Account account) {
    return account != null
        && account.getStatus() == AccountStatus.ACTIVE
        && account.isFederationEnabled()
        && account.getFederationIdentity() != null;
  }
}
