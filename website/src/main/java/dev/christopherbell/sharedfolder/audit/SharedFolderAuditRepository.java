package dev.christopherbell.sharedfolder.audit;

import dev.christopherbell.sharedfolder.api.SharedFolderAuditRetention;
import java.time.Instant;
import java.util.List;

/** Persistence boundary for bounded shared-folder audit events. */
public interface SharedFolderAuditRepository extends SharedFolderAuditRetention {
  SharedFolderAuditEvent save(SharedFolderAuditEvent event);

  @Override
  int deleteExpired(Instant cutoff, int limit);

  List<SharedFolderAuditEvent> search(
      String accountId,
      String action,
      String outcome,
      String relativePath,
      Instant from,
      Instant to,
      int limit);
}
