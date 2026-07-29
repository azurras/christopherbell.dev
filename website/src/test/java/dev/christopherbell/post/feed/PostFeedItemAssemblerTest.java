package dev.christopherbell.post.feed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.christopherbell.post.like.PostLikeStore;
import dev.christopherbell.post.model.Post;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PostFeedItemAssemblerTest {
  @Mock private PostEngagementQueryRepository engagement;
  @Mock private PostLikeStore likes;

  @ParameterizedTest
  @ValueSource(ints = {1, 50, 100})
  void assemblesEverySupportedPageSizeWithThreeBatchedQueries(int size) {
    var posts = java.util.stream.IntStream.range(0, size)
        .mapToObj(index -> Post.builder()
            .id("p" + index)
            .accountId("a" + index)
            .createdOn(Instant.EPOCH)
            .build())
        .toList();
    var ids = posts.stream().map(Post::getId).toList();
    var usernames = posts.stream().collect(java.util.stream.Collectors.toMap(
        Post::getAccountId,
        post -> "user-" + post.getAccountId()));
    when(engagement.replyCounts(ids)).thenReturn(Map.of("p0", 3));
    when(likes.counts(ids)).thenReturn(Map.of("p0", 2));
    when(likes.likedPostIds("viewer", ids)).thenReturn(Set.of("p0"));

    var items = new PostFeedItemAssembler(engagement, likes)
        .assemble(posts, usernames, "viewer");

    assertThat(items).hasSize(size);
    assertThat(items.get(0).replyCount()).isEqualTo(3);
    assertThat(items.get(0).likesCount()).isEqualTo(2);
    assertThat(items.get(0).liked()).isTrue();
    verify(engagement).replyCounts(ids);
    verify(likes).counts(ids);
    verify(likes).likedPostIds("viewer", ids);
  }
}
