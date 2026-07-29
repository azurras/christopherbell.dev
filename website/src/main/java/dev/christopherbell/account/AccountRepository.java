package dev.christopherbell.account;

import dev.christopherbell.account.model.Account;
import dev.christopherbell.account.model.AccountStatus;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

/**
 * Repository interface for managing {@link Account} entities in MongoDB.
 *
 * <p>
 * This interface extends {@link MongoRepository}, providing CRUD operations
 * and custom query methods for the {@link Account} entity. It leverages
 * Spring Data's repository abstraction to simplify data access and manipulation.
 * </p>
 *
 * <p>
 * The primary key type for {@link Account} is {@link String}.
 * </p>
 *
 * <p>
 * Custom query methods can be defined by following Spring Data's method
 * naming conventions, allowing for automatic implementation of common
 * queries without the need for boilerplate code.
 * </p>
 *
 * @see MongoRepository
 * @see Account
 */
@Repository
public interface AccountRepository extends MongoRepository<Account, String> {

  /**
   * Retrieves an {@link Account} by its unique email address.
   *
   * <p>
   * This query method leverages Spring Data's derived query generation.
   * If an {@code AccountEntity} with the given email exists, it will be
   * returned wrapped in an {@link Optional}; otherwise, the result will
   * be {@link Optional#empty()}.
   * </p>
   *
   * @param email the email address to look up (must not be {@code null})
   * @return an {@link Optional} containing the matching {@link Account}
   *         if found, or {@link Optional#empty()} if no match exists
   */
  Optional<Account> findByEmail(String email);

  /**
   * Retrieves an {@link Account} by email address without considering letter case.
   *
   * @param email the email address to look up (must not be {@code null})
   * @return an {@link Optional} containing the matching {@link Account}
   *         if found, or {@link Optional#empty()} if no match exists
   */
  Optional<Account> findByEmailIgnoreCase(String email);

  /**
   * Retrieves an {@link Account} by the stored password reset token hash.
   *
   * @param passwordResetTokenHash the hashed password reset token
   * @return an {@link Optional} containing the matching {@link Account}
   *         if found, or {@link Optional#empty()} if no match exists
   */
  Optional<Account> findByPasswordResetTokenHash(String passwordResetTokenHash);

  /**
   * Finds an {@link Account} by its unique username.
   *
   * <p>
   * This query method is automatically implemented by Spring Data based
   * on the method name. If a record with the specified username exists,
   * it will be returned wrapped in an {@link Optional}; otherwise, the
   * result will be {@link Optional#empty()}.
   * </p>
   *
   * @param username the username to search for (must not be {@code null})
   * @return an {@link Optional} containing the matching {@link Account}
   *         if found, or {@link Optional#empty()} if no match exists
   */
  Optional<Account> findByUsername(String username);

  /** Finds one publicly visible account in the requested lifecycle state. */
  Optional<Account> findByUsernameAndStatus(String username, AccountStatus status);

  /**
   * Finds an {@link Account} by username without considering letter case.
   *
   * @param username the username to search for (must not be null)
   * @return an {@link Optional} containing the matching account if found
   */
  Optional<Account> findByUsernameIgnoreCase(String username);

  /** Resolves an active account that explicitly exposes a local federation actor. */
  Optional<Account> findByUsernameIgnoreCaseAndStatusAndFederationEnabledTrue(
      String username,
      AccountStatus status);

  /** Counts accounts in one lifecycle state for aggregate public metadata. */
  long countByStatus(AccountStatus status);

  /** Pages active public-account candidates for crawler metadata. */
  Page<Account> findByStatus(AccountStatus status, Pageable pageable);

  /**
   * Finds active account suggestions whose usernames start with a prefix.
   *
   * @param usernamePrefix username prefix typed by the caller
   * @param status account status to include
   * @param pageable result cap and paging information
   * @return matching accounts sorted by username
   */
  List<Account> findByUsernameStartingWithIgnoreCaseAndStatusOrderByUsernameAsc(
      String usernamePrefix,
      AccountStatus status,
      Pageable pageable);

  /**
   * Counts accounts that are following the provided account id.
   *
   * @param accountId the followed account id
   * @return number of follower accounts
   */
  long countByFollowingIdsContaining(String accountId);

  /** Loads a bounded local-only following projection. */
  List<Account> findByIdInAndStatusAndFederationEnabledTrueOrderByUsernameAsc(
      Collection<String> accountIds,
      AccountStatus status,
      Pageable pageable);

  /** Loads a bounded local-only follower projection. */
  List<Account> findByFollowingIdsContainingAndStatusAndFederationEnabledTrueOrderByUsernameAsc(
      String accountId,
      AccountStatus status,
      Pageable pageable);
}
