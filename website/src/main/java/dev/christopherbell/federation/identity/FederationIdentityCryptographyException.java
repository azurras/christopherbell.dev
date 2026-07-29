package dev.christopherbell.federation.identity;

/** Signals a fail-closed federation private-key encryption or decryption failure. */
public final class FederationIdentityCryptographyException extends RuntimeException {

  FederationIdentityCryptographyException(String message, Throwable cause) {
    super(message, cause);
  }
}
