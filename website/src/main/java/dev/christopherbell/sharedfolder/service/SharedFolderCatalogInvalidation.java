package dev.christopherbell.sharedfolder.service;

/** Closed reasons that may advance the shared-folder catalog generation. */
public enum SharedFolderCatalogInvalidation {
  MUTATION,
  RESTORE,
  PURGE,
  UPLOAD
}
