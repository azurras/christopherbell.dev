package dev.christopherbell.blog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.christopherbell.blog.model.BlogProperties;
import dev.christopherbell.blog.model.BlogResponse;
import dev.christopherbell.blog.model.Post;
import dev.christopherbell.libs.api.exception.ResourceNotFoundException;
import org.junit.jupiter.api.Test;

class BlogServiceTest {

  private final BlogService blogService =
      new BlogService(new BlogProperties(BlogStub.configuredPosts()));

  @Test
  void findsExactlyThePostWithTheRequestedId() throws ResourceNotFoundException {
    BlogResponse matchingPost = blogService.findPostById(BlogStub.ROAD_TRIP_POST_ID);

    assertThat(matchingPost.posts()).containsExactly(BlogStub.roadTripPost());
  }

  @Test
  void reportsAnAbsentPostIdAsNotFound() {
    assertThatThrownBy(() -> blogService.findPostById(BlogStub.ABSENT_POST_ID))
        .isInstanceOf(ResourceNotFoundException.class)
        .hasMessageContaining(BlogStub.ABSENT_POST_ID.toString());
  }

  @Test
  void listsConfiguredPostsInConfigurationOrder() {
    assertThat(blogService.listPosts().posts())
        .containsExactly(BlogStub.miataPost(), BlogStub.roadTripPost());
  }

  @Test
  void listsNoPostsWhenNoneAreConfigured() {
    BlogService emptyBlogService = new BlogService(new BlogProperties(null));

    assertThat(emptyBlogService.listPosts().posts()).isEmpty();
  }

  @Test
  void rejectsAConfiguredPostWithoutATitle() {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> new Post(
            "CBell", "text", null, "summary", BlogStub.MIATA_POST_ID, null, null, " "))
        .withMessage("blog post title is required");
  }

  @Test
  void treatsMissingTagsAsNoTags() {
    Post untaggedPost = BlogStub.roadTripPost();

    assertThat(untaggedPost.tags()).isEmpty();
  }
}
