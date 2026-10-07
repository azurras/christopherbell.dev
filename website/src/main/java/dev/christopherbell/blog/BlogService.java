package dev.christopherbell.blog;

import dev.christopherbell.blog.model.BlogProperties;
import dev.christopherbell.blog.model.BlogResponse;
import dev.christopherbell.blog.model.Post;
import dev.christopherbell.libs.api.exception.ResourceNotFoundException;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Reads the configuration-backed blog.
 */
@AllArgsConstructor
@Service
public class BlogService {

  private final BlogProperties blogProperties;

  /**
   * Finds the configured post with the given ID.
   *
   * @param postId the requested post ID
   * @return a {@link BlogResponse} holding exactly the matching post
   * @throws ResourceNotFoundException if no configured post has the ID
   */
  public BlogResponse findPostById(UUID postId) throws ResourceNotFoundException {
    Objects.requireNonNull(postId, "postId");
    Post matchingPost = blogProperties.posts().stream()
        .filter(post -> post.id().equals(postId))
        .findFirst()
        .orElseThrow(() -> new ResourceNotFoundException("No blog post has ID " + postId));
    return new BlogResponse(List.of(matchingPost));
  }

  /**
   * Lists every configured post in configuration order.
   *
   * @return a {@link BlogResponse} holding all posts
   */
  public BlogResponse listPosts() {
    return new BlogResponse(blogProperties.posts());
  }
}
