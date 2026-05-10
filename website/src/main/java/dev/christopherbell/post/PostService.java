package dev.christopherbell.post;

import dev.christopherbell.account.AccountRepository;
import dev.christopherbell.libs.api.exception.InvalidRequestException;
import dev.christopherbell.libs.api.exception.ResourceNotFoundException;
import dev.christopherbell.account.model.Account;
import dev.christopherbell.permission.PermissionService;
import dev.christopherbell.post.model.Post;
import dev.christopherbell.post.model.PostCreateRequest;
import dev.christopherbell.post.model.PostDetail;
import dev.christopherbell.post.model.PostFeedItem;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;
import dev.christopherbell.libs.security.UsernameSanitizer;

/**
 * Application service for creating and retrieving tweet‑like posts.
 *
 * <p>Enforces input validation, ensures ownership via the authenticated
 * account, and delegates persistence to {@link PostRepository}.</p>
 */
@Service
@Slf4j
public class PostService {
  private final PostRepository postRepository;
  private final AccountRepository accountRepository;
  private final PostMapper postMapper;
  private final PermissionService permissionService;
  private final boolean expirationEnabled;

  private static final int MAX_TEXT_LENGTH = 280;
  private static final Duration BASE_LIFESPAN = Duration.ofHours(24);
  private static final Duration EXTENSION_PER_LIKE = Duration.ofHours(24);

  public PostService(
      PostRepository postRepository,
      AccountRepository accountRepository,
      PostMapper postMapper,
      PermissionService permissionService,
      @Value("${posts.expiration.enabled:false}") boolean expirationEnabled) {
    this.postRepository = postRepository;
    this.accountRepository = accountRepository;
    this.postMapper = postMapper;
    this.permissionService = permissionService;
    this.expirationEnabled = expirationEnabled;
  }

  /**
   * Creates a new post for the currently authenticated account.
   *
   * @param request input containing the post text (required, ≤ 280 chars)
   * @return created post as a {@link PostDetail}
   * @throws InvalidRequestException if text is null/blank or exceeds limits
   * @throws ResourceNotFoundException if the current account cannot be found
   */
  public PostDetail createPost(PostCreateRequest request)
      throws InvalidRequestException, ResourceNotFoundException {
    if (request == null || request.text() == null || request.text().isBlank()) {
      throw new InvalidRequestException("Post text cannot be null or blank.");
    }

    var text = request.text().trim();
    if (text.length() > MAX_TEXT_LENGTH) {
      throw new InvalidRequestException("Post text exceeds 280 characters.");
    }
    String selfId = getSelfId();
    var account = accountRepository
        .findById(selfId)
        .orElseThrow(() -> new ResourceNotFoundException(String.format("Account with id %s not found.", selfId)));
    var now = Instant.now();
    // Thread metadata
    String parentId = request.parentId();
    String rootId;
    Integer level;
    if (parentId != null && !parentId.isBlank()) {
      var parent = postRepository.findById(parentId)
          .orElseThrow(() -> new ResourceNotFoundException(
              String.format("Parent post with id %s not found.", parentId)));
      ensureActive(parent);
      rootId = parent.getRootId() != null ? parent.getRootId() : parent.getId();
      level = (parent.getLevel() != null ? parent.getLevel() : 0) + 1;
    } else {
      rootId = null; // set to self after ID gen
      level = 0;
    }

    var newId = UUID.randomUUID().toString();
    if (rootId == null) rootId = newId; // top-level post references itself as root

    var post = Post.builder()
        .id(newId)
        .accountId(account.getId())
        .text(text)
        .rootId(rootId)
        .parentId(parentId)
        .level(level)
        .likedBy(new HashSet<>())
        .likesCount(0)
        .createdOn(now)
        .lastUpdatedOn(now)
        .expiresOn(expirationEnabled ? calculateExpiration(now, 0) : null)
        .build();

    var saved = postRepository.save(post);
    return postMapper.toDetail(saved);
  }

