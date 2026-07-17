package dev.christopherbell.sharedfolder.audit;

/**
 * Boundary for recording a bounded shared-folder audit command.
 *
 * <p>Task 2 defines this contract only. A persistence implementation and operation-time
 * injection are intentionally deferred until the audit storage work is ready.
 */
@FunctionalInterface
public interface SharedFolderAuditSink {
  void record(SharedFolderAuditCommand command);
}
