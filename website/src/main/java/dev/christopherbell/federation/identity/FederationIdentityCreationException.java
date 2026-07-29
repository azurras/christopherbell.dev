package dev.christopherbell.federation.identity;

/** Signals that a local federation identity could not be generated safely. */
public final class FederationIdentityCreationException extends RuntimeException {

  FederationIdentityCreationException(String message, Throwable cause) {
    super(message, cause);
  }
}
