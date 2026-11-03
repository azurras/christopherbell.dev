package dev.christopherbell.post.discovery;

import static org.assertj.core.api.Assertions.assertThat;

import dev.christopherbell.account.model.Account;
import dev.christopherbell.account.model.AccountStatus;
import dev.christopherbell.account.model.Role;
import dev.christopherbell.libs.pagination.StableCursorCodec;
import dev.christopherbell.post.PostRepository;
import dev.christopherbell.post.model.Post;
import dev.christopherbell.post.model.PostTopic;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Shared discovery behavior checks for MongoDB-backed post queries. */
interface PostDiscoveryParityContract {
  String RUN = UUID.randomUUID().toString();
  String OWNER = "discovery-owner-" + RUN;
  String OTHER = "discovery-other-" + RUN;
  String FIRST = "discovery-first-" + RUN;
  String SECOND = "discovery-second-" + RUN;
  Instant NOW = Instant.parse("2099-08-13T22:00:00Z");

  PostRepository posts();

  VoidDiscoveryQueryPort discovery();

  VoidPeopleDiscoveryQueryPort people();

  StableCursorCodec cursors();

  void ensureAccount(Account account);

  @BeforeEach
  default void seedDiscovery() {
    ensureAccount(account(OWNER));
    ensureAccount(account(OTHER));
    posts().deleteById(FIRST);
    posts().deleteById(SECOND);
    posts().save(post(FIRST, OWNER, NOW, "java"));
    posts().save(post(SECOND, OTHER, NOW.plusSeconds(1), "spring"));
  }

  @Test
  default void discoveryModesUseStableCursorsAndTopicFilters() throws Exception {
    var first = discovery().newArrivals(Optional.empty(), 1, NOW.minusSeconds(1));
    assertThat(first.items()).extracting(Post::getId).containsExactly(SECOND);
    assertThat(discovery().newArrivals(cursors().decode(first.nextCursor()), 1,
        NOW.minusSeconds(1)).items()).extracting(Post::getId).containsExactly(FIRST);
    assertThat(discovery().topic("java", Optional.empty(), 10, NOW.minusSeconds(1)).items())
        .extracting(Post::getId).containsExactly(FIRST);
    assertThat(discovery().topics(Optional.empty(), 10, NOW.minusSeconds(1)).items())
        .extracting(VoidTopicSummary::canonical).containsExactly("spring", "java");
  }

  @Test
  default void peopleDiscoveryDerivesInterestsAndBoundsCandidates() {
    assertThat(people().interestsFor(OWNER, NOW.minusSeconds(1))).containsExactly("java");
    assertThat(people().recentActiveCandidates(NOW.minusSeconds(1), 1))
        .extracting(VoidPersonCandidate::accountId).containsExactly(OTHER);
  }

  @Test
  default void topicPagesIncludeStaleReplyProjectionsUntilTheirRootsExpire() throws Exception {
    String firstRoot = "topic-root-a-" + RUN;
    String secondRoot = "topic-root-b-" + RUN;
    String firstReply = "topic-reply-a-" + RUN;
    String secondReply = "topic-reply-b-" + RUN;
    String topic = "replytopic" + RUN.replace("-", "").substring(0, 12);
    try {
      posts().save(post(firstRoot, OWNER, NOW.plusSeconds(10), "rootonly"));
      posts().save(post(secondRoot, OWNER, NOW.plusSeconds(20), "rootonly"));
      posts().save(reply(firstReply, firstRoot, OTHER, topic, NOW.minusSeconds(1)));
      posts().save(reply(secondReply, secondRoot, OTHER, topic, NOW.minusSeconds(1)));

      var first = discovery().topic(topic, Optional.empty(), 1, NOW);
      assertThat(first.items()).extracting(Post::getId).containsExactly(secondRoot);
      assertThat(first.nextCursor()).isNotNull();
      assertThat(discovery().topic(topic, cursors().decode(first.nextCursor()), 1, NOW).items())
          .extracting(Post::getId).containsExactly(firstRoot);
      assertThat(discovery().topics(Optional.empty(), 24, NOW).items())
          .extracting(VoidTopicSummary::canonical).contains(topic);
    } finally {
      for (String id : List.of(firstReply, secondReply, firstRoot, secondRoot)) {
        posts().deleteById(id);
      }
    }
  }

  @Test
  default void peopleDiscoveryUsesRootDeadlineForParticipationAndCandidateBounds() {
    String root = "people-root-" + RUN;
    String reply = "people-reply-" + RUN;
    String author = "people-reply-author-" + RUN;
    String topic = "peopletopic" + RUN.replace("-", "").substring(0, 12);
    try {
      posts().save(post(root, OWNER, NOW.minusSeconds(100), "rootonly"));
      posts().save(reply(reply, root, author, topic, NOW.minusSeconds(1)));

      assertThat(people().interestsFor(author, NOW)).containsExactly(topic);
      assertThat(people().recentActiveCandidates(NOW, 1))
          .extracting(VoidPersonCandidate::accountId).containsExactly(author);
    } finally {
      posts().deleteById(reply);
      posts().deleteById(root);
    }
  }

  @Test
  default void peopleDiscoveryExcludesRepliesWhoseRootsExpiredDespiteFutureProjections() {
    String root = "expired-people-root-" + RUN;
    String reply = "expired-people-reply-" + RUN;
    String author = "expired-people-author-" + RUN;
    try {
      var expiredRoot = post(root, OWNER, NOW.minus(Duration.ofDays(3)), "rootonly");
      posts().save(expiredRoot);
      posts().save(reply(reply, root, author, "expiredtopic", NOW.plusSeconds(100)));

      assertThat(people().interestsFor(author, NOW)).isEmpty();
      assertThat(people().recentActiveCandidates(NOW, 128))
          .extracting(VoidPersonCandidate::accountId).doesNotContain(author);
    } finally {
      posts().deleteById(reply);
      posts().deleteById(root);
    }
  }

  private static Post reply(String id, String root, String author, String topic, Instant expiresOn) {
    return Post.builder().id(id).accountId(author).text("#" + topic).rootId(root).parentId(root).level(1)
        .topics(List.of(new PostTopic(topic, topic))).createdOn(NOW.plusSeconds(100))
        .expiresOn(expiresOn).likesCount(0).threadReplyLikesCount(0).threadReplyCount(0).build();
  }
  private static Account account(String id) {
    return Account.builder().id(id).createdOn(NOW).email(id + "@example.test")
        .passwordHash("hash").role(Role.USER)
        .status(AccountStatus.ACTIVE).username(id).build();
  }

  private static Post post(String id, String accountId, Instant createdOn, String topic) {
    return Post.builder().id(id).accountId(accountId).text("#" + topic).rootId(id).level(0)
        .topics(List.of(new PostTopic(topic, topic))).createdOn(createdOn)
        .expiresOn(createdOn.plus(Duration.ofDays(2))).likesCount(0)
        .threadReplyLikesCount(0).threadReplyCount(0).build();
  }
}