  /**
   * Lists posts authored by the current account (newest first).
   *
   * @return list of post details for the caller
   * @throws ResourceNotFoundException if the current account cannot be found
   */
  public List<PostDetail> getMyPosts() throws ResourceNotFoundException {
    String selfId = getSelfId();
    // Ensure account exists (defensive, consistent with create)
    accountRepository
        .findById(selfId)
        .orElseThrow(() -> new ResourceNotFoundException(String.format("Account with id %s not found.", selfId)));

    var posts = postRepository.findByAccountIdOrderByCreatedOnDesc(selfId);
    posts.forEach(this::ensureExpirationSet);
    return posts.stream()
        .filter(p -> !isExpired(p))
        .map(postMapper::toDetail)
        .toList();
  }

  /**
   * Returns the current user's feed (newest first) enriched with thread metadata.
   *
   * @param before optional exclusive upper bound timestamp for pagination
   * @param limit  maximum number of items to return (1..100)
   * @return list of feed items for the current user
   * @throws ResourceNotFoundException if the current account cannot be resolved
   */
  public List<PostFeedItem> getMyFeed(Instant before, int limit) throws ResourceNotFoundException {
    String selfId = getSelfId();
    var account = accountRepository
        .findById(selfId)
        .orElseThrow(() -> new ResourceNotFoundException(String.format("Account with id %s not found.", selfId)));

    int pageSize = Math.max(1, Math.min(limit, 100));
    Pageable page = PageRequest.of(0, pageSize, Sort.by(Sort.Direction.DESC, "createdOn"));
    List<Post> posts;
    if (before != null) {
      posts = postRepository.findByAccountIdAndCreatedOnLessThanOrderByCreatedOnDesc(selfId, before, page);
    } else {
      posts = postRepository.findByAccountIdOrderByCreatedOnDesc(selfId, page);
    }

    posts.forEach(this::ensureExpirationSet);
    return posts.stream()
        .filter(p -> !isExpired(p))
        .map(p -> PostFeedItem.builder()
            .id(p.getId())
            .accountId(p.getAccountId())
            .username(account.getUsername())
            .text(p.getText())
            .rootId(p.getRootId())
            .parentId(p.getParentId())
            .level(p.getLevel())
            .replyCount((int) postRepository.countByParentId(p.getId()))
            .createdOn(p.getCreatedOn())
            .lastUpdatedOn(p.getLastUpdatedOn())
            .build())
        .toList();
  }

  /**
   * Lists posts for a given account id (newest first).
   *
   * @param accountId the account id to filter by (required)
   * @return list of post details for the account
   * @throws InvalidRequestException if the id is null or blank
   * @throws ResourceNotFoundException if the account does not exist
   */
  public List<PostDetail> getPostsByAccountId(String accountId)
      throws InvalidRequestException, ResourceNotFoundException {
    if (accountId == null || accountId.isBlank()) {
      throw new InvalidRequestException("Account id cannot be null or blank.");
    }
    accountRepository
        .findById(accountId)
        .orElseThrow(() -> new ResourceNotFoundException(String.format("Account with id %s not found.", accountId)));

    var posts = postRepository.findByAccountIdOrderByCreatedOnDesc(accountId);
    posts.forEach(this::ensureExpirationSet);
    return posts.stream()
        .filter(p -> !isExpired(p))
        .map(postMapper::toDetail)
        .toList();
  }

  /**
   * Resolves the id of the authenticated account.
   * Separated for testability.
   */
  String getSelfId() {
    return PermissionService.getSelf();
  }

