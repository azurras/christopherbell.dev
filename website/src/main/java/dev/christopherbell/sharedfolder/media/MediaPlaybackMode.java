package dev.christopherbell.sharedfolder.media;

/** Browser delivery decision that never claims an uninspected codec is compatible. */
public enum MediaPlaybackMode {
  DIRECT,
  FALLBACK_REQUIRED,
  TRANSCODING,
  READY
}
