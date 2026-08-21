package dev.christopherbell.post.discovery;

import static org.assertj.core.api.Assertions.assertThat;

import dev.christopherbell.account.model.Account;
import dev.christopherbell.libs.pagination.StableCursorCodec;
import dev.christopherbell.post.PostRepository;
import dev.christopherbell.post.model.Post;
import dev.christopherbell.post.model.PostTopic;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Shared discovery behavior executed against real MongoDB and PostgreSQL. */
interface PostDiscoveryParityContract {
  String RUN = java.util.UUID.randomUUID().toString();
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
    posts().save(post(SECOND, OTHER, NOW.plusSeconds(1), "postgres"));
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
        .extracting(VoidTopicSummary::canonical).containsExactly("postgres", "java");
  }

  @Test
  default void peopleDiscoveryDerivesInterestsAndBoundsCandidates() {
    assertThat(people().interestsFor(OWNER, NOW.minusSeconds(1))).containsExactly("java");
    assertThat(people().recentActiveCandidates(NOW.minusSeconds(1), 1))
        .extracting(VoidPersonCandidate::accountId).containsExactly(OTHER);
  }

  private static Account account(String id) {
    return Account.builder().id(id).createdOn(NOW).email(id + "@example.test")
        .passwordHash("hash").role(dev.christopherbell.account.model.Role.USER)
        .status(dev.christopherbell.account.model.AccountStatus.ACTIVE).username(id).build();
  }

  private static Post post(String id, String accountId, Instant createdOn, String topic) {
    return Post.builder().id(id).accountId(accountId).text("#" + topic).rootId(id).level(0)
        .topics(List.of(new PostTopic(topic, topic))).createdOn(createdOn)
        .expiresOn(createdOn.plus(Duration.ofDays(2))).likesCount(0)
        .threadReplyLikesCount(0).threadReplyCount(0).build();
  }
}
