package dev.christopherbell.configuration.persistence.migration;

/** Read-only identity checks performed after untrusted command syntax passes validation. */
public interface MigrationIdentityProbe {
  MigrationDatabaseIdentity sourceIdentity(MigrationRequest request);

  MigrationDatabaseIdentity targetIdentity(MigrationRequest request);
}
