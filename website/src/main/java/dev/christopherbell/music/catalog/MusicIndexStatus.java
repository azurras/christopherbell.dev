package dev.christopherbell.music.catalog;

/** Whether a catalog row is safe to serve for its observed disk revision. */
public enum MusicIndexStatus {
  READY,
  PROBE_FAILED
}
