package dev.christopherbell.account.deletion;

/** Idempotent persistence and storage effects used by the durable deletion coordinator. */
public interface AccountDeletionOperations {
  boolean accountExists(String accountId);

  void ensureTombstone();

  void anonymizePublicPosts(String accountId, String pseudonym);

  void removePrivateData(String accountId);

  void cleanSharedFolderState(String accountId);

  void pseudonymizeRetainedRecords(String accountId, String pseudonym);

  void removeReferencesAndAccount(String accountId);
}