  /**
   * Returns a global feed of posts (all users), newest first.
   * If {@code before} is provided, returns posts strictly older than that timestamp.
   * The {@code limit} is capped between 1 and 100; default callers should use 20.
   */
  /**
   * Returns a global feed across all users (newest first) with optional cursor.
   *
   * @param before optional exclusive upper bound timestamp for pagination
   * @param limit  maximum number of items to return (1..100)
   * @return list of global feed items
   */
  public List<PostFeedItem> getGlobalFeed(Instant before, int limit) {
    int pageSize = Math.max(1, Math.min(limit, 100));
    Pageable page = PageRequest.of(0, pageSize, Sort.by(Sort.Direction.DESC, "createdOn"));

    List<Post> posts;
    if (before != null) {
      posts = postRepository.findByCreatedOnLessThanOrderByCreatedOnDesc(before, page);
    } else {
      posts = postRepository.findAll(page).getContent();
    }

    // Resolve usernames for authors in batch
    var authorIds = posts.stream().map(Post::getAccountId).distinct().toList();
    var authors = accountRepository.findAllById(authorIds);
    var idToUser = authors.stream()
        .collect(Collectors.toMap(Account::getId, Account::getUsername));

    // Determine current user if any
    String selfId = null;
    try { selfId = getSelfId(); } catch (Exception ignored) {}
    final String currentUserId = selfId;

    posts.forEach(this::ensureExpirationSet);
    return posts.stream()
        .filter(p -> !isExpired(p))
        .map(p -> PostFeedItem.builder()
            .id(p.getId())
            .accountId(p.getAccountId())
            .username(idToUser.get(p.getAccountId()))
            .text(p.getText())
            .rootId(p.getRootId())
            .parentId(p.getParentId())
            .level(p.getLevel())
            .likesCount(p.getLikesCount())
            .liked(currentUserId != null && p.getLikedBy() != null && p.getLikedBy().contains(currentUserId))
            .replyCount((int) postRepository.countByParentId(p.getId()))
            .createdOn(p.getCreatedOn())
            .lastUpdatedOn(p.getLastUpdatedOn())
            .build())
        .toList();
  }

  /**
   * Returns a user-specific feed (by username), newest first, with optional time cursor.
   */
  /**
   * Returns a user-specific feed for the given username.
   *
   * @param username the author's username (sanitized)
   * @param before   optional exclusive upper bound timestamp for pagination
   * @param limit    maximum number of items to return (1..100)
   * @return list of feed items for the user
   * @throws ResourceNotFoundException if the user cannot be found
   */
  public List<PostFeedItem> getUserFeed(String username, Instant before, int limit)
      throws ResourceNotFoundException {
    var sanitized = UsernameSanitizer.sanitize(username);
    var account = accountRepository.findByUsername(sanitized)
        .orElseThrow(() -> new ResourceNotFoundException(
            String.format("Account with username %s not found.", sanitized)));

    int pageSize = Math.max(1, Math.min(limit, 100));
    Pageable page = PageRequest.of(0, pageSize, Sort.by(Sort.Direction.DESC, "createdOn"));
    List<Post> posts;
    if (before != null) {
      posts = postRepository.findByAccountIdAndCreatedOnLessThanOrderByCreatedOnDesc(account.getId(), before, page);
    } else {
      posts = postRepository.findByAccountIdOrderByCreatedOnDesc(account.getId(), page);
    }

    String selfId = null;
    try { selfId = getSelfId(); } catch (Exception ignored) {}
    final String currentUserId = selfId;
    posts.forEach(this::ensureExpirationSet);
    return posts.stream()
        .filter(p -> !isExpired(p))
        .map(p -> PostFeedItem.builder()
            .id(p.getId())
            .accountId(p.getAccountId())
            .username(account.getUsername())
            .text(p.getText())
            .rootId(p.getRootId())
            .parentId(p.getParentId())
            .level(p.getLevel())
            .likesCount(p.getLikesCount())
            .liked(currentUserId != null && p.getLikedBy() != null && p.getLikedBy().contains(currentUserId))
            .replyCount((int) postRepository.countByParentId(p.getId()))
            .createdOn(p.getCreatedOn())
            .lastUpdatedOn(p.getLastUpdatedOn())
            .build())
        .toList();
  }

