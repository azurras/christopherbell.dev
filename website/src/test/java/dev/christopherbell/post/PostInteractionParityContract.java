package dev.christopherbell.post;

import static org.assertj.core.api.Assertions.assertThat;

import dev.christopherbell.account.AccountRepository;
import dev.christopherbell.account.model.Account;
import dev.christopherbell.account.model.AccountStatus;
import dev.christopherbell.account.model.Role;
import dev.christopherbell.post.expiration.PostExpirationStore;
import dev.christopherbell.post.like.PostLikeStore;
import dev.christopherbell.post.model.Post;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Shared like and expiration behavior checks for MongoDB-backed post operations. */
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

  dev.christopherbell.post.feed.PostFeedQueryPort feeds();

  @BeforeEach
  default void resetInteractionFixtures() {
    likes().deleteForPosts(List.of(ROOT, REPLY));
    posts().deleteById(REPLY);
    posts().deleteById(ROOT);
    for (var id : List.of(ACCOUNT, OTHER_ACCOUNT)) {
      if (accounts().findById(id).isEmpty()) {
        accounts().save(account(id));
      }
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

  @Test
  default void oppositeLikeDeltasCommuteWhenRemovalFinishesBeforeInsertionCounter() {
    var created = likes().like(ROOT, ACCOUNT, NOW);
    var removed = likes().unlike(ROOT, ACCOUNT);
    expiration().incrementCounter(ROOT, "likesCount", removed.delta(), NOW, false);
    expiration().incrementCounter(ROOT, "likesCount", created.delta(), NOW, true);
    var current = posts().findById(ROOT).orElseThrow();
    assertThat(current.getLikesCount()).isZero();
    assertThat(current.getExpiresOn()).isEqualTo(NOW.plus(Duration.ofDays(1)));
    assertThat(likes().counts(List.of(ROOT))).isEmpty();
  }

  @Test
  default void counterMutationReturnsItsAtomicallyDerivedExpiration() {
    var current = expiration().incrementCounter(ROOT, "likesCount", 2, NOW, true).orElseThrow();
    assertThat(current.getLikesCount()).isEqualTo(2);
    assertThat(current.getExpiresOn()).isEqualTo(NOW.plus(Duration.ofDays(3)));
    assertThat(posts().findById(ROOT).orElseThrow().getExpiresOn()).isEqualTo(current.getExpiresOn());
  }

  @Test
  default void editingDoesNotReplaceAnEngagementWriteDuringPreviewResolution() throws Exception {
    var previews = org.mockito.Mockito.mock(dev.christopherbell.post.preview.PostLinkPreviewService.class);
    org.mockito.Mockito.when(previews.resolveForText("edited")).thenAnswer(invocation -> {
      expiration().incrementCounter(ROOT, "likesCount", 1, NOW, true);
      return List.of();
    });
    var service = editingService(previews);
    service.edit(ROOT, new dev.christopherbell.post.editing.PostEditRequest("edited"), ACCOUNT);
    var current = posts().findById(ROOT).orElseThrow();
    assertThat(current.getText()).isEqualTo("edited");
    assertThat(current.getLikesCount()).isOne();
    assertThat(current.getEditAudit()).hasSize(1);
  }

  @Test
  default void editingCannotRecreateAPostDeletedDuringPreviewResolution() {
    var previews = org.mockito.Mockito.mock(dev.christopherbell.post.preview.PostLinkPreviewService.class);
    org.mockito.Mockito.when(previews.resolveForText("edited")).thenAnswer(invocation -> {
      posts().deleteById(ROOT);
      return List.of();
    });
    var service = editingService(previews);
    org.junit.jupiter.api.Assertions.assertThrows(dev.christopherbell.libs.api.exception.ResourceNotFoundException.class, () -> service.edit(
        ROOT, new dev.christopherbell.post.editing.PostEditRequest("edited"), ACCOUNT));
    assertThat(posts().findById(ROOT)).isEmpty();
  }

  @Test
  default void cleanupDoesNotDeleteARootExtendedAfterItsExpiredPageWasRead() {
    expiration().updateExpiration(ROOT, NOW.minusSeconds(1));
    var stale = posts().findById(ROOT).orElseThrow();
    var repository = org.mockito.Mockito.spy(posts());
    org.mockito.Mockito.doAnswer(invocation -> {
      expiration().incrementCounter(ROOT, "likesCount", 1, NOW, true);
      return List.of(stale);
    }).when(repository).findByExpiresOnLessThanEqual(org.mockito.ArgumentMatchers.any(),
        org.mockito.ArgumentMatchers.any());
    var service = new dev.christopherbell.post.expiration.PostExpirationService(
        repository, expiration(), Clock.fixed(NOW, ZoneOffset.UTC), true);
    service.purgeExpiredPosts();
    assertThat(posts().findById(ROOT)).isPresent();
    assertThat(posts().findById(REPLY)).isPresent();
  }

  @Test
  default void replyVisibilityAndDeadlineUseCurrentRootInsteadOfItsStaleCopy() {
    expiration().updateExpiration(REPLY, NOW.minusSeconds(1));
    expiration().incrementCounter(ROOT, "likesCount", 1, NOW, true);
    var page = feeds().account(ACCOUNT, Optional.empty(), 100,
        new dev.christopherbell.post.feed.PostFeedVisibility(
            Set.of(), Set.of(), Optional.of(NOW)));
    assertThat(page.posts()).extracting(Post::getId).contains(REPLY);
    assertThat(page.posts().stream().filter(post -> REPLY.equals(post.getId())).findFirst().orElseThrow()
        .getExpiresOn()).isEqualTo(NOW.plus(Duration.ofDays(2)));
    expiration().updateExpiration(ROOT, NOW.minusSeconds(1));
    expiration().updateExpiration(REPLY, NOW.plus(Duration.ofDays(10)));
    var expired = feeds().account(ACCOUNT, Optional.empty(), 100,
        new dev.christopherbell.post.feed.PostFeedVisibility(
            Set.of(), Set.of(), Optional.of(NOW)));
    assertThat(expired.posts()).extracting(Post::getId).doesNotContain(ROOT, REPLY);
  }

  @Test
  default void sitemapAndOutboxUseTheSameRootDeadlineAsFeeds() {
    for (var id : List.of(ROOT, REPLY)) {
      var post = posts().findById(id).orElseThrow();
      post.setFederationOutboundEligible(true);
      posts().save(post);
    }
    expiration().updateExpiration(REPLY, NOW.minusSeconds(1));
    var before = posts().countByExpiresOnAfter(NOW);
    assertThat(posts().findByExpiresOnAfter(NOW,
        org.springframework.data.domain.PageRequest.of(0, 1000)).getContent())
        .extracting(Post::getId).contains(ROOT, REPLY);
    assertThat(posts().findFederationOutboxPage(ACCOUNT, null, null, 100, NOW))
        .extracting(Post::getId).contains(ROOT, REPLY);
    expiration().updateExpiration(ROOT, NOW.minusSeconds(1));
    expiration().updateExpiration(REPLY, NOW.plus(Duration.ofDays(10)));
    assertThat(posts().countByExpiresOnAfter(NOW)).isEqualTo(before - 2);
    assertThat(posts().findFederationOutboxPage(ACCOUNT, null, null, 100, NOW))
        .extracting(Post::getId).doesNotContain(ROOT, REPLY);
  }

  @Test
  default void failedLikeRollbackDoesNotRemoveANewerIncarnation() {
    var original = likes().like(ROOT, ACCOUNT, NOW);
    likes().unlike(ROOT, ACCOUNT);
    var replacement = likes().like(ROOT, ACCOUNT, NOW);
    assertThat(likes().undoCreatedLike(ROOT, ACCOUNT, original.creationToken())).isFalse();
    assertThat(likes().exists(ROOT, ACCOUNT)).isTrue();
    assertThat(likes().undoCreatedLike(ROOT, ACCOUNT, replacement.creationToken())).isTrue();
  }

  private dev.christopherbell.post.editing.PostEditingService editingService(
      dev.christopherbell.post.preview.PostLinkPreviewService previews) {
    var clock = Clock.fixed(NOW.plusSeconds(2), ZoneOffset.UTC);
    return new dev.christopherbell.post.editing.PostEditingService(posts(),
        org.mockito.Mockito.mock(PostMapper.class), previews,
        new dev.christopherbell.post.expiration.PostExpirationService(posts(), expiration(), clock, true),
        new dev.christopherbell.post.editing.PostEditingProperties(Duration.ofMinutes(15), 10), clock);
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
