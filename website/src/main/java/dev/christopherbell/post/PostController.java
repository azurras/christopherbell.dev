package dev.christopherbell.post;

import static dev.christopherbell.libs.api.APIVersion.V20250914;

import dev.christopherbell.libs.api.model.Response;
import dev.christopherbell.permission.PermissionService;
import dev.christopherbell.post.model.PostCreateRequest;
import dev.christopherbell.post.model.PostDetail;
import dev.christopherbell.post.model.PostFeedItem;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller exposing endpoints to create and retrieve posts.
 *
 * <p>All routes are versioned under {@code /api/posts} with API version suffixes
 * from {@link dev.christopherbell.libs.api.APIVersion}. Access is controlled via
 * Spring Security authorities.</p>
 */
@RequiredArgsConstructor
@RequestMapping("/api/posts")
@RestController
public class PostController {
  private final PostService postService;
  private final PermissionService permissionService;

  /**
   * Creates a post for the authenticated user.
   *
   * @param request the post creation payload
   * @return HTTP 201 with the created {@link PostDetail}
   * @throws Exception if validation fails or the account cannot be resolved
   */
  @PostMapping(
      value = V20250914 + "/create",
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  @PreAuthorize("@permissionService.hasAuthority('USER')")
  public ResponseEntity<Response<PostDetail>> createPost(@Valid @RequestBody PostCreateRequest request)
      throws Exception {
    return new ResponseEntity<>(
        Response.<PostDetail>builder()
            .payload(postService.createPost(request))
            .success(true)
            .build(),
        HttpStatus.CREATED);
  }

  /**
   * Retrieves posts authored by the authenticated user.
   *
   * @return HTTP 200 with a list of {@link PostDetail}
   * @throws Exception if the account cannot be resolved
   */
  @GetMapping(value = V20250914 + "/me", produces = MediaType.APPLICATION_JSON_VALUE)
  @PreAuthorize("@permissionService.hasAuthority('USER')")
  public ResponseEntity<Response<List<PostDetail>>> getMyPosts() throws Exception {
    return new ResponseEntity<>(
        Response.<List<PostDetail>>builder()
            .payload(postService.getMyPosts())
            .success(true)
            .build(),
        HttpStatus.OK);
  }

  /**
   * Feed-style posts for the current user with thread metadata.
   */
  @GetMapping(value = V20250914 + "/me/feed", produces = MediaType.APPLICATION_JSON_VALUE)
  @PreAuthorize("@permissionService.hasAuthority('USER')")
  public ResponseEntity<Response<List<PostFeedItem>>> getMyFeed(
      @RequestParam(value = "before", required = false) java.time.Instant before,
      @RequestParam(value = "limit", required = false, defaultValue = "20") int limit
  ) throws Exception {
    return new ResponseEntity<>(
        Response.<List<PostFeedItem>>builder()
            .payload(postService.getMyFeed(before, limit))
            .success(true)
            .build(),
        HttpStatus.OK);
  }

  /**
   * Feed-style posts from accounts the current user follows.
   */
  @GetMapping(value = V20250914 + "/following/feed", produces = MediaType.APPLICATION_JSON_VALUE)
  @PreAuthorize("@permissionService.hasAuthority('USER')")
  public ResponseEntity<Response<List<PostFeedItem>>> getFollowingFeed(
      @RequestParam(value = "before", required = false) java.time.Instant before,
      @RequestParam(value = "limit", required = false, defaultValue = "20") int limit
  ) throws Exception {
    return new ResponseEntity<>(
        Response.<List<PostFeedItem>>builder()
            .payload(postService.getFollowingFeed(before, limit))
            .success(true)
            .build(),
        HttpStatus.OK);
  }

  /**
   * Retrieves posts for a specific account id (admin only).
   *
   * @param accountId the account id to filter posts by
   * @return HTTP 200 with a list of {@link PostDetail}
   * @throws Exception if the request is invalid or the account does not exist
   */
  @GetMapping(value = V20250914 + "/account/{accountId}", produces = MediaType.APPLICATION_JSON_VALUE)
  @PreAuthorize("@permissionService.hasAuthority('ADMIN')")
  public ResponseEntity<Response<List<PostDetail>>> getPostsByAccountId(@PathVariable String accountId)
      throws Exception {
    return new ResponseEntity<>(
        Response.<List<PostDetail>>builder()
            .payload(postService.getPostsByAccountId(accountId))
            .success(true)
            .build(),
        HttpStatus.OK);
  }

  /**
   * Public global feed endpoint returning newest posts across all users.
   *
   * @param before optional ISO-8601 timestamp; if provided, returns posts older than this
   * @param limit optional page size; defaults to 20 (cap at 100)
   */
  @GetMapping(value = V20250914 + "/feed", produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<Response<List<PostFeedItem>>> getGlobalFeed(
      @RequestParam(value = "before", required = false) java.time.Instant before,
      @RequestParam(value = "limit", required = false, defaultValue = "20") int limit
  ) {
    return new ResponseEntity<>(
        Response.<List<PostFeedItem>>builder()
            .payload(postService.getGlobalFeed(before, limit))
            .success(true)
            .build(),
        HttpStatus.OK);
  }

  /**
   * Public user feed endpoint returning newest posts for a given username.
   */
  @GetMapping(value = V20250914 + "/user/{username}/feed", produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<Response<List<PostFeedItem>>> getUserFeed(
      @PathVariable String username,
      @RequestParam(value = "before", required = false) java.time.Instant before,
      @RequestParam(value = "limit", required = false, defaultValue = "20") int limit
  ) throws Exception {
    return new ResponseEntity<>(
        Response.<List<PostFeedItem>>builder()
            .payload(postService.getUserFeed(username, before, limit))
            .success(true)
            .build(),
        HttpStatus.OK);
  }

  /**
   * Deletes a post by id. Requires USER authority; service enforces ownership or ADMIN override.
   */
  @DeleteMapping(value = V20250914 + "/{postId}", produces = MediaType.APPLICATION_JSON_VALUE)
  @PreAuthorize("@permissionService.hasAuthority('USER')")
  public ResponseEntity<Response<PostDetail>> deletePost(@PathVariable String postId) throws Exception {
    return new ResponseEntity<>(
        Response.<PostDetail>builder()
            .payload(postService.deletePost(postId))
            .success(true)
            .build(),
        HttpStatus.OK);
  }

  /** Likes/unlikes a post for the current user and returns the updated post feed item. */
  @PostMapping(value = V20250914 + "/{postId}/like", produces = MediaType.APPLICATION_JSON_VALUE)
  @PreAuthorize("@permissionService.hasAuthority('USER')")
  public ResponseEntity<Response<PostFeedItem>> toggleLike(@PathVariable String postId) throws Exception {
    return new ResponseEntity<>(
        Response.<PostFeedItem>builder()
            .payload(postService.toggleLike(postId))
            .success(true)
            .build(),
        HttpStatus.OK);
  }

  /**
   * Gets a single post by id (public).
   */
  @GetMapping(value = V20250914 + "/{postId}", produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<Response<PostFeedItem>> getPost(@PathVariable String postId) throws Exception {
    return new ResponseEntity<>(
        Response.<PostFeedItem>builder()
            .payload(postService.getPostById(postId))
            .success(true)
            .build(),
        HttpStatus.OK);
  }

  /**
   * Gets a flat thread for a post (root + replies), public.
   */
  @GetMapping(value = V20250914 + "/{postId}/thread", produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<Response<List<PostFeedItem>>> getThread(@PathVariable String postId) throws Exception {
    return new ResponseEntity<>(
        Response.<List<PostFeedItem>>builder()
            .payload(postService.getThread(postId))
            .success(true)
            .build(),
        HttpStatus.OK);
  }
}