  /**
   * Returns a single post by id enriched with author's username.
   *
   * @param id the post id
   * @return a feed-style item for the post
   * @throws ResourceNotFoundException if the post or author cannot be found
   */
  public PostFeedItem getPostById(String id) throws ResourceNotFoundException {
    var post = postRepository.findById(id)
        .orElseThrow(() -> new ResourceNotFoundException(String.format("Post with id %s not found.", id)));
    ensureActive(post);
    var author = accountRepository.findById(post.getAccountId())
        .orElseThrow(() -> new ResourceNotFoundException(String.format("Account with id %s not found.", post.getAccountId())));
    String selfId = null;
    try { selfId = getSelfId(); } catch (Exception ignored) {}
    ensureExpirationSet(post);
    return PostFeedItem.builder()
        .id(post.getId())
        .accountId(post.getAccountId())
        .username(author.getUsername())
        .text(post.getText())
        .rootId(post.getRootId())
        .parentId(post.getParentId())
        .level(post.getLevel())
        .likesCount(post.getLikesCount())
        .liked(selfId != null && post.getLikedBy() != null && post.getLikedBy().contains(selfId))
        .replyCount((int) postRepository.countByParentId(post.getId()))
        .createdOn(post.getCreatedOn())
        .lastUpdatedOn(post.getLastUpdatedOn())
        .build();
  }

  /**
   * Returns a flat list of posts in a thread (root first, then replies).
   *
   * @param id any post id within the thread
   * @return ordered list of thread items
   * @throws ResourceNotFoundException if the reference post cannot be found
   */
  public List<PostFeedItem> getThread(String id) throws ResourceNotFoundException {
    var post = postRepository.findById(id)
        .orElseThrow(() -> new ResourceNotFoundException(String.format("Post with id %s not found.", id)));
    ensureActive(post);
    var rootId = post.getRootId() != null ? post.getRootId() : post.getId();
    var posts = postRepository.findByRootIdOrderByCreatedOnAsc(rootId);
    // Map usernames
    var authorIds = posts.stream().map(Post::getAccountId).distinct().toList();
    var authors = accountRepository.findAllById(authorIds);
    var idToUser = authors.stream()
        .collect(java.util.stream.Collectors.toMap(Account::getId, Account::getUsername));
    String selfId = null;
    try { selfId = getSelfId(); } catch (Exception ignored) {}
    final String currentUserId = selfId;
    posts.forEach(this::ensureExpirationSet);
    return posts.stream()
        .filter(p -> !isExpired(p))
        .map(p -> PostFeedItem.builder()
            .id(p.getId())
            .accountId(p.getAccountId())
            .username(idToUser.get(p.getAccountId()))
            .text(p.getText())
            .rootId(p.getRootId())
            .parentId(p.getParentId())
            .level(p.getLevel())
            .likesCount(p.getLikesCount())
            .liked(currentUserId != null && p.getLikedBy() != null && p.getLikedBy().contains(currentUserId))
            .replyCount((int) postRepository.countByParentId(p.getId()))
            .createdOn(p.getCreatedOn())
            .lastUpdatedOn(p.getLastUpdatedOn())
            .build())
        .toList();
  }

