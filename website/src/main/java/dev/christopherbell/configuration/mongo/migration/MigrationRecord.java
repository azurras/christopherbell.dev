package dev.christopherbell.configuration.mongo.migration;

import java.time.Instant;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/** Durable audit record for a versioned application migration. */
@Data
@NoArgsConstructor
@Document(MigrationRecord.COLLECTION)
public class MigrationRecord {
  public static final String COLLECTION = "application_migrations";

  @Id private String id;
  private String checksum;
  private String description;
  private MigrationStatus status;
  private String ownerToken;
  private Instant startedAt;
  private Instant completedAt;
  private String failureCategory;

  static MigrationRecord running(
      ApplicationMigration migration, String ownerToken, Instant startedAt) {
    var record = new MigrationRecord();
    record.setId(migration.id());
    record.setChecksum(migration.checksum());
    record.setDescription(migration.description());
    record.setStatus(MigrationStatus.RUNNING);
    record.setOwnerToken(ownerToken);
    record.setStartedAt(startedAt);
    return record;
  }
}
