package dev.christopherbell.account.deletion;

/** Owning storage boundary for private resources that are not safe to delete as raw Mongo rows. */
public interface AccountDeletionResourceCleaner {
  void deleteOwnedResources(String accountId);
}
