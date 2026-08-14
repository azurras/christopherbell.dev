package dev.christopherbell.sharedfolder.audit;

import java.time.Instant;
import java.util.List;

/** Persistence boundary for bounded shared-folder audit events. */
public interface SharedFolderAuditRepository {
  SharedFolderAuditEvent save(SharedFolderAuditEvent event);
  List<SharedFolderAuditEvent> search(
      String accountId,
      String action,
      String outcome,
      String relativePath,
      Instant from,
      Instant to,
      int limit);
}
