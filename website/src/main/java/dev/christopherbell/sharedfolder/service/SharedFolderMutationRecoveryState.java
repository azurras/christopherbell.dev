package dev.christopherbell.sharedfolder.service;

/** Durable phases for conditional replacement recovery. */
public enum SharedFolderMutationRecoveryState {
  PREPARED,
  TARGET_QUARANTINED,
  SOURCE_MOVED,
  RESTORE_PENDING
}