  /**
   * Toggles like for the current user on a post.
   *
   * @param postId target post id
   * @return updated feed item reflecting new like state and count
   * @throws ResourceNotFoundException if the post or author cannot be found
   * @throws InvalidRequestException   if the caller is not authenticated
   */
  public PostFeedItem toggleLike(String postId)
      throws ResourceNotFoundException, InvalidRequestException {
    String selfId = getSelfId();
    var post = postRepository.findById(postId)
        .orElseThrow(() -> new ResourceNotFoundException(String.format("Post with id %s not found.", postId)));
    ensureActive(post);
    if (post.getLikedBy() == null) post.setLikedBy(new HashSet<>());
    boolean liked;
    if (post.getLikedBy().contains(selfId)) {
      post.getLikedBy().remove(selfId);
      post.setLikesCount(Math.max(0, (post.getLikesCount() == null ? 0 : post.getLikesCount()) - 1));
      liked = false;
    } else {
      post.getLikedBy().add(selfId);
      post.setLikesCount((post.getLikesCount() == null ? 0 : post.getLikesCount()) + 1);
      liked = true;
    }
    post.setLastUpdatedOn(Instant.now());
    refreshExpiration(post);
    postRepository.save(post);
    var author = accountRepository.findById(post.getAccountId())
        .orElseThrow(() -> new ResourceNotFoundException(String.format("Account with id %s not found.", post.getAccountId())));
    return PostFeedItem.builder()
        .id(post.getId())
        .accountId(post.getAccountId())
        .username(author.getUsername())
        .text(post.getText())
        .rootId(post.getRootId())
        .parentId(post.getParentId())
        .level(post.getLevel())
        .likesCount(post.getLikesCount())
        .liked(liked)
        .replyCount((int) postRepository.countByParentId(post.getId()))
        .createdOn(post.getCreatedOn())
        .lastUpdatedOn(post.getLastUpdatedOn())
        .build();
  }

  /**
   * Deletes a post if the caller is the author or has ADMIN role.
   *
   * @param postId the post identifier
   * @return deleted post details
   * @throws ResourceNotFoundException if the post does not exist
   * @throws InvalidRequestException   if the caller is not authorized
   */
  public PostDetail deletePost(String postId)
      throws ResourceNotFoundException, InvalidRequestException {
    var post = postRepository
        .findById(postId)
        .orElseThrow(() -> new ResourceNotFoundException(
            String.format("Post with id %s not found.", postId)));

    String selfId = getSelfId();
    boolean isOwner = post.getAccountId() != null && post.getAccountId().equals(selfId);
    boolean isAdmin = permissionService.hasAuthority("ADMIN");
    if (!isOwner && !isAdmin) {
      throw new InvalidRequestException("Not authorized to delete this post.");
    }

    postRepository.delete(post);
    return postMapper.toDetail(post);
  }

  @Scheduled(fixedDelayString = "${posts.expiration.cleanup-interval:600000}")
  public void purgeExpiredPosts() {
    log.info("Post expiration cleanup job started.");
    try {
      if (!expirationEnabled) {
        return;
      }
      var missing = postRepository.findByExpiresOnIsNull();
      if (!missing.isEmpty()) {
        missing.forEach(p -> {
          refreshExpiration(p);
          postRepository.save(p);
        });
      }
      postRepository.deleteByExpiresOnLessThanEqual(Instant.now());
    } finally {
      log.info("Post expiration cleanup job completed.");
    }
  }

  private static Instant calculateExpiration(Instant createdOn, int likesCount) {
    Instant base = createdOn != null ? createdOn : Instant.now();
    long likeCount = Math.max(0, likesCount);
    return base.plus(BASE_LIFESPAN).plus(EXTENSION_PER_LIKE.multipliedBy(likeCount));
  }

  private void refreshExpiration(Post post) {
    if (!expirationEnabled || post == null) {
      return;
    }
    int likes = post.getLikesCount() != null ? post.getLikesCount() : 0;
    post.setExpiresOn(calculateExpiration(post.getCreatedOn(), likes));
  }

  private void ensureExpirationSet(Post post) {
    if (!expirationEnabled || post == null || post.getExpiresOn() != null) {
      return;
    }
    refreshExpiration(post);
    postRepository.save(post);
  }

  private boolean isExpired(Post post) {
    if (!expirationEnabled || post == null) {
      return false;
    }
    Instant expiresOn = post.getExpiresOn();
    return expiresOn != null && !expiresOn.isAfter(Instant.now());
  }

  private void ensureActive(Post post) throws ResourceNotFoundException {
    ensureExpirationSet(post);
    if (isExpired(post)) {
      postRepository.delete(post);
      throw new ResourceNotFoundException(String.format("Post with id %s not found.", post.getId()));
    }
  }
}
