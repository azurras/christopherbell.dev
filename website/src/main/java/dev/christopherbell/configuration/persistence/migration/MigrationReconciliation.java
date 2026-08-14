package dev.christopherbell.configuration.persistence.migration;

/** Exact staged-kind reconciliation evidence required before publication. */
public record MigrationReconciliation(
    boolean stagingComplete,
    long sourceCount,
    long stagedCount,
    String sourceDigest,
    String reconstructedSourceDigest,
    boolean relationshipsValid,
    boolean portQueriesValid) {
  public boolean equivalent() {
    return stagingComplete
        && sourceCount == stagedCount
        && sourceDigest != null
        && sourceDigest.equals(reconstructedSourceDigest)
        && relationshipsValid
        && portQueriesValid;
  }
}
