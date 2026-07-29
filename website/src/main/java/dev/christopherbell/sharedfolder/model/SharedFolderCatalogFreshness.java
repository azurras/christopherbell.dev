package dev.christopherbell.sharedfolder.model;

/** Public-safe lifecycle of the current shared-folder catalog generation. */
public enum SharedFolderCatalogFreshness {
  BUILDING,
  FRESH,
  STALE,
  FAILED
}
