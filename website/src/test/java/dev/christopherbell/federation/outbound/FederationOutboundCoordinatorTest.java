package dev.christopherbell.federation.outbound;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.christopherbell.account.AccountRepository;
import dev.christopherbell.account.model.Account;
import dev.christopherbell.account.model.AccountStatus;
import dev.christopherbell.federation.configuration.FederationOutboundProperties;
import dev.christopherbell.federation.configuration.FederationProperties;
import dev.christopherbell.federation.identity.EncryptedPrivateKey;
import dev.christopherbell.federation.identity.FederationIdentity;
import dev.christopherbell.post.PostRepository;
import dev.christopherbell.post.model.Post;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import org.junit.jupiter.api.Test;

class FederationOutboundCoordinatorTest {
  private static final Instant NOW = Instant.parse("2026-07-28T12:00:00Z");

  @Test
  void outboundKillSwitchPreventsQueueAndRemoteEffects() {
    var store = new InMemoryStore();
    store.scan.add(post("post-1", true));
    store.claimed = job(1);
    var gateway = new RecordingGateway(new FederationDeliveryResult.Delivered(202));
    var coordinator = coordinator(properties(false), store, gateway, account(true));

    coordinator.reconcile();
    coordinator.deliver();

    assertThat(store.enqueued).isEmpty();
    assertThat(store.claimCalls).isZero();
    assertThat(gateway.calls).isZero();
  }

  @Test
  void reconcileEnqueuesEveryControlledPeerThenAdvancesCursor() {
    var store = new InMemoryStore();
    var post = post("post-1", true);
    store.scan.add(post);
    var coordinator = coordinator(
        properties(true), store,
        new RecordingGateway(new FederationDeliveryResult.Delivered(202)), account(true));

    coordinator.reconcile();

    assertThat(store.enqueued).containsExactly("post-1:peer");
    assertThat(store.cursor).isEqualTo(new FederationScanCursor(post.getCreatedOn(), "post-1"));
  }

  @Test
  void revokedConsentCancelsClaimWithoutCallingRemotePeer() {
    var store = new InMemoryStore();
    store.claimed = job(1);
    var gateway = new RecordingGateway(new FederationDeliveryResult.Delivered(202));
    var coordinator = coordinator(properties(true), store, gateway, account(false));

    coordinator.deliver();

    assertThat(store.terminal).isEqualTo("cancelled:AUTHOR_INELIGIBLE");
    assertThat(gateway.calls).isZero();
  }

  @Test
  void successfulDeliveryCompletesTheExactClaim() {
    var store = new InMemoryStore();
    store.claimed = job(1);
    var gateway = new RecordingGateway(new FederationDeliveryResult.Delivered(202));
    var coordinator = coordinator(properties(true), store, gateway, account(true));

    coordinator.deliver();

    assertThat(gateway.calls).isEqualTo(1);
    assertThat(store.terminal).isEqualTo("succeeded:202");
  }

  @Test
  void retryableDeliveryUsesBoundedExponentialBackoffAndRetryAfter() {
    var store = new InMemoryStore();
    store.claimed = job(2);
    var gateway = new RecordingGateway(new FederationDeliveryResult.RetryableFailure(
        OptionalInt.of(429), Optional.of(Duration.ofMinutes(5))));
    var coordinator = coordinator(properties(true), store, gateway, account(true));

    coordinator.deliver();

    assertThat(store.retryAt).isEqualTo(NOW.plus(Duration.ofMinutes(5)));
    assertThat(store.terminal).isEqualTo("retry:429");
  }

  private static FederationOutboundCoordinator coordinator(
      FederationProperties properties,
      InMemoryStore store,
      RecordingGateway gateway,
      Account account
  ) {
    var posts = mock(PostRepository.class);
    var accounts = mock(AccountRepository.class);
    when(posts.findById("post-1")).thenReturn(Optional.of(post("post-1", true)));
    when(accounts.findById("account-123")).thenReturn(Optional.of(account));
    return new FederationOutboundCoordinator(
        properties,
        store,
        posts,
        accounts,
        gateway,
        Clock.fixed(NOW, ZoneOffset.UTC),
        "worker-1");
  }

