package dev.christopherbell.post.feed;

import static org.assertj.core.api.Assertions.assertThat;

import dev.christopherbell.account.model.Account;
import dev.christopherbell.libs.pagination.StableCursorCodec;
import dev.christopherbell.post.PostRepository;
import dev.christopherbell.post.model.Post;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Shared feed/engagement behavior executed against real MongoDB and PostgreSQL. */
interface PostReadModelParityContract {
  String OWNER = "feed-owner";
  String OTHER = "feed-other";
  String ROOT = "feed-root";
  String REPLY_A = "feed-reply-a";
  String REPLY_B = "feed-reply-b";
  Instant NOW = Instant.parse("2026-08-13T21:00:00Z");

  PostRepository posts();

  PostFeedQueryPort feed();

  PostEngagementQueryPort engagement();

  StableCursorCodec cursors();

  void ensureAccount(Account account);

  @BeforeEach
  default void seedFeed() {
    ensureAccount(account(OWNER));
    ensureAccount(account(OTHER));
    for (var id : List.of(REPLY_B, REPLY_A, ROOT)) posts().deleteById(id);
    posts().save(post(ROOT, OWNER, null, ROOT, 0, NOW));
    posts().save(post(REPLY_A, OTHER, ROOT, ROOT, 1, NOW.plusSeconds(1)));
    posts().save(post(REPLY_B, OWNER, ROOT, ROOT, 1, NOW.plusSeconds(2)));
  }

  @Test
  default void feedsUseStableDescendingCursorsAndVisibilityFilters() throws Exception {
    var first = feed().account(OWNER, Optional.empty(), 1);
    assertThat(first.posts()).extracting(Post::getId).containsExactly(REPLY_B);
    assertThat(feed().account(OWNER, cursors().decode(first.nextCursor()), 1).posts())
        .extracting(Post::getId).containsExactly(ROOT);
    assertThat(feed().account(OTHER, Optional.empty(), 10).posts())
        .extracting(Post::getId).containsExactly(REPLY_A);
    var visibility = new PostFeedVisibility(
        java.util.Set.of(OTHER), java.util.Set.of(), Optional.of(NOW.minusSeconds(1)));
    assertThat(feed().global(Optional.empty(), 10, visibility).posts())
        .extracting(Post::getId).doesNotContain(REPLY_A);
  }

  @Test
  default void engagementCountsRepliesInOneBulkResult() {
    assertThat(engagement().replyCounts(List.of(ROOT, REPLY_A)))
        .containsEntry(ROOT, 2).doesNotContainKey(REPLY_A);
    assertThat(engagement().replyCounts(List.of())).isEmpty();
  }

  private static Account account(String id) {
    return Account.builder().id(id).createdOn(NOW).email(id + "@example.test")
        .passwordHash("hash").role(dev.christopherbell.account.model.Role.USER)
        .status(dev.christopherbell.account.model.AccountStatus.ACTIVE).username(id).build();
  }

  private static Post post(
      String id, String accountId, String parentId, String rootId, int level, Instant createdOn) {
    return Post.builder().id(id).accountId(accountId).text(id).parentId(parentId).rootId(rootId)
        .level(level).createdOn(createdOn).expiresOn(createdOn.plus(Duration.ofDays(2)))
        .likesCount(0).threadReplyLikesCount(0).threadReplyCount(0).build();
  }
}
