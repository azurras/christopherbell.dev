package dev.christopherbell.federation.discovery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import dev.christopherbell.account.AccountRepository;
import dev.christopherbell.account.follow.AccountFollowStore;
import dev.christopherbell.account.model.Account;
import dev.christopherbell.account.model.AccountStatus;
import dev.christopherbell.configuration.security.BrowserSecurityProperties;
import dev.christopherbell.federation.discovery.FederationDiscoveryModels.ActivityPubActor;
import dev.christopherbell.federation.discovery.FederationDiscoveryModels.ActivityPubPublicKey;
import dev.christopherbell.federation.outbound.FederationActivityFactory;
import dev.christopherbell.libs.pagination.StableCursorCodec;
import dev.christopherbell.post.model.Post;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.net.URI;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;

@ExtendWith(MockitoExtension.class)
class FederationCollectionServiceTest {
  private static final Instant NOW = Instant.parse("2026-07-28T21:00:00Z");
  private static final String ACTOR_ID = "https://www.christopherbell.dev/ap/users/chris";

  @Mock private FederationDiscoveryService discovery;
  @Mock private FederationOutboxQueryRepository outboxQueries;
  @Mock private AccountRepository accounts;
  @Mock private AccountFollowStore follows;

  private FederationCollectionService collections;

  @BeforeEach
  void setUp() {
    collections = new FederationCollectionService(
        discovery,
        outboxQueries,
        accounts,
        follows,
        new StableCursorCodec(),
        Clock.fixed(NOW, ZoneOffset.UTC),
        new FederationActivityFactory(new BrowserSecurityProperties(
            URI.create("https://www.christopherbell.dev"), true, true)));
  }

  @Test
  void outboxSummaryIsBoundedAndPointsToItsFirstPage() throws Exception {
    var account = account("account-123", "chris");
    when(discovery.actorAccount("chris")).thenReturn(account);
    when(discovery.actorForAccount(account)).thenReturn(actor("chris"));
    when(outboxQueries.count("account-123", NOW)).thenReturn(42L);

    var collection = collections.outbox("chris", false, "", 20);

    assertThat(collection.id()).isEqualTo(ACTOR_ID + "/outbox");
    assertThat(collection.type()).isEqualTo("OrderedCollection");
    assertThat(collection.totalItems()).isEqualTo(42L);
    assertThat(collection.first()).isEqualTo(ACTOR_ID + "/outbox?page=true");
    assertThat(collection.orderedItems()).isNull();
  }

  @Test
  void outboxPageMapsEscapedActivePostsToPublicCreateActivities() throws Exception {
    var account = account("account-123", "chris");
    var post = Post.builder()
        .id("post-1")
        .accountId(account.getId())
        .text("hello <script>alert(1)</script>")
        .createdOn(NOW.minusSeconds(30))
        .lastUpdatedOn(NOW.minusSeconds(10))
        .parentId("parent-1")
        .expiresOn(NOW.plusSeconds(60))
        .build();
    when(discovery.actorAccount("chris")).thenReturn(account);
    when(discovery.actorForAccount(account)).thenReturn(actor("chris"));
    when(outboxQueries.count(account.getId(), NOW)).thenReturn(1L);
    when(outboxQueries.page(eq(account.getId()), any(), eq(20), eq(NOW)))
        .thenReturn(new FederationPage<>(List.of(post), null));

    var collection = collections.outbox("chris", true, "", 20);

    assertThat(collection.partOf()).isEqualTo(ACTOR_ID + "/outbox");
    assertThat(collection.orderedItems()).singleElement().satisfies(activity -> {
      assertThat(activity.actor()).isEqualTo(ACTOR_ID);
      assertThat(activity.type()).isEqualTo("Create");
      assertThat(activity.object().content())
          .isEqualTo("hello &lt;script&gt;alert(1)&lt;/script&gt;");
      assertThat(activity.object().inReplyTo())
          .isEqualTo("https://www.christopherbell.dev/void/parent-1");
      assertThat(activity.object().to())
          .containsExactly("https://www.w3.org/ns/activitystreams#Public");
    });
  }

  @Test
  void relationshipCollectionsContainOnlyValidatedLocalActorIds() throws Exception {
    var owner = account("account-123", "chris");
    var related = account("account-456", "alex");
    when(discovery.actorAccount("chris")).thenReturn(owner);
    when(discovery.actorForAccount(owner)).thenReturn(actor("chris"));
    when(follows.followedAccountIds(eq(owner.getId()), any(Pageable.class)))
        .thenReturn(List.of("account-456"));
    when(accounts.findByIdInAndStatusAndFederationEnabledTrueOrderByUsernameAsc(
        eq(List.of("account-456")), eq(AccountStatus.ACTIVE), any(Pageable.class)))
        .thenReturn(List.of(related));
    when(discovery.actorForAccount(related)).thenReturn(actor("alex"));

    var collection = collections.following("chris");

    assertThat(collection.totalItems()).isEqualTo(1);
    assertThat(collection.orderedItems())
        .containsExactly("https://www.christopherbell.dev/ap/users/alex");
  }

  private static Account account(String id, String username) {
    return Account.builder()
        .id(id)
        .username(username)
        .status(AccountStatus.ACTIVE)
        .federationEnabled(true)
        .build();
  }

  private static ActivityPubActor actor(String username) {
    String actorId = "https://www.christopherbell.dev/ap/users/" + username;
    return new ActivityPubActor(
        List.of("https://www.w3.org/ns/activitystreams"),
        actorId,
        "Person",
        username,
        username,
        actorId + "/inbox",
        actorId + "/outbox",
        actorId + "/followers",
        actorId + "/following",
        "https://www.christopherbell.dev/u/" + username,
        new ActivityPubPublicKey(actorId + "#main-key", actorId, "public"));
  }
}
