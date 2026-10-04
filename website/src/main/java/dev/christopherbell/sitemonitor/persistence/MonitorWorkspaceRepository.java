package dev.christopherbell.sitemonitor.persistence;

import dev.christopherbell.sitemonitor.model.MonitorWorkspace;
import java.util.List;
import java.util.Optional;

/** Persistence boundary for capped, versioned private pilot workspaces. */
public interface MonitorWorkspaceRepository {
  Optional<MonitorWorkspace> find(String accountId);
  MonitorWorkspace save(MonitorWorkspace workspace);
  List<MonitorWorkspace> list();
  long count();
  void delete(MonitorWorkspace expected);
  /** Called only while holding the global pilot lease; reserves one automatic attempt. */
  boolean claimScheduledMinute(java.time.Instant now);
}
