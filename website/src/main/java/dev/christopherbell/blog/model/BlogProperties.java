package dev.christopherbell.blog.model;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Blog posts bound from the {@code blog-properties} configuration prefix.
 *
 * @param posts the configured posts in display order
 */
@ConfigurationProperties("blog-properties")
public record BlogProperties(List<Post> posts) {

  public BlogProperties {
    posts = posts == null ? List.of() : List.copyOf(posts);
  }
}
