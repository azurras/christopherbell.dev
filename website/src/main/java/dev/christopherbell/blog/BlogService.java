package dev.christopherbell.blog;

import dev.christopherbell.blog.model.BlogProperties;
import dev.christopherbell.blog.model.BlogResponse;
import dev.christopherbell.libs.api.exception.InvalidRequestException;
import dev.christopherbell.libs.api.exception.ResourceNotFoundException;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Service layer for blog queries.
 */
@AllArgsConstructor
@Service
@Slf4j
public class BlogService {

  private final BlogProperties blogProperties;

  /**
   * Retrieves a blog post by ID.
   *
   * @param id the requested blog post ID
   * @return a {@link BlogResponse} containing the matching post
   * @throws InvalidRequestException if the ID is blank
   * @throws ResourceNotFoundException if no post matches the valid ID
   */
  public BlogResponse getPostById(String id)
      throws InvalidRequestException, ResourceNotFoundException {
    if (Objects.isNull(id) || id.isBlank()) {
      throw new InvalidRequestException("Id can't be null or blank");
    }
    final UUID postId;
    try {
      postId = UUID.fromString(id);
    } catch (IllegalArgumentException e) {
      throw new InvalidRequestException("Id must be a valid UUID", e);
    }

    for (var post : blogProperties.getPosts()) {
      if (post.getId().equals(postId)) {
        return BlogResponse.builder()
            .posts(List.of(post))
            .build();
      }
    }

    throw new ResourceNotFoundException("No Post Found");
  }

  /**
   * Retrieves all blog posts.
   *
   * @return a {@link BlogResponse} with all posts
   */
  public BlogResponse getPosts() {
    return BlogResponse.builder()
        .posts(blogProperties.getPosts())
        .build();
  }
}
