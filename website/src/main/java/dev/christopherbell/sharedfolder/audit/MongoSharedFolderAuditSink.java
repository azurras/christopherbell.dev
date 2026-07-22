package dev.christopherbell.sharedfolder.audit;

import dev.christopherbell.configuration.SharedFolderProperties;
import java.util.Objects;
import org.springframework.stereotype.Component;

/** Persists bounded audit commands with the configured absolute TTL deadline. */
@Component
public final class MongoSharedFolderAuditSink implements SharedFolderAuditSink {
  private final SharedFolderAuditRepository repository;
  private final SharedFolderProperties properties;

  public MongoSharedFolderAuditSink(
      SharedFolderAuditRepository repository, SharedFolderProperties properties) {
    this.repository = Objects.requireNonNull(repository);
    this.properties = Objects.requireNonNull(properties);
  }

  @Override
  public void record(SharedFolderAuditCommand command) {
    Objects.requireNonNull(command, "audit command is required");
    repository.save(SharedFolderAuditEvent.from(
        command, command.occurredAt().plus(properties.auditRetention())));
  }
}
