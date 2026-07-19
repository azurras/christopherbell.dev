package dev.christopherbell.sharedfolder.recycle;

/** Durable lifecycle for one private recycle payload. */
public enum SharedFolderRecycleState {
  PREPARING,
  RECYCLED,
  RESTORING,
  PURGING
}
