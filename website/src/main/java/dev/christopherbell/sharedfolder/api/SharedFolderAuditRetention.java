package dev.christopherbell.sharedfolder.api;

import java.time.Instant;

/** Published boundary for bounded shared-folder audit retention. */
public interface SharedFolderAuditRetention {
  int deleteExpired(Instant cutoff, int limit);
}
