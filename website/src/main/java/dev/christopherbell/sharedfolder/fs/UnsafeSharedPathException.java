package dev.christopherbell.sharedfolder.fs;

/**
 * Signals that a caller-supplied shared-folder path cannot safely be used for a filesystem
 * operation.
 *
 * <p>The resolver deliberately uses one exception type for malformed paths, missing required
 * existing paths, and link/reparse-point observations. Callers must treat all of them as a
 * fail-closed boundary result rather than attempting a fallback path.
 */
public final class UnsafeSharedPathException extends SecurityException {
  public UnsafeSharedPathException(String message) {
    super(message);
  }

  public UnsafeSharedPathException(String message, Throwable cause) {
    super(message, cause);
  }
}
