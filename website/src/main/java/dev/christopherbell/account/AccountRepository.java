package dev.christopherbell.account;

import dev.christopherbell.account.model.Account;
import dev.christopherbell.account.model.AccountStatus;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.dao.IncorrectResultSizeDataAccessException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * Service-facing persistence port for {@link Account} documents, keyed by {@link String} id.
 *
 * @see Account
 */
public interface AccountRepository {
  Account save(Account account);
  Optional<Account> findById(String id);
  boolean existsById(String id);
  void deleteById(String id);
  Page<Account> findAll(Pageable pageable);
  List<Account> findAllById(Iterable<String> ids);

  /**
   * Retrieves an {@link Account} by its unique email address.
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
   * @throws IncorrectResultSizeDataAccessException if stored email case is ambiguous
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
   * @throws IncorrectResultSizeDataAccessException if stored username case is ambiguous
   */
  Optional<Account> findByUsernameIgnoreCase(String username);

  /**
   * Resolves an active account that explicitly exposes a local federation actor.
   *
   * @throws IncorrectResultSizeDataAccessException if eligible stored username case is ambiguous
   */
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

  /** Loads a bounded local-only following projection. */
  List<Account> findByIdInAndStatusAndFederationEnabledTrueOrderByUsernameAsc(
      Collection<String> accountIds,
      AccountStatus status,
      Pageable pageable);

}
