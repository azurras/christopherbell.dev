package dev.christopherbell.post;

import static org.assertj.core.api.Assertions.assertThat;

import dev.christopherbell.post.model.Post;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;

/** Identical post and cursor assertions run against both persistence engines. */
interface PostRepositoryParityContract {
  String RUN = java.util.UUID.randomUUID().toString();
  String OWNER = "post-parity-owner-" + RUN;
  String FIRST = "post-parity-a-" + RUN;
  String SECOND = "post-parity-b-" + RUN;
  Instant CREATED = Instant.parse("2099-08-13T13:00:00Z");

  PostRepository parityPosts();

  @BeforeEach
  default void removeParityPosts() {
    parityPosts().deleteById(FIRST);
    parityPosts().deleteById(SECOND);
  }

  @Test
  default void parityPreservesCrudTtlAndFederationCursorSemantics() {
    parityPosts().save(post(FIRST, CREATED));
    parityPosts().save(post(SECOND, CREATED.plusSeconds(1)));

    assertThat(parityPosts().findById(FIRST).orElseThrow().getText()).isEqualTo(FIRST);
    assertThat(parityPosts().findByExpiresOnLessThanEqual(
        CREATED.plus(Duration.ofDays(3)), PageRequest.of(0, 1_000)))
        .extracting(Post::getId).contains(FIRST, SECOND);
    assertThat(parityPosts().findFederationEligibleAfter(CREATED, FIRST, 10))
        .extracting(Post::getId).contains(SECOND).doesNotContain(FIRST);
    assertThat(parityPosts().findFederationOutboxPage(
        OWNER, null, null, 1, CREATED.minusSeconds(1)))
        .extracting(Post::getId).containsExactly(SECOND);
  }

  private static Post post(String id, Instant createdOn) {
    return Post.builder().id(id).accountId(OWNER).text(id)
        .rootId(id).level(0).createdOn(createdOn)
        .expiresOn(createdOn.plus(Duration.ofDays(2))).federationOutboundEligible(true)
        .likesCount(0).threadReplyLikesCount(0).threadReplyCount(0).build();
  }
}