  private static FederationProperties properties(boolean enabled) {
    var outbound = new FederationOutboundProperties(
        Instant.parse("2026-07-28T00:00:00Z"),
        List.of(new FederationOutboundProperties.ControlledPeer(
            "peer", URI.create("https://social.example/inbox"))),
        Duration.ofSeconds(3), Duration.ofSeconds(10), Duration.ofSeconds(30),
        Duration.ofHours(6), 6, 10, false);
    return new FederationProperties(
        true, false, enabled, "site", "test",
        Base64.getEncoder().encodeToString(new byte[32]), outbound);
  }

  private static Account account(boolean consented) {
    String actor = "https://www.christopherbell.dev/ap/users/chris";
    return Account.builder()
        .id("account-123")
        .status(AccountStatus.ACTIVE)
        .federationEnabled(consented)
        .federationIdentity(new FederationIdentity(
            actor,
            actor + "#main-key",
            "-----BEGIN PUBLIC KEY-----\npublic\n-----END PUBLIC KEY-----",
            new EncryptedPrivateKey(new byte[12], new byte[16]),
            1,
            NOW.minusSeconds(60)))
        .build();
  }

  private static Post post(String id, boolean eligible) {
    return Post.builder()
        .id(id)
        .accountId("account-123")
        .text("hello")
        .createdOn(NOW.minusSeconds(60))
        .expiresOn(NOW.plusSeconds(600))
        .federationOutboundEligible(eligible)
        .build();
  }

  private static FederationDeliveryJob job(int attempts) {
    return new FederationDeliveryJob(
        "job-1", "post-1", "account-123", "peer",
        "https://social.example/inbox", FederationDeliveryState.CLAIMED,
        attempts, NOW, "worker-1", NOW.plusSeconds(30), null, null, NOW, NOW);
  }

  private static final class RecordingGateway implements FederationActivityDeliveryGateway {
    private final FederationDeliveryResult result;
    private int calls;

    private RecordingGateway(FederationDeliveryResult result) {
      this.result = result;
    }

    @Override
    public FederationDeliveryResult deliver(
        Account account,
        Post post,
        FederationOutboundProperties.ControlledPeer peer
    ) {
      calls++;
      return result;
    }
  }

  private static final class InMemoryStore implements FederationDeliveryStore {
    private final List<Post> scan = new ArrayList<>();
    private final List<String> enqueued = new ArrayList<>();
    private FederationScanCursor cursor;
    private FederationDeliveryJob claimed;
    private int claimCalls;
    private String terminal;
    private Instant retryAt;

    @Override
    public FederationScanCursor loadCursor() {
      return cursor;
    }

    @Override
    public List<Post> scanEligibleAfter(FederationScanCursor ignored, int limit) {
      return List.copyOf(scan);
    }

    @Override
    public void enqueueIfAbsent(Post post, FederationOutboundProperties.ControlledPeer peer,
        Instant now) {
      enqueued.add(post.getId() + ":" + peer.name());
    }

    @Override
    public void saveCursor(FederationScanCursor cursor, Instant now) {
      this.cursor = cursor;
    }

    @Override
    public Optional<FederationDeliveryJob> claimDue(
        String owner, Instant now, Instant leaseUntil) {
      claimCalls++;
      return Optional.ofNullable(claimed);
    }

    @Override
    public boolean succeed(String jobId, String owner, int status, Instant now) {
      terminal = "succeeded:" + status;
      return true;
    }

    @Override
    public boolean retry(String jobId, String owner, Integer status, Instant nextAttempt,
        Instant now) {
      terminal = "retry:" + status;
      retryAt = nextAttempt;
      return true;
    }

    @Override
    public boolean dead(String jobId, String owner, Integer status, String reason, Instant now) {
      terminal = "dead:" + reason;
      return true;
    }

    @Override
    public boolean cancel(String jobId, String owner, String reason, Instant now) {
      terminal = "cancelled:" + reason;
      return true;
    }
  }
}
