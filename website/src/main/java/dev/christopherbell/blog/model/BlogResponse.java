package dev.christopherbell.blog.model;

import java.util.List;

/**
 * Blog API payload.
 *
 * @param posts the posts being returned, in display order
 */
public record BlogResponse(List<Post> posts) {

  public BlogResponse {
    posts = List.copyOf(posts);
  }
}
