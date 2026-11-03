package dev.christopherbell.post.expiration;

import dev.christopherbell.libs.api.exception.ResourceNotFoundException;
import dev.christopherbell.post.PostRepository;
import dev.christopherbell.post.model.Post;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Objects;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** Owns post lifespan calculations, repair, synchronization, and cleanup. */
@Service
@Slf4j
public class PostExpirationService {
  private static final Duration BASE_LIFESPAN = Duration.ofHours(24);
  private static final Duration EXTENSION_PER_LIKE = Duration.ofHours(24);
  private static final int MAINTENANCE_BATCH_SIZE = 250;

  private final PostRepository postRepository;
  private final PostExpirationStore store;
  private final Clock clock;
  private final boolean expirationEnabled;

  @Autowired
  public PostExpirationService(
      PostRepository postRepository,
      PostExpirationStore store,
      Clock clock,
      @Value("${posts.expiration.enabled:false}") boolean expirationEnabled) {
    this.postRepository = postRepository;
    this.store = store;
    this.clock = clock;
    this.expirationEnabled = expirationEnabled;
  }

  /** Test-only compatibility constructor for focused calculation tests. */
  public PostExpirationService(PostRepository postRepository, boolean expirationEnabled) {
    this.postRepository = postRepository;
    this.store = null;
    this.clock = Clock.systemUTC();
    this.expirationEnabled = expirationEnabled;
  }

  /** Calculates a root post expiration from creation time and extension count. */
  public Instant calculateExpiration(Instant createdOn, int extensionCount) {
    Instant base = createdOn != null ? createdOn : clock.instant();
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
    int replies = post.getThreadReplyCount() == null ? 0 : post.getThreadReplyCount();
    post.setExpiresOn(calculateExpiration(post.getCreatedOn(), likes + replyLikes + replies));
  }

  /** Returns whether a post has reached its expiration timestamp. */
  public boolean isExpired(Post post) {
    if (!expirationEnabled || post == null) {
      return false;
    }
    if (isReply(post)) {
      var root = postRepository.findById(post.getRootId()).orElse(null);
      if (root == null) {
        return true;
      }
      post.setExpiresOn(root.getExpiresOn());
    }
    Instant expiresOn = post.getExpiresOn();
    return expiresOn != null && !expiresOn.isAfter(clock.instant());
  }

  /** Returns the active-feed cutoff when expiration is enabled. */
  public Optional<Instant> activeCutoff() {
    return expirationEnabled ? Optional.of(clock.instant()) : Optional.empty();
  }

  /** Rejects expired replies and conditionally claims a currently expired root before cleanup. */
  public void ensureActive(Post post) throws ResourceNotFoundException {
    if (!isExpired(post)) {
      return;
    }
    if (store != null && !isReply(post)) {
      var removed = store.removeExpiredRoot(post.getId(), clock.instant());
      if (removed.isPresent()) {
        deletePostTree(removed.orElseThrow());
      } else {
        var current = postRepository.findById(post.getId());
        if (current.isPresent() && !isExpired(current.orElseThrow())) {
          post.setExpiresOn(current.orElseThrow().getExpiresOn());
          return;
        }
      }
    } else if (store == null) {
      deletePostTree(post);
    }
    throw new ResourceNotFoundException(String.format("Post with id %s not found.", post.getId()));
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

  /** Atomically updates the root's reply-like counter and its authoritative deadline. */
  public void refreshThreadRootExpiration(
      Post threadRoot, int replyLikeDelta, Instant extendedOn) {
    if (!expirationEnabled || threadRoot == null || replyLikeDelta == 0) {
      return;
    }
    var now = extendedOn != null ? extendedOn : clock.instant();
    var updated = incrementCounter(
        threadRoot, "threadReplyLikesCount", replyLikeDelta, now, replyLikeDelta > 0);
    requireMutationTarget(updated);
    refreshAndPersistExpiration(updated);
  }

  /** Refreshes the thread root after a new reply has been saved. */
  public void refreshThreadRootExpirationForNewReply(Post reply, Instant extendedOn)
      throws ResourceNotFoundException {
    if (!expirationEnabled || !isReply(reply)) {
      return;
    }
    var threadRoot = activeThreadRootForReply(reply);
    var updated = incrementCounter(
        threadRoot, "threadReplyCount", 1, Objects.requireNonNull(extendedOn), true);
    requireMutationTarget(updated);
    refreshAndPersistExpiration(updated);
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
    var rootExpiration = postRepository.findById(rootId)
        .map(Post::getExpiresOn)
        .filter(expiresOn -> !expiresOn.equals(reply.getExpiresOn()));
    if (rootExpiration.isEmpty()) {
      return false;
    }
    reply.setExpiresOn(rootExpiration.orElseThrow());
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
    if (store != null) {
      store.synchronizeReplies(rootId, post.getId(), rootExpiration);
      return;
    }
    postRepository.findByRootIdOrderByCreatedOnAsc(rootId).stream()
        .filter(this::isReply)
        .filter(reply -> rootExpiration != null && !rootExpiration.equals(reply.getExpiresOn()))
        .forEach(reply -> {
          reply.setExpiresOn(rootExpiration);
          postRepository.save(reply);
        });
  }

  /** Atomically applies a real like-edge transition and returns current post state. */
  public Post applyLikeTransition(
      Post post, Post threadRoot, int delta, Instant changedOn) {
    if (post == null || delta == 0) {
      return post;
    }
    var updated = incrementCounter(post, "likesCount", delta, changedOn, delta > 0);
    requireMutationTarget(updated);
    refreshAndPersistExpiration(updated);
    refreshThreadRootExpiration(threadRoot, delta, delta > 0 ? changedOn : null);
    if (isReply(updated)) {
      setReplyExpirationFromRoot(updated);
    }
    return updated;
  }

  /** Deletes a post and all descendants, then reconciles relationship and root metrics. */
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
    long removedCount;
    if (store == null) {
      postRepository.deleteAll(subtree);
      removedCount = subtree.size();
    } else {
      var postIds = subtree.stream().map(Post::getId).toList();
      removedCount = store.deletePosts(postIds);
    }
    if (isReply(post) && removedCount > 0) {
      thread.stream()
          .filter(candidate -> rootId.equals(candidate.getId()))
          .findFirst()
          .ifPresent(root -> decrementReplyCount(root, removedCount));
    }
  }

