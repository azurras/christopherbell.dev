package dev.christopherbell.libs.api.exception;

/** Internal operational failure that callers cannot safely diagnose or correct. */
public class InternalServiceException extends RuntimeException {
  public InternalServiceException(String message, Throwable cause) {
    super(message, cause);
  }
}
