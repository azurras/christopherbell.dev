package dev.christopherbell.libs.api.exception;

/** Infrastructure failure that makes a service operation temporarily unavailable. */
public class ServiceUnavailableException extends RuntimeException {
  public ServiceUnavailableException(String message, Throwable cause) {
    super(message, cause);
  }
}
