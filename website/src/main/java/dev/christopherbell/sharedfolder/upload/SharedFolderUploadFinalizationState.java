package dev.christopherbell.sharedfolder.upload;

/** Durable physical phases for conditional upload replacement. */
public enum SharedFolderUploadFinalizationState {
  PREPARED,
  TARGET_QUARANTINED,
  SOURCE_MOVED,
  RESTORE_PENDING
}
