package dev.christopherbell.vehicle;

/**
 * Exception for non-success NHTSA HTTP responses.
 */
public class NhtsaVinClientException extends Exception {
  private final int statusCode;

  public NhtsaVinClientException(int statusCode) {
    super("NHTSA returned HTTP status " + statusCode);
    this.statusCode = statusCode;
  }

  public int getStatusCode() {
    return statusCode;
  }
}
