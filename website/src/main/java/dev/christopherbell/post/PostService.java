package dev.christopherbell.post;

import dev.christopherbell.libs.api.exception.InvalidRequestException;
import dev.christopherbell.libs.api.exception.ResourceNotFoundException;
import dev.christopherbell.permission.PermissionService;
import dev.christopherbell.post.creation.PostCreationService;
import dev.christopherbell.post.feed.PostFeedService;
import dev.christopherbell.post.feed.PostFeedPage;
import dev.christopherbell.post.feed.PostDetailPage;
import dev.christopherbell.post.interaction.PostInteractionService;
import dev.christopherbell.post.model.PostCreateRequest;
import dev.christopherbell.post.model.PostDetail;
import dev.christopherbell.post.model.PostFeedItem;
import dev.christopherbell.post.thread.PostThreadService;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** Facade that keeps the post controller API stable while subfeatures own behavior. */
@RequiredArgsConstructor
@Service
public class PostService {
  private final PermissionService permissionService;
  private final PostCreationService postCreationService;
  private final PostFeedService postFeedService;
  private final PostThreadService postThreadService;
  private final PostInteractionService postInteractionService;

  /** Creates a root post or reply for the current account. */
  public PostDetail createPost(PostCreateRequest request)
      throws InvalidRequestException, ResourceNotFoundException {
    return postCreationService.createPost(request, this::getSelfId);
  }

  /** Lists posts authored by the current account. */
  public List<PostDetail> getMyPosts() throws ResourceNotFoundException {
    return postFeedService.getMyPosts(getSelfId());
  }

  /** Returns one stable page of posts authored by the current account. */
  public PostDetailPage getMyPostsPage(String cursor, int size)
      throws InvalidRequestException, ResourceNotFoundException {
    return postFeedService.getMyPostsPage(getSelfId(), cursor, size);
  }

  /** Returns the current user's feed. */
  public List<PostFeedItem> getMyFeed(Instant before, int limit) throws ResourceNotFoundException {
    return postFeedService.getMyFeed(getSelfId(), before, limit);
  }

  /** Returns one stable page from the current user's own feed. */
  public PostFeedPage getMyFeedPage(String cursor, int size)
      throws InvalidRequestException, ResourceNotFoundException {
    return postFeedService.getMyFeedPage(getSelfId(), cursor, size);
  }

  /** Returns posts from accounts the current user follows. */
  public List<PostFeedItem> getFollowingFeed(Instant before, int limit)
      throws ResourceNotFoundException {
    return postFeedService.getFollowingFeed(getSelfId(), before, limit);
  }

  /** Returns one stable page from followed accounts. */
  public PostFeedPage getFollowingFeedPage(String cursor, int size)
      throws InvalidRequestException, ResourceNotFoundException {
    return postFeedService.getFollowingFeedPage(getSelfId(), cursor, size);
  }

  /** Lists posts for a specific account id. */
  public List<PostDetail> getPostsByAccountId(String accountId)
      throws InvalidRequestException, ResourceNotFoundException {
    return postFeedService.getPostsByAccountId(accountId);
  }

  /** Returns one stable page of posts for a specific account id. */
  public PostDetailPage getPostsByAccountPage(String accountId, String cursor, int size)
      throws InvalidRequestException, ResourceNotFoundException {
    return postFeedService.getPostsByAccountPage(accountId, cursor, size);
  }

  /** Resolves the id of the authenticated account. Separated for tests. */
  String getSelfId() {
    return PermissionService.getSelf();
  }

  /** Returns a public global feed, with viewer-specific fields when authenticated. */
  public List<PostFeedItem> getGlobalFeed(Instant before, int limit) {
    return postFeedService.getGlobalFeed(before, limit, getSelfIdOrNull());
  }

  /** Returns one stable global feed page. */
  public PostFeedPage getGlobalFeedPage(String cursor, int size) throws InvalidRequestException {
    return postFeedService.getGlobalFeedPage(cursor, size, getSelfIdOrNull());
  }

  /** Returns a public feed for one username. */
  public List<PostFeedItem> getUserFeed(String username, Instant before, int limit)
      throws ResourceNotFoundException {
    return postFeedService.getUserFeed(username, before, limit, getSelfIdOrNull());
  }

  /** Returns one stable page for a public username. */
  public PostFeedPage getUserFeedPage(String username, String cursor, int size)
      throws InvalidRequestException, ResourceNotFoundException {
    return postFeedService.getUserFeedPage(username, cursor, size, getSelfIdOrNull());
  }

  /** Returns one post by id. */
  public PostFeedItem getPostById(String id) throws ResourceNotFoundException {
    return postThreadService.getPostById(id, getSelfIdOrNull());
  }

  /** Returns a flat root-and-replies thread for any post id in the thread. */
  public List<PostFeedItem> getThread(String id) throws ResourceNotFoundException {
    return postThreadService.getThread(id, getSelfIdOrNull());
  }

  /** Toggles the current user's like on a post. */
  public PostFeedItem toggleLike(String postId)
      throws ResourceNotFoundException, InvalidRequestException {
    return postInteractionService.toggleLike(postId, getSelfId());
  }

  /** Sets the current user's desired like state idempotently. */
  public PostFeedItem setLiked(String postId, boolean liked)
      throws ResourceNotFoundException, InvalidRequestException {
    return postInteractionService.setLiked(postId, getSelfId(), liked);
  }

  /** Deletes a post when the current user is the author or an admin. */
  public PostDetail deletePost(String postId)
      throws ResourceNotFoundException, InvalidRequestException {
    return postInteractionService.deletePost(postId, getSelfId(), permissionService.hasAuthority("ADMIN"));
  }

  private String getSelfIdOrNull() {
    try {
      return getSelfId();
    } catch (Exception ignored) {
      return null;
    }
  }
}
