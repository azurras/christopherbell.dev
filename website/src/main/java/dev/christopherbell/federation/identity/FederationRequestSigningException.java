package dev.christopherbell.federation.identity;

/** Signals that a local federation identity could not sign an outbound request. */
public final class FederationRequestSigningException extends RuntimeException {
  public FederationRequestSigningException(String message) {
    super(message);
  }

  public FederationRequestSigningException(String message, Throwable cause) {
    super(message, cause);
  }
}
