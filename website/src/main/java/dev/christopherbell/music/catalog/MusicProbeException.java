package dev.christopherbell.music.catalog;

/** Safe failure raised when media metadata cannot be trusted. */
public class MusicProbeException extends RuntimeException {
  public MusicProbeException(String message) {
    super(message);
  }

  public MusicProbeException(String message, Throwable cause) {
    super(message, cause);
  }
}
