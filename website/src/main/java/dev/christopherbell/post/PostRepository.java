package dev.christopherbell.post;

import dev.christopherbell.post.model.Post;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/** Persistence port for {@link Post} documents. */
public interface PostRepository {
  Post save(Post post);
  /** Replaces only edit-owned fields if the observed text/revision still exists. Never inserts. */
  Optional<Post> editIfUnchanged(Post observed, Post replacement, Instant editingOn,
      Instant oldestEditableCreation, int auditLimit, boolean requireActiveExpiration);
  Optional<Post> findById(String id);
  void delete(Post post);
  void deleteById(String id);
  void deleteAll(Iterable<Post> posts);
  long count();
  /**
   * Retrieves posts for a given account, newest first.
   *
   * @param accountId the owning account id
   * @return list of posts ordered by {@code createdOn} descending
   */
  List<Post> findByAccountIdOrderByCreatedOnDesc(String accountId);
  /**
   * Retrieves posts for a given account, newest first, with pagination.
   *
   * @param accountId the owning account id
   * @param pageable  page request (size, sort)
   * @return a page slice of posts ordered by {@code createdOn} descending
   */
  List<Post> findByAccountIdOrderByCreatedOnDesc(String accountId, Pageable pageable);

  /**
   * Retrieves a page of posts across all accounts ordered by created time descending.
   */
  Page<Post> findAll(Pageable pageable);

  /** All posts in a thread (includes root) ordered oldest-first (by createdOn). */
  List<Post> findByRootIdOrderByCreatedOnAsc(String rootId);

  /** Finds thread roots whose current expiration is at or before the provided instant. */
  List<Post> findByExpiresOnLessThanEqual(Instant cutoff, Pageable pageable);

  /** Counts posts whose authoritative thread expiration remains in the future. */
  long countByExpiresOnAfter(Instant cutoff);

  /** Pages posts whose authoritative thread expiration remains in the future. */
  Page<Post> findByExpiresOnAfter(Instant cutoff, Pageable pageable);

  /** Finds thread roots that have not been assigned an expiration timestamp yet. */
  List<Post> findByExpiresOnIsNull(Pageable pageable);

  /** Count root posts authored by an account. */
  long countByAccountIdAndParentIdIsNull(String accountId);

  /** Count reply posts authored by an account. */
  long countByAccountIdAndParentIdIsNotNull(String accountId);

  /** Stable ascending scan of posts eligible for federation after an optional tuple boundary. */
  List<Post> findFederationEligibleAfter(Instant createdOn, String postId, int limit);

  /** Stable descending page of active posts exposed through a local federation outbox. */
  List<Post> findFederationOutboxPage(
      String accountId, Instant createdOn, String postId, int limit, Instant expiresAfter);

}
