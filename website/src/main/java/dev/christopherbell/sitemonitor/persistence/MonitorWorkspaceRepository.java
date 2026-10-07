package dev.christopherbell.sitemonitor.persistence;

import dev.christopherbell.sitemonitor.model.MonitorWorkspace;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Persistence boundary for capped, versioned private pilot workspaces. */
public interface MonitorWorkspaceRepository {
  Optional<MonitorWorkspace> findByAccountId(String accountId);

  /** Inserts a new workspace into a free pilot slot, or updates an existing one optimistically. */
  MonitorWorkspace save(MonitorWorkspace workspace);

  List<MonitorWorkspace> listAll();

  long count();

  /** Deletes the workspace only if it still has the expected version. */
  void delete(MonitorWorkspace expected);

  /** Called only while holding the global pilot lease; reserves one automatic attempt. */
  boolean claimScheduledMinute(Instant now);
}
