package dev.christopherbell.sharedfolder.audit;

/**
 * Boundary for recording a bounded shared-folder audit command.
 *
 * <p>Implementations receive only the bounded command shape; request bodies, secrets, absolute
 * paths, and exception text cannot cross this boundary.
 */
@FunctionalInterface
public interface SharedFolderAuditSink {
  void record(SharedFolderAuditCommand command);
}
