package dev.christopherbell.post.expiration;

import dev.christopherbell.libs.api.exception.ResourceNotFoundException;
import dev.christopherbell.post.PostRepository;
import dev.christopherbell.post.model.Post;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Objects;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/** Owns post lifespan calculations, repair, synchronization, and cleanup. */
@Service
@Slf4j
public class PostExpirationService {
  private static final Duration BASE_LIFESPAN = Duration.ofHours(24);
  private static final Duration EXTENSION_PER_LIKE = Duration.ofHours(24);

  private final PostRepository postRepository;
  private final boolean expirationEnabled;

  public PostExpirationService(
      PostRepository postRepository,
      @Value("${posts.expiration.enabled:false}") boolean expirationEnabled) {
    this.postRepository = postRepository;
    this.expirationEnabled = expirationEnabled;
  }

  /** Calculates a root post expiration from creation time and extension count. */
  public Instant calculateExpiration(Instant createdOn, int extensionCount) {
    Instant base = createdOn != null ? createdOn : Instant.now();
    long count = Math.max(0, extensionCount);
    return base.plus(BASE_LIFESPAN).plus(EXTENSION_PER_LIKE.multipliedBy(count));
  }

  /** Returns the correct initial expiration for a new root post or reply. */
  public Instant expirationForNewPost(Instant createdOn, Instant inheritedReplyExpiration) {
    if (!expirationEnabled) {
      return null;
    }
    return inheritedReplyExpiration != null ? inheritedReplyExpiration : calculateExpiration(createdOn, 0);
  }

  /** Recalculates a post's expiration in memory. */
  public void refreshExpiration(Post post) {
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

  /** Repairs missing or stale expiration data and persists the repair when needed. */
  public void ensureExpirationSet(Post post) {
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

  /** Returns whether a post has reached its expiration timestamp. */
  public boolean isExpired(Post post) {
    if (!expirationEnabled || post == null) {
      return false;
    }
    Instant expiresOn = post.getExpiresOn();
    return expiresOn != null && !expiresOn.isAfter(Instant.now());
  }

  /** Ensures a post is not expired, deleting expired subtrees before returning 404. */
  public void ensureActive(Post post) throws ResourceNotFoundException {
    ensureExpirationSet(post);
    if (isReply(post)) {
      setReplyExpirationFromRoot(post);
    }
    if (isExpired(post)) {
      deletePostTree(post);
      throw new ResourceNotFoundException(String.format("Post with id %s not found.", post.getId()));
    }
  }

  /** Returns the active root for a reply, or null for root posts. */
  public Post activeThreadRootForReply(Post post) throws ResourceNotFoundException {
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

  /** Updates a root's reply-like extension count and synchronizes reply expirations. */
  public void refreshThreadRootExpiration(Post threadRoot, int replyLikeDelta) {
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

  /** Refreshes the thread root after a new reply has been saved. */
  public void refreshThreadRootExpirationForNewReply(Post reply) throws ResourceNotFoundException {
    if (!expirationEnabled || !isReply(reply)) {
      return;
    }
    activeThreadRootForReply(reply);
  }

  /** Returns the root expiration a new reply should inherit. */
  public Instant rootExpirationFor(Post post, String rootId) throws ResourceNotFoundException {
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

  /** Aligns an existing reply with its thread root expiration. */
  public boolean setReplyExpirationFromRoot(Post reply) {
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

  /** Pushes the current root expiration through every nested reply. */
  public void synchronizeReplyExpirations(Post post) {
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

  /** Deletes a post and all of its descendants. */
  public void deletePostTree(Post post) {
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

  /** Scheduled cleanup for expired post trees and older documents missing expiration data. */
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
}
