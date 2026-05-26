package dev.christopherbell.post;

import dev.christopherbell.account.AccountRepository;
import dev.christopherbell.libs.api.exception.InvalidRequestException;
import dev.christopherbell.libs.api.exception.ResourceNotFoundException;
import dev.christopherbell.account.model.Account;
import dev.christopherbell.notification.NotificationService;
import dev.christopherbell.permission.PermissionService;
import dev.christopherbell.post.model.Post;
import dev.christopherbell.post.model.PostCreateRequest;
import dev.christopherbell.post.model.PostDetail;
import dev.christopherbell.post.model.PostFeedItem;
import java.util.ArrayList;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
  private final NotificationService notificationService;
  private final PostLinkPreviewService postLinkPreviewService;
  private final boolean expirationEnabled;

  private static final int MAX_TEXT_LENGTH = 280;
  private static final int MIN_FEED_LIMIT = 1;
  private static final int MAX_FEED_LIMIT = 100;
  private static final Duration BASE_LIFESPAN = Duration.ofHours(24);
  private static final Duration EXTENSION_PER_LIKE = Duration.ofHours(24);

  public PostService(
      PostRepository postRepository,
      AccountRepository accountRepository,
      PostMapper postMapper,
      PermissionService permissionService,
      NotificationService notificationService,
      PostLinkPreviewService postLinkPreviewService,
      @Value("${posts.expiration.enabled:false}") boolean expirationEnabled) {
    this.postRepository = postRepository;
    this.accountRepository = accountRepository;
    this.postMapper = postMapper;
    this.permissionService = permissionService;
    this.notificationService = notificationService;
    this.postLinkPreviewService = postLinkPreviewService;
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
    Instant inheritedReplyExpiration = null;
    if (parentId != null && !parentId.isBlank()) {
      var parent = postRepository.findById(parentId)
          .orElseThrow(() -> new ResourceNotFoundException(
              String.format("Parent post with id %s not found.", parentId)));
      ensureActive(parent);
      rootId = parent.getRootId() != null ? parent.getRootId() : parent.getId();
      level = (parent.getLevel() != null ? parent.getLevel() : 0) + 1;
      inheritedReplyExpiration = rootExpirationFor(parent, rootId);
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
        .expiresOn(expirationEnabled ? expirationForNewPost(now, inheritedReplyExpiration) : null)
        .linkPreviews(postLinkPreviewService.resolveForText(text))
        .build();

    var saved = postRepository.save(post);
    refreshThreadRootExpirationForNewReply(saved);
    notificationService.createMentionNotifications(saved, account);
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

    Pageable page = newFeedPage(limit);
    List<Post> posts;
    if (before != null) {
      posts = postRepository.findByAccountIdAndCreatedOnLessThanOrderByCreatedOnDesc(selfId, before, page);
    } else {
      posts = postRepository.findByAccountIdOrderByCreatedOnDesc(selfId, page);
    }

    posts.forEach(this::ensureExpirationSet);
    return posts.stream()
        .filter(p -> !isExpired(p))
        .map(p -> toFeedItem(p, account.getUsername(), selfId))
        .toList();
  }

  /**
   * Returns feed posts from accounts the current user follows.
   *
   * @param before optional exclusive upper bound timestamp for pagination
   * @param limit maximum number of items to return (1..100)
   * @return list of followed-account feed items
   * @throws ResourceNotFoundException if the current account cannot be resolved
   */
  public List<PostFeedItem> getFollowingFeed(Instant before, int limit)
      throws ResourceNotFoundException {
    String selfId = getSelfId();
    var self = accountRepository
        .findById(selfId)
        .orElseThrow(() -> new ResourceNotFoundException(String.format("Account with id %s not found.", selfId)));

    var followingIds = self.getFollowingIds() == null ? List.<String>of() : self.getFollowingIds().stream().toList();
    if (followingIds.isEmpty()) {
      return List.of();
    }

    Pageable page = newFeedPage(limit);
    List<Post> posts;
    if (before != null) {
      posts = postRepository.findByAccountIdInAndCreatedOnLessThanOrderByCreatedOnDesc(followingIds, before, page);
    } else {
      posts = postRepository.findByAccountIdInOrderByCreatedOnDesc(followingIds, page);
    }

    var idToUser = usernamesByAccountId(followingIds);

    posts.forEach(this::ensureExpirationSet);
    return posts.stream()
        .filter(p -> !isExpired(p))
        .map(p -> toFeedItem(p, idToUser.get(p.getAccountId()), selfId))
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
   * Returns a global feed across all users (newest first) with optional cursor.
   *
   * @param before optional exclusive upper bound timestamp for pagination
   * @param limit  maximum number of items to return (1..100)
   * @return list of global feed items
   */
  public List<PostFeedItem> getGlobalFeed(Instant before, int limit) {
    Pageable page = newFeedPage(limit);

    List<Post> posts;
    if (before != null) {
      posts = postRepository.findByCreatedOnLessThanOrderByCreatedOnDesc(before, page);
    } else {
      posts = postRepository.findAll(page).getContent();
    }

    var authorIds = posts.stream().map(Post::getAccountId).distinct().toList();
    var idToUser = usernamesByAccountId(authorIds);
    String selfId = getSelfIdOrNull();

    posts.forEach(this::ensureExpirationSet);
    return posts.stream()
        .filter(p -> !isExpired(p))
        .map(p -> toFeedItem(p, idToUser.get(p.getAccountId()), selfId))
        .toList();
  }

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

    Pageable page = newFeedPage(limit);
    List<Post> posts;
    if (before != null) {
      posts = postRepository.findByAccountIdAndCreatedOnLessThanOrderByCreatedOnDesc(account.getId(), before, page);
    } else {
      posts = postRepository.findByAccountIdOrderByCreatedOnDesc(account.getId(), page);
    }

    String selfId = getSelfIdOrNull();
    posts.forEach(this::ensureExpirationSet);
    return posts.stream()
        .filter(p -> !isExpired(p))
        .map(p -> toFeedItem(p, account.getUsername(), selfId))
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
    String selfId = getSelfIdOrNull();
    ensureExpirationSet(post);
    return toFeedItem(post, author.getUsername(), selfId);
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
    var authorIds = posts.stream().map(Post::getAccountId).distinct().toList();
    var idToUser = usernamesByAccountId(authorIds);
    String selfId = getSelfIdOrNull();
    posts.forEach(this::ensureExpirationSet);
    return posts.stream()
        .filter(p -> !isExpired(p))
        .map(p -> toFeedItem(p, idToUser.get(p.getAccountId()), selfId))
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
    var threadRoot = activeThreadRootForReply(post);
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
    synchronizeReplyExpirations(post);
    refreshThreadRootExpiration(threadRoot, liked ? 1 : -1);
    var author = accountRepository.findById(post.getAccountId())
        .orElseThrow(() -> new ResourceNotFoundException(String.format("Account with id %s not found.", post.getAccountId())));
    return toFeedItem(post, author.getUsername(), liked ? selfId : null);
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

    deletePostTree(post);
    return postMapper.toDetail(post);
  }

  @Scheduled(fixedDelayString = "${posts.expiration.cleanup-interval}")
  public void purgeExpiredPosts() {
    if (!expirationEnabled) {
      return;
    }
    log.info("Post expiration cleanup job started.");
    try {
      var missing = postRepository.findByExpiresOnIsNull();
      if (!missing.isEmpty()) {
        missing.forEach(p -> {
          refreshExpiration(p);
          postRepository.save(p);
        });
      }
      postRepository.findByExpiresOnLessThanEqual(Instant.now()).forEach(this::deletePostTree);
    } finally {
      log.info("Post expiration cleanup job completed.");
    }
  }

  /**
   * Builds a bounded first-page request for feed endpoints.
   *
   * <p>All feed APIs use the same newest-first pagination contract, so keeping
   * this in one place prevents each endpoint from drifting on limit bounds or
   * sort field.</p>
   */
  private Pageable newFeedPage(int requestedLimit) {
    int pageSize = Math.max(MIN_FEED_LIMIT, Math.min(requestedLimit, MAX_FEED_LIMIT));
    return PageRequest.of(0, pageSize, Sort.by(Sort.Direction.DESC, "createdOn"));
  }

  /**
   * Resolves account ids to usernames in one repository call.
   *
   * <p>Feed rendering needs usernames, but posts only store author ids. This
   * helper keeps the batch lookup explicit and avoids N+1 account queries.</p>
   */
  private Map<String, String> usernamesByAccountId(List<String> accountIds) {
    return accountRepository.findAllById(accountIds).stream()
        .collect(Collectors.toMap(Account::getId, Account::getUsername));
  }

  /**
   * Returns the current account id when a request is authenticated.
   *
   * <p>Public feed routes are useful to anonymous visitors, so a missing or
   * invalid JWT should only disable viewer-specific fields like {@code liked}.
   * Authenticated write paths still call {@link #getSelfId()} directly.</p>
   */
  private String getSelfIdOrNull() {
    try {
      return getSelfId();
    } catch (Exception ignored) {
      return null;
    }
  }

  /**
   * Maps a persisted post into the shared feed DTO.
   *
   * <p>This is intentionally the only builder for feed items so likes, reply
   * counts, expiration timestamps, and viewer-specific liked state remain
   * consistent across global, user, following, thread, and single-post views.</p>
   */
  private PostFeedItem toFeedItem(Post post, String username, String currentUserId) {
    return PostFeedItem.builder()
        .id(post.getId())
        .accountId(post.getAccountId())
        .username(username)
        .text(post.getText())
        .linkPreviews(post.getLinkPreviews())
        .rootId(post.getRootId())
        .parentId(post.getParentId())
        .level(post.getLevel())
        .likesCount(post.getLikesCount())
        .liked(currentUserId != null
            && post.getLikedBy() != null
            && post.getLikedBy().contains(currentUserId))
        .replyCount((int) postRepository.countByParentId(post.getId()))
        .createdOn(post.getCreatedOn())
        .lastUpdatedOn(post.getLastUpdatedOn())
        .expiresOn(post.getExpiresOn())
        .build();
  }

  private static Instant calculateExpiration(Instant createdOn, int likesCount) {
    Instant base = createdOn != null ? createdOn : Instant.now();
    long likeCount = Math.max(0, likesCount);
    return base.plus(BASE_LIFESPAN).plus(EXTENSION_PER_LIKE.multipliedBy(likeCount));
  }

  private static Instant expirationForNewPost(Instant createdOn, Instant inheritedReplyExpiration) {
    return inheritedReplyExpiration != null ? inheritedReplyExpiration : calculateExpiration(createdOn, 0);
  }

  private void refreshExpiration(Post post) {
    if (!expirationEnabled || post == null) {
      return;
    }
    if (isReply(post)) {
      setReplyExpirationFromRoot(post);
      return;
    }
    int likes = post.getLikesCount() != null ? post.getLikesCount() : 0;
    int replyLikes = post.getThreadReplyLikesCount() != null ? post.getThreadReplyLikesCount() : 0;
    int replies = threadReplyCount(post);
    post.setExpiresOn(calculateExpiration(post.getCreatedOn(), likes + replyLikes + replies));
  }

  private void ensureExpirationSet(Post post) {
    if (!expirationEnabled || post == null) {
      return;
    }
    if (isReply(post)) {
      if (setReplyExpirationFromRoot(post)) {
        postRepository.save(post);
      }
      return;
    }
    var previousExpiration = post.getExpiresOn();
    refreshExpiration(post);
    if (!Objects.equals(previousExpiration, post.getExpiresOn())) {
      postRepository.save(post);
      synchronizeReplyExpirations(post);
    }
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
    if (isReply(post)) {
      setReplyExpirationFromRoot(post);
    }
    if (isExpired(post)) {
      deletePostTree(post);
      throw new ResourceNotFoundException(String.format("Post with id %s not found.", post.getId()));
    }
  }

  private Post activeThreadRootForReply(Post post) throws ResourceNotFoundException {
    if (post == null || post.getParentId() == null || post.getParentId().isBlank()) {
      return null;
    }
    var rootId = post.getRootId();
    if (rootId == null || rootId.isBlank() || rootId.equals(post.getId())) {
      return null;
    }
    var root = postRepository.findById(rootId)
        .orElseThrow(() -> new ResourceNotFoundException(String.format("Post with id %s not found.", rootId)));
    ensureActive(root);
    return root;
  }

  private void refreshThreadRootExpiration(Post threadRoot, int replyLikeDelta) {
    if (!expirationEnabled || threadRoot == null || replyLikeDelta == 0) {
      return;
    }
    var count = threadRoot.getThreadReplyLikesCount() != null ? threadRoot.getThreadReplyLikesCount() : 0;
    threadRoot.setThreadReplyLikesCount(Math.max(0, count + replyLikeDelta));
    threadRoot.setLastUpdatedOn(Instant.now());
    refreshExpiration(threadRoot);
    postRepository.save(threadRoot);
    synchronizeReplyExpirations(threadRoot);
  }

  private void refreshThreadRootExpirationForNewReply(Post reply) throws ResourceNotFoundException {
    if (!expirationEnabled || !isReply(reply)) {
      return;
    }
    activeThreadRootForReply(reply);
  }

  private boolean isReply(Post post) {
    return post != null && post.getParentId() != null && !post.getParentId().isBlank();
  }

  private int threadReplyCount(Post post) {
    if (post == null || isReply(post)) {
      return 0;
    }
    var rootId = post.getRootId() != null && !post.getRootId().isBlank()
        ? post.getRootId()
        : post.getId();
    if (rootId == null || rootId.isBlank()) {
      return 0;
    }
    return (int) postRepository.findByRootIdOrderByCreatedOnAsc(rootId).stream()
        .filter(this::isReply)
        .count();
  }

  /**
   * Returns the root expiration a new reply should inherit.
   *
   * <p>Nested replies point at their immediate parent, but all expiration math
   * belongs to the thread root so the full conversation disappears together.</p>
   */
  private Instant rootExpirationFor(Post post, String rootId) throws ResourceNotFoundException {
    if (!expirationEnabled || post == null) {
      return null;
    }
    if (post.getId() != null && post.getId().equals(rootId)) {
      return post.getExpiresOn();
    }
    var root = postRepository.findById(rootId)
        .orElseThrow(() -> new ResourceNotFoundException(String.format("Post with id %s not found.", rootId)));
    ensureActive(root);
    return root.getExpiresOn();
  }

  /**
   * Aligns an existing reply with its thread root expiration.
   *
   * <p>Older reply documents may already have an independent expiration. Reads
   * call this helper so those documents converge without a one-off migration.</p>
   */
  private boolean setReplyExpirationFromRoot(Post reply) {
    if (!expirationEnabled || !isReply(reply)) {
      return false;
    }
    var rootId = reply.getRootId();
    if (rootId == null || rootId.isBlank() || rootId.equals(reply.getId())) {
      return false;
    }
    var rootExpiration = postRepository.findById(rootId).map(Post::getExpiresOn);
    if (rootExpiration.isEmpty() || rootExpiration.get() == null || rootExpiration.get().equals(reply.getExpiresOn())) {
      return false;
    }
    reply.setExpiresOn(rootExpiration.get());
    return true;
  }

  /**
   * Pushes the current root expiration through every nested reply.
   *
   * <p>Root likes and reply likes both change the root lifespan. Saving the
   * synchronized timestamp keeps future feed and cleanup reads simple.</p>
   */
  private void synchronizeReplyExpirations(Post post) {
    if (!expirationEnabled || post == null || isReply(post)) {
      return;
    }
    var rootId = post.getRootId() != null && !post.getRootId().isBlank()
        ? post.getRootId()
        : post.getId();
    var rootExpiration = post.getExpiresOn();
    postRepository.findByRootIdOrderByCreatedOnAsc(rootId).stream()
        .filter(this::isReply)
        .filter(reply -> rootExpiration != null && !rootExpiration.equals(reply.getExpiresOn()))
        .forEach(reply -> {
          reply.setExpiresOn(rootExpiration);
          postRepository.save(reply);
        });
  }

  private void deletePostTree(Post post) {
    if (post == null) {
      return;
    }
    var rootId = post.getRootId() != null && !post.getRootId().isBlank()
        ? post.getRootId()
        : post.getId();
    var thread = new ArrayList<>(postRepository.findByRootIdOrderByCreatedOnAsc(rootId));
    if (thread.stream().noneMatch(candidate -> candidate.getId().equals(post.getId()))) {
      thread.add(post);
    }

    var idsToDelete = new HashSet<String>();
    idsToDelete.add(post.getId());
    var subtree = new ArrayList<Post>();
    for (Post candidate : thread) {
      if (idsToDelete.contains(candidate.getId())
          || (candidate.getParentId() != null && idsToDelete.contains(candidate.getParentId()))) {
        idsToDelete.add(candidate.getId());
        subtree.add(candidate);
      }
    }
    if (subtree.isEmpty()) {
      subtree.add(post);
    }
    postRepository.deleteAll(subtree);
  }
}
