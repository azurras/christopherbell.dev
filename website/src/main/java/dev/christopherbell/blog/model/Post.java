package dev.christopherbell.blog.model;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * One blog post, bound from configuration and returned by the blog API.
 *
 * @param author the display author, or {@code null} when unknown
 * @param contentText the post body, rendered as literal text
 * @param createdOn when the post was published, or {@code null} when unknown
 * @param description a short summary
 * @param id the stable post identifier used by {@code /api/blog/v1/posts/{id}}
 * @param imagePath an optional site-relative image path
 * @param tags the post's tags; empty when none are configured
 * @param title the post title
 */
public record Post(
    String author,
    String contentText,
    Instant createdOn,
    String description,
    UUID id,
    String imagePath,
    List<String> tags,
    String title) {

  public Post {
    Objects.requireNonNull(id, "blog post id is required");
    if (title == null || title.isBlank()) {
      throw new IllegalArgumentException("blog post title is required");
    }
    tags = tags == null ? List.of() : List.copyOf(tags);
  }
}
