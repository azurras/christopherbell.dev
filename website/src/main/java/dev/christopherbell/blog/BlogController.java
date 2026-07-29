package dev.christopherbell.blog;

import dev.christopherbell.blog.model.BlogResponse;
import dev.christopherbell.libs.api.exception.InvalidRequestException;
import dev.christopherbell.libs.api.exception.ResourceNotFoundException;
import dev.christopherbell.libs.api.model.Response;
import java.util.UUID;
import lombok.AllArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller for blog content under {@code /api/blog}.
 *
 * <p>Endpoints return a {@link Response} envelope containing a {@link BlogResponse} payload.</p>
 */
@AllArgsConstructor
@RequestMapping("/api/blog")
@RestController
public class BlogController {
  private final BlogService blogService;

  /**
   * Retrieves a single post by its ID.
   *
   * @param id the post identifier
   * @return HTTP 200 with a {@link BlogResponse} containing the post
   */
  @GetMapping(value = "/v1/posts/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<Response<BlogResponse>> getBlogPostById(@PathVariable UUID id)
      throws InvalidRequestException, ResourceNotFoundException {
    return new ResponseEntity<>(
        Response.<BlogResponse>builder()
            .payload(blogService.getPostById(id.toString()))
            .success(true)
            .build(), HttpStatus.OK);
  }

  /**
   * Lists all posts.
   *
   * @return HTTP 200 with a {@link BlogResponse} containing all posts
   */
  @GetMapping(value = "/v1/posts", produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<Response<BlogResponse>> getBlogPosts() {
    return new ResponseEntity<>(
        Response.<BlogResponse>builder()
            .payload(blogService.getPosts())
            .success(true)
            .build(), HttpStatus.OK);
  }
}
