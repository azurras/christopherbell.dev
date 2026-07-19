package dev.christopherbell.sharedfolder.audit;

import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

/** Bounded persisted shared-folder audit event with a MongoDB TTL deadline. */
@Document("shared_folder_audit")
@CompoundIndexes({
    @CompoundIndex(name = "shared_audit_occurred_desc", def = "{'occurredAt': -1}"),
    @CompoundIndex(name = "shared_audit_account_occurred_desc",
        def = "{'accountId': 1, 'occurredAt': -1}"),
    @CompoundIndex(name = "shared_audit_action_occurred_desc",
        def = "{'action': 1, 'occurredAt': -1}")
})
public record SharedFolderAuditEvent(
    @Id String id,
    @Indexed String accountId,
    @Indexed String action,
    String relativePath,
    Long size,
    String outcome,
    String failureCategory,
    String clientIp,
    @Indexed Instant occurredAt,
    @Indexed(expireAfter = "0s") Instant expiresAt) {

  /** Converts only an already validated bounded command into persistence data. */
  public static SharedFolderAuditEvent from(
      SharedFolderAuditCommand command, Instant expiresAt) {
    if (command == null || expiresAt == null || expiresAt.isBefore(command.occurredAt())) {
      throw new IllegalArgumentException("Audit expiry is invalid");
    }
    return new SharedFolderAuditEvent(
        null, command.accountId(), command.action(), command.relativePathOrResourceId(),
        command.size(), command.outcome(), command.failureCategory(), command.clientIp(),
        command.occurredAt(), expiresAt);
  }
}