  /** Scheduled cleanup for expired post trees and older documents missing expiration data. */
  @Scheduled(fixedDelayString = "${posts.expiration.cleanup-interval}")
  public void purgeExpiredPosts() {
    if (!expirationEnabled) {
      return;
    }
    var page = PageRequest.of(
        0,
        MAINTENANCE_BATCH_SIZE,
        Sort.by(Sort.Direction.ASC, "id"));
    var missing = postRepository.findByExpiresOnIsNull(page);
    if (!missing.isEmpty()) {
      missing.forEach(post -> {
        if (store != null && !isReply(post)) {
          store.incrementCounter(post.getId(), "likesCount", 0, clock.instant(), false);
        } else if (store == null) {
          refreshExpiration(post);
          postRepository.save(post);
        }
      });
      log.info("Post expiration cleanup repaired {} posts missing expiration timestamps.", missing.size());
    }

    var cutoff = clock.instant();
    var expired = postRepository.findByExpiresOnLessThanEqual(cutoff, page);
    int removed = 0;
    for (Post candidate : expired) {
      if (isReply(candidate)) {
        continue;
      }
      if (store == null) {
        deletePostTree(candidate);
        removed++;
      } else {
        var claimed = store.removeExpiredRoot(candidate.getId(), cutoff);
        if (claimed.isPresent()) {
          deletePostTree(claimed.orElseThrow());
          removed++;
        }
      }
    }
    if (removed > 0) {
      log.info("Post expiration cleanup deleted {} expired post trees.", removed);
    }
  }

  private boolean isReply(Post post) {
    return post != null && post.getParentId() != null && !post.getParentId().isBlank();
  }

  private Post incrementCounter(
      Post fallback, String field, int delta, Instant changedOn, boolean extended) {
    if (store == null) {
      int current = switch (field) {
        case "likesCount" -> fallback.getLikesCount() == null ? 0 : fallback.getLikesCount();
        case "threadReplyLikesCount" -> fallback.getThreadReplyLikesCount() == null
            ? 0 : fallback.getThreadReplyLikesCount();
        case "threadReplyCount" -> fallback.getThreadReplyCount() == null
            ? 0 : fallback.getThreadReplyCount();
        default -> throw new IllegalArgumentException("Unsupported post counter.");
      };
      int next = Math.max(0, current + delta);
      if (field.equals("likesCount")) {
        fallback.setLikesCount(next);
      }
      if (field.equals("threadReplyLikesCount")) {
        fallback.setThreadReplyLikesCount(next);
      }
      if (field.equals("threadReplyCount")) {
        fallback.setThreadReplyCount(next);
      }
      fallback.setLastUpdatedOn(changedOn);
      if (extended) {
        fallback.setLastExtendedOn(changedOn);
      }
      postRepository.save(fallback);
      return fallback;
    }
    return store.incrementCounter(fallback.getId(), field, delta, changedOn, extended)
        .orElse(null);
  }

  private void decrementReplyCount(Post root, long removedCount) {
    int delta = Math.toIntExact(Math.min(removedCount, Integer.MAX_VALUE));
    var changedOn = clock.instant();
    if (store == null) {
      var updated = incrementCounter(root, "threadReplyCount", -delta, changedOn, false);
      requireMutationTarget(updated);
      refreshAndPersistExpiration(updated);
      return;
    }
    var updated = store.incrementCounter(
        root.getId(), "threadReplyCount", -delta, changedOn, false).orElse(null);
    requireMutationTarget(updated);
    refreshAndPersistExpiration(updated);
  }

  private static void requireMutationTarget(Post post) {
    if (post == null) {
      throw new ResponseStatusException(
          HttpStatus.NOT_FOUND, "Post no longer exists.");
    }
  }

  private void refreshAndPersistExpiration(Post post) {
    if (post == null) {
      return;
    }
    if (store == null) {
      refreshExpiration(post);
      postRepository.save(post);
      synchronizeReplyExpirations(post);
    }
    // The production store derives the root deadline in the same write as its signed delta.
    // Replies resolve their root's deadline at reads; competing bulk copies cannot regress it.
  }
}
