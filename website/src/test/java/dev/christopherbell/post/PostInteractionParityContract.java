package dev.christopherbell.post;

import static org.assertj.core.api.Assertions.assertThat;

import dev.christopherbell.account.AccountRepository;
import dev.christopherbell.account.model.Account;
import dev.christopherbell.account.model.AccountStatus;
import dev.christopherbell.account.model.Role;
import dev.christopherbell.post.expiration.PostExpirationStore;
import dev.christopherbell.post.like.PostLikeStore;
import dev.christopherbell.post.model.Post;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Shared like and expiration behavior executed against real MongoDB and PostgreSQL. */
interface PostInteractionParityContract {
  String ACCOUNT = "interaction-owner";
  String OTHER_ACCOUNT = "interaction-other";
  String ROOT = "interaction-root";
  String REPLY = "interaction-reply";
  Instant NOW = Instant.parse("2026-08-13T17:00:00Z");

  AccountRepository accounts();

  PostRepository posts();

  PostLikeStore likes();

  PostExpirationStore expiration();

  @BeforeEach
  default void resetInteractionFixtures() {
    likes().deleteForPosts(List.of(ROOT, REPLY));
    posts().deleteById(REPLY);
    posts().deleteById(ROOT);
    for (var id : List.of(ACCOUNT, OTHER_ACCOUNT)) {
      if (accounts().findById(id).isEmpty()) accounts().save(account(id));
    }
    posts().save(post(ROOT, null, ROOT, 0, NOW));
    posts().save(post(REPLY, ROOT, ROOT, 1, NOW.plusSeconds(1)));
  }

  @Test
  default void likeTransitionsAggregateAndOrderIdentically() {
    assertThat(likes().like(ROOT, ACCOUNT, NOW).delta()).isOne();
    assertThat(likes().like(ROOT, ACCOUNT, NOW).delta()).isZero();
    assertThat(likes().like(REPLY, ACCOUNT, NOW.plusSeconds(1)).delta()).isOne();
    assertThat(likes().like(ROOT, OTHER_ACCOUNT, NOW.plusSeconds(2)).delta()).isOne();

    assertThat(likes().counts(List.of(ROOT, REPLY)))
        .containsEntry(ROOT, 2).containsEntry(REPLY, 1);
    assertThat(likes().likedPostIds(ACCOUNT, List.of(ROOT, REPLY)))
        .containsExactlyInAnyOrder(ROOT, REPLY);
    assertThat(likes().recentLikedPostIds(ACCOUNT)).startsWith(REPLY, ROOT);
    assertThat(likes().unlike(ROOT, ACCOUNT).delta()).isEqualTo(-1);
    assertThat(likes().unlike(ROOT, ACCOUNT).delta()).isZero();
  }

  @Test
  default void expirationCountersSynchronizeRepliesAndNeverGoBelowZero() {
    var changedOn = NOW.plusSeconds(10);
    assertThat(expiration().incrementCounter(ROOT, "likesCount", 1, changedOn, true))
        .get().extracting(Post::getLikesCount, Post::getLastExtendedOn)
        .containsExactly(1, changedOn);
    assertThat(expiration().decrementFloorZero(ROOT, "likesCount", 5, changedOn.plusSeconds(1)))
        .get().extracting(Post::getLikesCount).isEqualTo(0);

    var synchronizedExpiry = NOW.plus(Duration.ofDays(4));
    expiration().synchronizeReplies(ROOT, ROOT, synchronizedExpiry);
    assertThat(posts().findById(REPLY).orElseThrow().getExpiresOn()).isEqualTo(synchronizedExpiry);
    expiration().updateExpiration(ROOT, synchronizedExpiry);
    assertThat(posts().findById(ROOT).orElseThrow().getExpiresOn()).isEqualTo(synchronizedExpiry);
  }

  private static Account account(String id) {
    return Account.builder().id(id).createdOn(NOW).email(id + "@example.test")
        .passwordHash("hash").role(Role.USER).status(AccountStatus.ACTIVE).username(id).build();
  }

  private static Post post(
      String id, String parentId, String rootId, int level, Instant createdOn) {
    return Post.builder().id(id).accountId(ACCOUNT).text(id).parentId(parentId).rootId(rootId)
        .level(level).createdOn(createdOn).expiresOn(createdOn.plus(Duration.ofDays(2)))
        .likesCount(0).threadReplyLikesCount(0).threadReplyCount(0).build();
  }
}
