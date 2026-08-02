package dev.christopherbell.post.discovery;

import dev.christopherbell.account.AccountRepository;
import dev.christopherbell.libs.api.exception.InvalidRequestException;
import dev.christopherbell.libs.pagination.StableCursorCodec;
import dev.christopherbell.permission.PermissionService;
import dev.christopherbell.post.feed.PostFeedItemAssembler;
import dev.christopherbell.post.model.Post;
import dev.christopherbell.post.model.PostFeedItem;
import dev.christopherbell.post.model.PostTopic;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** Maps bounded discovery queries into presentation-safe public results. */
@Service
@RequiredArgsConstructor
public class VoidDiscoveryService {
  private static final String INVALID_TOPIC_MESSAGE = "Invalid topic.";

  private final VoidDiscoveryQueryRepository queries;
  private final AccountRepository accounts;
  private final StableCursorCodec cursors;
  private final Clock clock;
  private final PermissionService permissions;
  private final PostFeedItemAssembler feedItems;

  public VoidDiscoveryPage<PostFeedItem> newArrivals(String cursor, int size)
      throws InvalidRequestException {
    return mapPosts(queries.newArrivals(cursors.decode(cursor), size, clock.instant()));
  }

  public VoidDiscoveryPage<PostFeedItem> fadingSoon(String cursor, int size)
      throws InvalidRequestException {
    return mapPosts(queries.fadingSoon(cursors.decode(cursor), size, clock.instant()));
  }

  public VoidDiscoveryPage<PostFeedItem> recentlyRevived(String cursor, int size)
      throws InvalidRequestException {
    return mapPosts(queries.recentlyRevived(cursors.decode(cursor), size, clock.instant()));
  }

  public VoidDiscoveryPage<VoidTopicSummary> topics(String cursor, int size)
      throws InvalidRequestException {
    return queries.topics(cursors.decode(cursor), size, clock.instant());
  }

  public VoidDiscoveryPage<PostFeedItem> topic(String rawTopic, String cursor, int size)
      throws InvalidRequestException {
    return mapPosts(queries.topic(
        canonicalTopic(rawTopic), cursors.decode(cursor), size, clock.instant()));
  }

  private VoidDiscoveryPage<PostFeedItem> mapPosts(VoidDiscoveryPage<Post> page) {
    String currentUserId = permissions.hasAuthority("USER") ? permissions.getSelfId() : null;
    var accountIds = page.items().stream()
        .map(Post::getAccountId)
        .filter(Objects::nonNull)
        .distinct()
        .toList();
    var usernames = new LinkedHashMap<String, String>();
    accounts.findAllById(accountIds)
        .forEach(account -> usernames.put(account.getId(), account.getUsername()));
    var items = feedItems.assemble(page.items(), usernames, currentUserId);
    return new VoidDiscoveryPage<>(items, page.nextCursor());
  }

  private static String canonicalTopic(String rawTopic) throws InvalidRequestException {
    if (rawTopic == null || rawTopic.isBlank()) {
      throw new InvalidRequestException(INVALID_TOPIC_MESSAGE);
    }
    try {
      return PostTopic.canonicalizeRoute(rawTopic);
    } catch (IllegalArgumentException exception) {
      throw new InvalidRequestException(INVALID_TOPIC_MESSAGE, exception);
    }
  }
}
