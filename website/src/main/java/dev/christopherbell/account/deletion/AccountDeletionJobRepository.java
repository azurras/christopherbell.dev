package dev.christopherbell.account.deletion;

import java.util.Optional;

/** Persistence for pseudonymous account-deletion checkpoints. */
public interface AccountDeletionJobRepository {
  Optional<AccountDeletionJob> findById(String id);
  AccountDeletionJob save(AccountDeletionJob job);
}
