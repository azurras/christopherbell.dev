package dev.christopherbell.blog;

import dev.christopherbell.blog.model.BlogResponse;
import dev.christopherbell.libs.api.exception.ResourceNotFoundException;
import dev.christopherbell.libs.api.model.Response;
import java.util.UUID;
import lombok.AllArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller for blog content under {@code /api/blog}.
 *
 * <p>Endpoints return a {@link Response} envelope containing a {@link BlogResponse} payload.
 * A malformed post ID is rejected with 400 by UUID path conversion before the service runs.</p>
 */
@AllArgsConstructor
@RequestMapping("/api/blog")
@RestController
public class BlogController {
  private final BlogService blogService;

  /**
   * Finds one configured post.
   *
   * @param postId the post identifier from the path
   * @return HTTP 200 with a {@link BlogResponse} holding exactly that post
   * @throws ResourceNotFoundException if no configured post has the ID
   */
  @GetMapping(value = "/v1/posts/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<Response<BlogResponse>> findBlogPost(@PathVariable("id") UUID postId)
      throws ResourceNotFoundException {
    BlogResponse matchingPost = blogService.findPostById(postId);
    return ResponseEntity.ok(
        Response.<BlogResponse>builder()
            .payload(matchingPost)
            .success(true)
            .build());
  }

  /**
   * Lists every configured post.
   *
   * @return HTTP 200 with a {@link BlogResponse} holding all posts
   */
  @GetMapping(value = "/v1/posts", produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<Response<BlogResponse>> listBlogPosts() {
    BlogResponse allPosts = blogService.listPosts();
    return ResponseEntity.ok(
        Response.<BlogResponse>builder()
            .payload(allPosts)
            .success(true)
            .build());
  }
}
