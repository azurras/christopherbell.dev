package dev.christopherbell.account.deletion;

/** Ordered, independently idempotent account-deletion checkpoints. */
public enum AccountDeletionStep {
  ENSURE_TOMBSTONE("tombstone"),
  ANONYMIZE_PUBLIC_POSTS("public_posts"),
  REMOVE_PRIVATE_DATA("private_data"),
  CLEAN_SHARED_FOLDER_STATE("shared_folder"),
  PSEUDONYMIZE_RETAINED_RECORDS("retained_records"),
  REMOVE_REFERENCES_AND_ACCOUNT("account_record");

  private final String failureCategory;

  AccountDeletionStep(String failureCategory) {
    this.failureCategory = failureCategory;
  }

  public String failureCategory() {
    return failureCategory;
  }

  void execute(AccountDeletionOperations operations, String accountId, String pseudonym) {
    switch (this) {
      case ENSURE_TOMBSTONE -> operations.ensureTombstone();
      case ANONYMIZE_PUBLIC_POSTS -> operations.anonymizePublicPosts(accountId);
      case REMOVE_PRIVATE_DATA -> operations.removePrivateData(accountId);
      case CLEAN_SHARED_FOLDER_STATE -> operations.cleanSharedFolderState(accountId);
      case PSEUDONYMIZE_RETAINED_RECORDS ->
          operations.pseudonymizeRetainedRecords(accountId, pseudonym);
      case REMOVE_REFERENCES_AND_ACCOUNT -> operations.removeReferencesAndAccount(accountId);
    }
  }
}
