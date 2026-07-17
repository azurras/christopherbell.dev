package dev.christopherbell.sharedfolder.model;

/** The two ordinary filesystem entry types the read-only portal exposes. */
public enum SharedDirectoryEntryType {
  /** An ordinary directory that can be listed. */
  DIRECTORY,
  /** An ordinary file that can be downloaded or safely previewed. */
  FILE
}
