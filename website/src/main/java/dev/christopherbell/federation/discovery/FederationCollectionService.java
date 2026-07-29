package dev.christopherbell.federation.discovery;

import dev.christopherbell.account.AccountRepository;
import dev.christopherbell.account.model.Account;
import dev.christopherbell.account.model.AccountStatus;
import dev.christopherbell.configuration.security.BrowserSecurityProperties;
import dev.christopherbell.federation.discovery.FederationDiscoveryModels.ActivityPubCreate;
import dev.christopherbell.federation.discovery.FederationDiscoveryModels.ActivityPubNote;
import dev.christopherbell.federation.discovery.FederationDiscoveryModels.ActivityPubOrderedCollection;
import dev.christopherbell.libs.api.exception.InvalidRequestException;
import dev.christopherbell.libs.api.exception.ResourceNotFoundException;
import dev.christopherbell.pagination.StableCursorCodec;
import dev.christopherbell.post.model.Post;
import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.web.util.HtmlUtils;
import org.springframework.web.util.UriComponentsBuilder;

/** Builds bounded read-only outbox and local relationship collections. */
@Service
public class FederationCollectionService {
  private static final String CONTEXT = "https://www.w3.org/ns/activitystreams";
  private static final String PUBLIC = "https://www.w3.org/ns/activitystreams#Public";
  private static final int MAX_PAGE_SIZE = 20;

  private final FederationDiscoveryService discovery;
  private final FederationOutboxQueryRepository outboxQueries;
  private final AccountRepository accounts;
  private final StableCursorCodec cursors;
  private final Clock clock;
  private final String publicOrigin;

  public FederationCollectionService(
      FederationDiscoveryService discovery,
      FederationOutboxQueryRepository outboxQueries,
      AccountRepository accounts,
      StableCursorCodec cursors,
      Clock clock,
      BrowserSecurityProperties browserSecurity
  ) {
    this.discovery = Objects.requireNonNull(discovery, "discovery");
    this.outboxQueries = Objects.requireNonNull(outboxQueries, "outboxQueries");
    this.accounts = Objects.requireNonNull(accounts, "accounts");
    this.cursors = Objects.requireNonNull(cursors, "cursors");
    this.clock = Objects.requireNonNull(clock, "clock");
    this.publicOrigin = Objects.requireNonNull(browserSecurity, "browserSecurity")
        .publicBaseUrl()
        .toString();
  }

  public ActivityPubOrderedCollection<ActivityPubCreate> outbox(
      String username,
      boolean page,
      String cursor,
      int requestedSize
  ) throws ResourceNotFoundException, InvalidRequestException {
    Account account = discovery.actorAccount(username);
    String actorId = discovery.actorForAccount(account).id();
    String collectionId = actorId + "/outbox";
    Instant now = Instant.now(clock);
    long totalItems = outboxQueries.count(account.getId(), now);
    if (!page) {
      return new ActivityPubOrderedCollection<>(
          List.of(CONTEXT),
          collectionId,
          "OrderedCollection",
          totalItems,
          pageUrl(collectionId, null),
          null,
          null,
          null);
    }

    int size = Math.max(1, Math.min(requestedSize, MAX_PAGE_SIZE));
    var loaded = outboxQueries.page(account.getId(), cursors.decode(cursor), size, now);
    var items = loaded.items().stream()
        .map(post -> createActivity(actorId, post))
        .toList();
    String currentPage = pageUrl(collectionId, cursor == null || cursor.isBlank() ? null : cursor);
    String next = loaded.nextCursor() == null
        ? null
        : pageUrl(collectionId, loaded.nextCursor());
    return new ActivityPubOrderedCollection<>(
        List.of(CONTEXT),
        currentPage,
        "OrderedCollectionPage",
        totalItems,
        null,
        collectionId,
        items,
        next);
  }

  public ActivityPubOrderedCollection<String> following(String username)
      throws ResourceNotFoundException {
    Account owner = discovery.actorAccount(username);
    Collection<String> followingIds = owner.getFollowingIds() == null
        ? List.of()
        : owner.getFollowingIds();
    List<Account> related = followingIds.isEmpty()
        ? List.of()
        : accounts.findByIdInAndStatusAndFederationEnabledTrueOrderByUsernameAsc(
            followingIds, AccountStatus.ACTIVE, PageRequest.of(0, MAX_PAGE_SIZE));
    return relationship(owner, "following", related);
  }

  public ActivityPubOrderedCollection<String> followers(String username)
      throws ResourceNotFoundException {
    Account owner = discovery.actorAccount(username);
    List<Account> related = accounts
        .findByFollowingIdsContainingAndStatusAndFederationEnabledTrueOrderByUsernameAsc(
            owner.getId(), AccountStatus.ACTIVE, PageRequest.of(0, MAX_PAGE_SIZE));
    return relationship(owner, "followers", related);
  }

  private ActivityPubOrderedCollection<String> relationship(
      Account owner,
      String name,
      List<Account> related
  ) throws ResourceNotFoundException {
    String actorId = discovery.actorForAccount(owner).id();
    var actorIds = related.stream()
        .map(this::optionalActorId)
        .filter(Objects::nonNull)
        .toList();
    return new ActivityPubOrderedCollection<>(
        List.of(CONTEXT),
        actorId + "/" + name,
        "OrderedCollection",
        actorIds.size(),
        null,
        null,
        actorIds,
        null);
  }

  private String optionalActorId(Account account) {
    try {
      return discovery.actorForAccount(account).id();
    } catch (ResourceNotFoundException ignored) {
      return null;
    }
  }

  private ActivityPubCreate createActivity(String actorId, Post post) {
    String objectId = publicOrigin + "/void/" + post.getId();
    String followers = actorId + "/followers";
    List<String> to = List.of(PUBLIC);
    List<String> cc = List.of(followers);
    String reply = post.getParentId() == null
        ? null
        : publicOrigin + "/void/" + post.getParentId();
    var note = new ActivityPubNote(
        objectId,
        "Note",
        actorId,
        HtmlUtils.htmlEscape(String.valueOf(post.getText() == null ? "" : post.getText())),
        post.getCreatedOn(),
        post.getLastUpdatedOn(),
        reply,
        to,
        cc,
        objectId);
    return new ActivityPubCreate(
        objectId + "#activity",
        "Create",
        actorId,
        post.getCreatedOn(),
        to,
        cc,
        note);
  }

  private static String pageUrl(String collectionId, String cursor) {
    var builder = UriComponentsBuilder.fromUriString(collectionId).queryParam("page", true);
    if (cursor != null && !cursor.isBlank()) {
      builder.queryParam("cursor", cursor);
    }
    return builder.build().toUriString();
  }
}
