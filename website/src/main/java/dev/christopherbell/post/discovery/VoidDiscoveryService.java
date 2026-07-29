package dev.christopherbell.post.discovery;

import dev.christopherbell.account.AccountRepository;
import dev.christopherbell.libs.api.exception.InvalidRequestException;
import dev.christopherbell.pagination.StableCursorCodec;
import dev.christopherbell.post.PostRepository;
import dev.christopherbell.post.model.Post;
import dev.christopherbell.post.model.PostFeedItem;
import dev.christopherbell.post.model.PostTopic;
import java.text.Normalizer;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Locale;
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
  private final PostRepository posts;
  private final StableCursorCodec cursors;
  private final Clock clock;

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
    var accountIds = page.items().stream()
        .map(Post::getAccountId)
        .filter(Objects::nonNull)
        .distinct()
        .toList();
    var usernames = new LinkedHashMap<String, String>();
    accounts.findAllById(accountIds)
        .forEach(account -> usernames.put(account.getId(), account.getUsername()));
    var items = page.items().stream()
        .map(post -> toFeedItem(post, usernames.get(post.getAccountId())))
        .toList();
    return new VoidDiscoveryPage<>(items, page.nextCursor());
  }

  private PostFeedItem toFeedItem(Post post, String username) {
    return PostFeedItem.builder()
        .id(post.getId())
        .accountId(post.getAccountId())
        .username(username)
        .text(post.getText())
        .linkPreviews(post.getLinkPreviews())
        .rootId(post.getRootId())
        .parentId(post.getParentId())
        .level(post.getLevel())
        .likesCount(post.getLikesCount())
        .liked(false)
        .replyCount((int) posts.countByParentId(post.getId()))
        .createdOn(post.getCreatedOn())
        .lastUpdatedOn(post.getLastUpdatedOn())
        .editedOn(post.getEditedOn())
        .lastExtendedOn(post.getLastExtendedOn())
        .topics(post.getTopics())
        .expiresOn(post.getExpiresOn())
        .build();
  }

  private static String canonicalTopic(String rawTopic) throws InvalidRequestException {
    if (rawTopic == null || rawTopic.isBlank()) {
      throw new InvalidRequestException(INVALID_TOPIC_MESSAGE);
    }
    var normalized = Normalizer.normalize(rawTopic.strip(), Normalizer.Form.NFKC)
        .toLowerCase(Locale.ROOT);
    normalized = Normalizer.normalize(normalized, Normalizer.Form.NFKC);
    try {
      return new PostTopic(normalized, normalized).canonical();
    } catch (IllegalArgumentException exception) {
      throw new InvalidRequestException(INVALID_TOPIC_MESSAGE, exception);
    }
  }
}
