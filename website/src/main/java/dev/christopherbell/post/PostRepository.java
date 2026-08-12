package dev.christopherbell.post;

import dev.christopherbell.post.model.Post;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * Spring Data Mongo repository for {@link dev.christopherbell.post.model.Post} entities.
 */
public interface PostRepository {
  Post save(Post post);
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

  /** Finds posts whose expiration timestamp is at or before the provided instant. */
  List<Post> findByExpiresOnLessThanEqual(Instant cutoff, Pageable pageable);

  /** Counts posts whose configured expiration remains in the future. */
  long countByExpiresOnAfter(Instant cutoff);

  /** Pages posts whose configured expiration remains in the future. */
  Page<Post> findByExpiresOnAfter(Instant cutoff, Pageable pageable);

  /** Finds posts that have not been assigned an expiration timestamp yet. */
  List<Post> findByExpiresOnIsNull(Pageable pageable);

  /** Count root posts authored by an account. */
  long countByAccountIdAndParentIdIsNull(String accountId);

  /** Count reply posts authored by an account. */
  long countByAccountIdAndParentIdIsNotNull(String accountId);

}
