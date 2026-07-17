package dev.christopherbell.sharedfolder.upload;

/** Persisted lifecycle states for a private resumable shared-folder upload. */
public enum SharedFolderUploadState {
  ACTIVE,
  FINALIZING,
  COMPLETED,
  CANCEL_PENDING,
  CANCELLED,
  EXPIRED
}
